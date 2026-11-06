# HyperLogLog、定时任务与统计聚合完整实现

## 0. 实现目标

```text
每次跳转：PV +1
每次跳转：UV 按 visitorId 估算
每次跳转：IP 按 clientIp 估算
每天 01:00 聚合到统计表
每天 00:00 清理今日字段
```

---

## 1. Maven 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>
        spring-boot-starter-data-redis
    </artifactId>
</dependency>

<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>
        redisson-spring-boot-starter
    </artifactId>
</dependency>

<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>
        mybatis-plus-boot-starter
    </artifactId>
</dependency>
```

启动类：

```java
@SpringBootApplication
@EnableScheduling
public class AdminApplication {
}
```

---

## 2. 跳转时计数完整代码

```java
@Component
public class AccessCounter {

    private final StringRedisTemplate redisTemplate;
    private final AccessEventProducer producer;

    public AccessCounter(
            StringRedisTemplate redisTemplate,
            AccessEventProducer producer) {
        this.redisTemplate = redisTemplate;
        this.producer = producer;
    }

    public void record(
            LinkEntity link,
            HttpServletRequest request) {
        String date = LocalDate.now().toString();

        String pvKey = String.format(
                "shortlink:click:%d:%s:pv",
                link.getId(), date);
        String uvKey = String.format(
                "shortlink:click:%d:%s:uv",
                link.getId(), date);
        String ipKey = String.format(
                "shortlink:click:%d:%s:ip",
                link.getId(), date);

        redisTemplate.opsForValue().increment(pvKey);
        redisTemplate.expire(
                pvKey, Duration.ofDays(3));

        String visitorId =
                producer.generateVisitorId(request);
        redisTemplate.opsForHyperLogLog()
                .add(uvKey, visitorId);
        redisTemplate.expire(
                uvKey, Duration.ofDays(3));

        String ip = producer.getClientIp(request);
        redisTemplate.opsForHyperLogLog()
                .add(ipKey, ip);
        redisTemplate.expire(
                ipKey, Duration.ofDays(3));
    }
}
```

底层：

```text
INCR shortlink:click:1:2026-10-26:pv
EXPIRE ... 259200

PFADD shortlink:click:1:2026-10-26:uv visitor-id
EXPIRE ... 259200

PFADD shortlink:click:1:2026-10-26:ip 1.2.3.4
EXPIRE ... 259200
```

---

## 3. 查询实时数据

```java
public long getPv(Long linkId, LocalDate date) {
    String key = String.format(
            "shortlink:click:%d:%s:pv",
            linkId, date);
    String value = redisTemplate.opsForValue()
            .get(key);
    return value == null ? 0 : Long.parseLong(value);
}

public long getUv(Long linkId, LocalDate date) {
    String key = String.format(
            "shortlink:click:%d:%s:uv",
            linkId, date);
    Long value = redisTemplate
            .opsForHyperLogLog()
            .size(key);
    return value == null ? 0 : value;
}
```

对应：

```text
GET pv-key
PFCOUNT uv-key
```

---

## 4. 统计实体

```java
@Data
@TableName("t_access_stats")
public class AccessStatsEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long linkId;
    private String linkGid;

    @TableField("stat_date")
    private LocalDate statsDate;

    private Long pv;
    private Long uv;
    private Long ipCount;
}
```

唯一键：

```sql
UNIQUE KEY uk_link_date (link_id, stat_date)
```

---

## 5. 聚合任务完整代码

```java
@Component
public class StatsAggregationTask {

    private final LinkMapper linkMapper;
    private final AccessStatsMapper statsMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    public StatsAggregationTask(
            LinkMapper linkMapper,
            AccessStatsMapper statsMapper,
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient) {
        this.linkMapper = linkMapper;
        this.statsMapper = statsMapper;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
    }

