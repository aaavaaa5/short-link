# 05 Redis Stream 异步流水线

## 1. 本章解决什么问题

短链跳转不能等待：

```text
解析 User-Agent
查询 IP 地理位置
写访问日志表
```

所以项目采用：

```text
project 生产 Stream
  -> admin 消费 Stream
  -> 解析 UA/GeoIP
  -> 写 t_access_log
```

本章讲清楚生产、消费、ACK、Pending 和幂等。

## 2. 依赖

Stream 使用 Spring Data Redis，不需要额外消息队列依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

`StringRedisTemplate` 提供：

```java
redisTemplate.opsForStream()
```

底层操作：

```text
XADD
XREADGROUP
XACK
XPENDING
XCLAIM
```

## 3. 为什么不用普通 Redis List

Redis List：

```text
LPUSH 生产
BRPOP 消费
```

问题：

```text
没有消费组
没有 ACK
没有 Pending 记录
消费者异常后难以判断消息状态
```

Redis Stream 提供：

```text
消息 ID
消费组
消费者名称
Pending 列表
ACK
消息重新领取
```

## 4. 生产者代码

```java
@Component
public class AccessEventProducer {

    public static final String STREAM_KEY =
            "shortlink:access:stream";

    @Autowired
    private StringRedisTemplate redisTemplate;

    public void sendAccessEvent(
            LinkEntity entity,
            HttpServletRequest request) {
        Map<String, String> message =
                buildMessage(entity, request);
        redisTemplate.opsForStream()
                .add(MapRecord.create(STREAM_KEY, message));
    }
}
```

`MapRecord` 表示 Stream 消息。

底层近似：

```text
XADD shortlink:access:stream * link_id 1 short_uri abc123 ...
```

## 5. 消息字段

```text
link_id
link_gid
short_uri
visitor_id
visitor_ip
user_agent
referer
access_time
```

为什么消息中带 `link_gid`：

```text
t_access_log 按 link_gid 分片
消费者插入日志时需要分片键
```

为什么带 `access_time`：

```text
消费时间可能晚于用户点击时间
统计应该记录用户真实访问时间
```

## 6. 访问者标识

```java
String ip = getClientIp(request);
String ua = request.getHeader("User-Agent");
return UUID.nameUUIDFromBytes(
        (ip + ua).getBytes())
        .toString()
        .replace("-", "")
        .substring(0, 16);
```

含义：

```text
同一 IP + 同一 UA -> 同一个 visitorId
```

这是近似访客标识，不是登录用户 ID。

## 7. 获取客户端 IP

优先读取代理头：

```java
X-Forwarded-For
X-Real-IP
request.getRemoteAddr()
```

`X-Forwarded-For` 可能是：

```text
client, proxy1, proxy2
```

代码取第一个。

生产环境必须保证只有可信代理可以伪造这些请求头。

## 8. Stream 长度控制

代码：

```java
redisTemplate.opsForStream()
        .trim(STREAM_KEY, 100_000, false);
```

表示大约保留最新 10 万条。

为什么需要 trim：

```text
Stream 会持续增长
不裁剪会占用越来越多 Redis 内存
```

更严谨的写法可以在 `XADD` 时带上 MAXLEN，避免 add 和 trim 分开执行。

## 9. 消费者组

常量：

```text
STREAM_KEY = shortlink:access:stream
GROUP = admin-consumer-group
```

创建消费者组：

```java
connection.streamCommands().xGroupCreate(
        key,
        GROUP,
        ReadOffset.from("0"),
        true);
```

参数含义：

```text
key：Stream 名称
GROUP：消费组名称
ReadOffset.from("0")：从头开始消费
true：Stream 不存在时创建
```

多个 admin 实例使用同一个组，会自动分摊消息。

## 10. 消费者名称

代码用：

```text
应用名 + 主机名 + PID + 随机后缀
```

目的：

```text
每个实例有唯一消费者名称
避免两个实例使用同一个名称
```

## 11. 为什么用 @Scheduled 拉取

```java
@Scheduled(fixedDelay = 1000)
public void consume() {
    prepareRuntimeState();
    ensureGroupReady();
    consumePending();
    consumeNew();
}
```

启动类必须有：

```java
@EnableScheduling
```

`fixedDelay = 1000`：

```text
上一次 consume() 结束后，再等待 1 秒执行下一次
```

这与 `fixedRate` 不同。

这里采用拉模式，而不是 Stream Listener：

