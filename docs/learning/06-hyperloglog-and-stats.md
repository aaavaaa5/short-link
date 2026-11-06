# 06 HyperLogLog、PV/UV 与统计聚合

## 1. 本章解决什么问题

理解跳转时如何统计：

```text
PV：访问次数
UV：独立访客数
IP：独立 IP 数
```

以及为什么 UV/IP 使用 HyperLogLog，而不是普通 Set。

## 2. 三类计数

```text
PV：每次访问 +1
UV：同一个 visitorId 只算一次
IP：同一个 IP 只算一次
```

Redis Key：

```text
shortlink:click:{linkId}:{date}:pv
shortlink:click:{linkId}:{date}:uv
shortlink:click:{linkId}:{date}:ip
```

例如：

```text
shortlink:click:100:2026-10-26:pv
```

## 3. PV

代码：

```java
redisTemplate.opsForValue().increment(pvKey);
redisTemplate.expire(pvKey, Duration.ofDays(3));
```

底层：

```text
INCR shortlink:click:100:2026-10-26:pv
EXPIRE shortlink:click:100:2026-10-26:pv 259200
```

PV 是精确计数。

## 4. visitorId

```java
String visitorId = accessEventProducer
        .generateVisitorId(request);
```

定义：

```text
IP + User-Agent 的哈希
```

局限：

```text
同一 WiFi 多设备可能冲突
同一个人换网络会变成另一个访客
浏览器 UA 完全相同时可能被视为同一访客
```

短链跳转通常拿不到登录用户和 Cookie，所以这是合理近似。

## 5. UV

```java
redisTemplate.opsForHyperLogLog()
        .add(uvKey, visitorId);
redisTemplate.expire(uvKey, Duration.ofDays(3));
```

底层：

```text
PFADD shortlink:click:100:2026-10-26:uv visitorId
EXPIRE ...
```

查询：

```java
Long uv = redisTemplate
        .opsForHyperLogLog()
        .size(uvKey);
```

底层：

```text
PFCOUNT shortlink:click:100:2026-10-26:uv
```

## 6. IP 去重

```java
redisTemplate.opsForHyperLogLog()
        .add(ipKey, clientIp);
```

这里直接以 IP 字符串作为元素。

## 7. 为什么不用 Set

Set 方案：

```text
SADD uv visitor1
SADD uv visitor2
SCARD uv
```

优点：

```text
精确
```

缺点：

```text
每个 visitorId 都要存
访问量越大内存越高
```

HyperLogLog：

```text
固定较小内存估算基数
标准误差约 0.81%
```

适合：

```text
展示型 UV 统计
独立 IP 趋势
```

不适合：

```text
计费
精确名单
必须知道具体有哪些访客
```

## 8. TTL

```java
redisTemplate.expire(key, Duration.ofDays(3));
```

为什么保留 3 天：

```text
凌晨聚合任务读取昨天数据
开发调试时任务可能漏跑
统计查询可临时从 Redis 兜底最近几天
```

## 9. 定时聚合任务

```java
@Scheduled(cron = "0 0 1 * * ?")
public void aggregate() {
}
```

每天 01:00 执行：

```text
前一天的访问已经结束
读取前一天 PV/UV/IP
写入 t_access_stats
```

## 10. 分布式锁

多个 admin 实例都会触发 `@Scheduled`。

因此：

```java
RLock lock = redissonClient.getLock(
        "shortlink:lock:stats-aggregation");
if (lock.tryLock(0, 1800, TimeUnit.SECONDS)) {
    doAggregate();
}
```

含义：

```text
不等待
拿到锁的实例执行
租期 30 分钟
```

详细锁机制见 `07-redisson-lock.md`。

## 11. 聚合步骤

```text
1. 取昨天的日期
2. 查询所有短链
3. 对每条短链读取 PV
4. 读取 UV HLL
5. 读取 IP HLL
6. 查 t_access_stats 是否已有当天记录
7. 没有则 insert
8. 有则 update
9. 按增量更新 t_link 累计字段
```

## 12. 为什么更新 t_link 用增量

假设 Redis 昨日数据：

```text
PV = 100
UV = 60
IP = 70
```

`t_access_stats` 中原值：

```text
PV = 90
UV = 50
IP = 65
```

增量：

```text
10, 10, 5
```

任务重复执行时：

```text
yesterdayPv - previousPv = 100 - 100 = 0
```

累计值不会重复增加。

## 13. t_access_stats

表关键字段：

```text
link_id
link_gid
stat_date
pv
uv
ip_count
```

唯一键：

```sql
UNIQUE KEY uk_link_date (link_id, stat_date)
```

查询历史趋势时，优先读聚合表。

## 14. 今日实时数据

今日数据还没有聚合进数据库。

查询时：

```text
历史累计 = t_link.total_xxx
今日实时 = Redis
总累计 = 历史累计 + 今日实时
```

代码对应：

```java
long todayPv = getRedisLong(pvKey);
long todayUv = getRedisHll(uvKey);
long todayIp = getRedisHll(ipKey);
```

## 15. 零点重置

`MidnightResetTask` 每天 00:00 执行：

```java
@Scheduled(cron = "0 0 0 * * ?")
```

它清空 `t_link.today_*`。

但实时页面主要仍从 Redis 读取今日值。

这两个逻辑并存是为了：

```text
数据库字段保留今日快照
查询服务可以直接从 Redis 获取实时值
```

## 16. 同类方案

| 需求 | 方案 |
|---|---|
| 精确 UV | Redis Set、Bitmap、数据库去重表 |
| 大规模近似 UV | HyperLogLog |
| 明细审计 | 访问日志表 |
| 即时趋势 | Redis 计数器 |
| 历史报表 | 聚合表 |

实际系统通常同时保留：

```text
实时计数
每日聚合
原始明细
```

它们解决不同查询需求。

## 17. 本章自检

1. PV/UV/IP 分别对应什么 Redis 命令？
2. 为什么 UV 默认使用 HLL？
3. HLL 的误差和内存特点是什么？
4. 为什么聚合任务在 01:00，而不是 00:00？
5. 重复执行聚合任务为什么不会重复累计？
6. 今日实时查询为什么要合并 Redis 和数据库？
7. visitorId 为什么不是严格用户身份？