    @Scheduled(cron = "0 0 1 * * ?")
    public void aggregate() {
        RLock lock = redissonClient.getLock(
                "shortlink:lock:stats-aggregation");

        try {
            if (!lock.tryLock(
                    0, 1800, TimeUnit.SECONDS)) {
                return;
            }

            try {
                doAggregate(LocalDate.now().minusDays(1));
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void doAggregate(LocalDate date) {
        List<LinkEntity> links =
                linkMapper.selectList(null);

        for (LinkEntity link : links) {
            aggregateOne(link, date);
        }
    }

    private void aggregateOne(
            LinkEntity link,
            LocalDate date) {
        long pv = readLong(String.format(
                "shortlink:click:%d:%s:pv",
                link.getId(), date));
        long uv = readHll(String.format(
                "shortlink:click:%d:%s:uv",
                link.getId(), date));
        long ip = readHll(String.format(
                "shortlink:click:%d:%s:ip",
                link.getId(), date));

        LambdaQueryWrapper<AccessStatsEntity> wrapper =
                Wrappers.lambdaQuery();
        wrapper.eq(
                AccessStatsEntity::getLinkId,
                link.getId());
        wrapper.eq(
                AccessStatsEntity::getStatsDate,
                date);

        AccessStatsEntity stats =
                statsMapper.selectOne(wrapper);

        long previousPv =
                stats == null || stats.getPv() == null
                        ? 0 : stats.getPv();
        long previousUv =
                stats == null || stats.getUv() == null
                        ? 0 : stats.getUv();
        long previousIp =
                stats == null || stats.getIpCount() == null
                        ? 0 : stats.getIpCount();

        if (stats == null) {
            stats = new AccessStatsEntity();
            stats.setLinkId(link.getId());
            stats.setLinkGid(link.getGid());
            stats.setStatsDate(date);
        }

        stats.setPv(pv);
        stats.setUv(uv);
        stats.setIpCount(ip);

        if (stats.getId() == null) {
            statsMapper.insert(stats);
        } else {
            statsMapper.updateById(stats);
        }

        link.setTotalPv(
                nvl(link.getTotalPv()) + (pv - previousPv));
        link.setTotalUv(
                nvl(link.getTotalUv()) + (uv - previousUv));
        link.setTotalIpCount(
                nvl(link.getTotalIpCount())
                        + (ip - previousIp));
        linkMapper.updateById(link);
    }

    private long readLong(String key) {
        String value = redisTemplate.opsForValue()
                .get(key);
        return value == null ? 0 : Long.parseLong(value);
    }

    private long readHll(String key) {
        Long value = redisTemplate
                .opsForHyperLogLog()
                .size(key);
        return value == null ? 0 : value;
    }

    private long nvl(Long value) {
        return value == null ? 0 : value;
    }
}
```

---

## 6. 为什么按增量更新累计值

第一次执行：

```text
Redis 昨日 PV = 100
数据库 stats.pv = 0
增量 = 100
totalPv += 100
```

重复执行：

```text
Redis 昨日 PV = 100
数据库 stats.pv = 100
增量 = 0
totalPv 不变
```

所以任务重复运行也不会重复累计。

---

## 7. 午夜清零

```java
@Scheduled(cron = "0 0 0 * * ?")
public void resetToday() {
    RLock lock = redissonClient.getLock(
            "shortlink:lock:midnight-reset");

    try {
        if (!lock.tryLock(
                0, 30, TimeUnit.SECONDS)) {
            return;
        }

        try {
            linkMapper.update(
                    null,
                    Wrappers.lambdaUpdate()
                            .set(LinkEntity::getTodayPv, 0)
                            .set(LinkEntity::getTodayUv, 0)
                            .set(LinkEntity::getTodayIpCount, 0));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

---

## 8. @Scheduled 参数

```java
@Scheduled(fixedDelay = 1000)
```

上一次方法结束后等 1 秒。

```java
@Scheduled(fixedRate = 1000)
```

按开始时间计算频率。

```java
@Scheduled(cron = "0 0 1 * * ?")
```

每天 01:00。

Cron 六位：

```text
秒 分 时 日 月 周
```

---

## 9. 为什么定时任务要加分布式锁

每个 admin 实例都会执行 `@Scheduled`。

三个实例：

```text
admin1 执行
admin2 执行
admin3 执行
```

没有锁就会重复聚合。

---

## 10. 验证

设置测试数据：

```bash
redis-cli SET shortlink:click:1:2026-10-26:pv 10
redis-cli PFADD shortlink:click:1:2026-10-26:uv u1 u2 u3
redis-cli PFADD shortlink:click:1:2026-10-26:ip 1.1.1.1 2.2.2.2
```

查看：

```bash
redis-cli GET shortlink:click:1:2026-10-26:pv
redis-cli PFCOUNT shortlink:click:1:2026-10-26:uv
redis-cli PFCOUNT shortlink:click:1:2026-10-26:ip
```

把任务 cron 临时改成每分钟，确认数据库写入。

---

## 11. 从零实现顺序

```text
1. 写 INCR PV
2. 写 PFADD UV
3. 写 PFADD IP
4. 写 PFCOUNT 查询
5. 建 t_access_stats
6. 开 @EnableScheduling
7. 写聚合任务
8. 加 Redisson 锁
9. 用增量更新 total
10. 写午夜清零
```

---

## 12. 本篇自检

```text
[ ] 能写 INCR 和 PFADD
[ ] 能解释 PV 精确、UV 近似
[ ] 能写 cron 聚合任务
[ ] 能解释为什么用增量
[ ] 能写午夜清零
[ ] 能解释多实例为什么要锁
```