```text
实现简单
容易控制批量大小
容易先处理 Pending
适合教学项目
```

## 12. 读取新消息

```java
List<MapRecord<String, Object, Object>> records =
        redisTemplate.opsForStream().read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(100),
                StreamOffset.create(
                        STREAM_KEY, ReadOffset.lastConsumed()));
```

底层：

```text
XREADGROUP GROUP admin-consumer-group consumer
  COUNT 100
  STREAMS shortlink:access:stream >
```

`>` 表示尚未投递给消费组的新消息。

## 13. 为什么先处理 Pending

Pending 表示：

```text
消息已经投递给某个消费者
但还没有 ACK
```

可能原因：

```text
消费者宕机
数据库写入失败
进程被重启
```

因此消费顺序：

```text
先 Pending
再新消息
```

避免失败消息永远排在后面。

## 14. XCLAIM 重新领取

代码：

```java
redisTemplate.opsForStream().claim(
        STREAM_KEY,
        GROUP,
        consumerName,
        Duration.ofSeconds(30),
        msg.getId());
```

含义：

```text
找 Pending 中空闲超过 30 秒的消息
重新分配给当前消费者
```

如果消息刚投递不久，不立即抢走，避免多个消费者重复处理。

## 15. 处理消息

消费者步骤：

```text
读取字段
创建 AccessLogEntity
UA 正则解析
GeoIP 离线解析
解析访问时间
插入 t_access_log
```

UA 解析：

```java
userAgentUtil.parseBrowser(userAgent)
userAgentUtil.parseOs(userAgent)
userAgentUtil.parseDevice(userAgent)
```

GeoIP：

```java
geoIpUtil.parseCountry(ip)
geoIpUtil.parseProvince(ip)
geoIpUtil.parseCity(ip)
```

解析结果写入：

```text
browser
os
device_type
country
province
city
```

## 16. 本地解析缓存

UA 和 GeoIP 解析可能重复。

消费者用 Caffeine：

```java
Cache<String, UserAgentParts> userAgentCache =
        Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(24, TimeUnit.HOURS)
                .build();
```

作用：

```text
同一个 User-Agent 只解析一次
同一个 IP 只查一次地区
```

注意：这是消费者自己的本地缓存，与跳转缓存无关。

## 17. ACK

处理成功：

```java
redisTemplate.opsForStream()
        .acknowledge(STREAM_KEY, GROUP, recordId);
```

底层：

```text
XACK shortlink:access:stream admin-consumer-group message-id
```

处理失败：

```text
不要 ACK
```

消息继续留在 Pending 中等待重新领取。

## 18. 表唯一索引和幂等

建表：

```sql
UNIQUE KEY uk_link_gid_message (link_gid, message_id)
```

消费者把 Stream 消息 ID 写入 `message_id`。

重复消费时：

```text
插入相同 link_gid + message_id
  -> DuplicateKeyException
  -> 视为已处理
  -> ACK
```

这就是：

```text
至少一次消费 + 数据库幂等
```

不是 MQ 保证“正好一次”。

## 19. 访问日志分片

ShardingSphere 配置：

```text
逻辑表：t_access_log
分片键：link_gid
```

因为消息中带 `link_gid`，消费者插入时可以精准路由。

## 20. 错误处理

生产者：

```java
try {
    // XADD
} catch (Exception e) {
    log.error(...);
}
```

访问日志失败不应该阻塞跳转。

消费者：

```java
try {
    // parse + insert
    return true;
} catch (Exception e) {
    return false;
}
```

失败返回 false，调用方不 ACK。

## 21. 同类方案

| 方案 | 特点 |
|---|---|
| Redis List | 简单，没有消费组和 ACK |
| Redis Pub/Sub | 不持久，断线丢消息 |
| Redis Stream | 消费组、Pending、ACK，适合已有 Redis |
| Kafka | 高吞吐、分区、持久化，适合大规模日志 |
| RabbitMQ | 路由灵活、确认机制成熟 |

本项目选择 Stream，是为了不额外部署消息中间件。

## 22. 本章自检

1. `XADD`、`XREADGROUP`、`XACK` 分别做什么？
2. 为什么消费失败不能 ACK？
3. Pending 列表是什么？
4. 为什么先消费 Pending 再消费新消息？
5. 为什么 `@Scheduled(fixedDelay=1000)` 不一定每秒执行一次？
6. 为什么消息里必须带 `link_gid`？
7. 数据库唯一索引如何实现幂等？
8. 为什么生产失败通常不能阻塞跳转？
