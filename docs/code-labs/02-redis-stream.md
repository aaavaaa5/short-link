# Redis Stream 完整实现

## 0. 实现目标

最终得到一条可靠的异步日志链路：

```text
跳转接口
  -> XADD 写入 Redis Stream
  -> 立即返回跳转

定时消费者
  -> XREADGROUP 批量读取
  -> 解析 UA/IP
  -> 写 MySQL
  -> 成功后 XACK
```

这篇直接给出可照着写的完整代码，不依赖你记住项目源码位置。

---

## 1. Maven 依赖

Redis Stream 只需要 Spring Data Redis：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>
        spring-boot-starter-data-redis
    </artifactId>
</dependency>
```

写数据库需要：

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>
        mybatis-plus-boot-starter
    </artifactId>
</dependency>

<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

定时任务需要：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>
        spring-boot-starter-web
    </artifactId>
</dependency>
```

`@Scheduled` 本身在 Spring Context 中，不需要额外 starter。

---

## 2. YAML 配置

```yaml
spring:
  application:
    name: shortlink-admin
  data:
    redis:
      host: localhost
      port: 6379

shortlink:
  access-event:
    consumer-name:
    zone-id: Asia/Shanghai
```

`consumer-name` 留空时，程序自动生成：

```text
应用名-主机名-PID-随机后缀
```

目的是让多个实例拥有唯一消费名。

---

## 3. 开启定时任务

```java
@SpringBootApplication
@EnableScheduling
public class AdminApplication {
    public static void main(String[] args) {
        SpringApplication.run(
                AdminApplication.class, args);
    }
}
```

没有 `@EnableScheduling`，消费者不会定时执行。

---

## 4. 生产者完整实现

```java
package com.app.mq;

import cn.hutool.core.util.StrUtil;
import com.app.entity.LinkEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class AccessEventProducer {

    public static final String STREAM_KEY =
            "shortlink:access:stream";

    private static final int MAX_LEN = 100_000;

    private final StringRedisTemplate redisTemplate;

    public AccessEventProducer(
            StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void sendAccessEvent(
            LinkEntity entity,
            HttpServletRequest request) {
        try {
            Map<String, String> message =
                    buildMessage(entity, request);

            redisTemplate.opsForStream()
                    .add(MapRecord.create(
                            STREAM_KEY, message));

            redisTemplate.opsForStream()
                    .trim(STREAM_KEY, MAX_LEN, false);
        } catch (Exception e) {
            // 日志失败不能影响跳转
            e.printStackTrace();
        }
    }

    private Map<String, String> buildMessage(
            LinkEntity entity,
            HttpServletRequest request) {
        Map<String, String> message = new HashMap<>();
        message.put("link_id",
                String.valueOf(entity.getId()));
        message.put("link_gid",
                entity.getGid() == null
                        ? ""
                        : entity.getGid());
        message.put("short_uri",
                entity.getShortUri());
        message.put("visitor_id",
                generateVisitorId(request));
        message.put("visitor_ip",
                getClientIp(request));
        message.put("user_agent",
                defaultString(request.getHeader("User-Agent")));
        message.put("referer",
                defaultString(request.getHeader("Referer")));
        message.put("access_time",
                String.valueOf(System.currentTimeMillis()));
        return message;
    }

    public String generateVisitorId(
            HttpServletRequest request) {
        String ip = getClientIp(request);
        String ua = defaultString(
                request.getHeader("User-Agent"));

        return UUID.nameUUIDFromBytes(
                        (ip + ua).getBytes())
                .toString()
                .replace("-", "")
                .substring(0, 16);
    }

    public String getClientIp(
            HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(xff)) {
            return xff.split(",")[0].trim();
        }

        String xri = request.getHeader("X-Real-IP");
        if (StrUtil.isNotBlank(xri)) {
            return xri.trim();
        }

        return request.getRemoteAddr();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }
}
```

---

## 5. 生产者逐段解释

### 5.1 为什么注入 StringRedisTemplate

```java
private final StringRedisTemplate redisTemplate;
```

因为 Stream 的字段都是字符串：

```text
link_id
short_uri
user_agent
```

`StringRedisTemplate` 可以避免 Java 对象序列化。

### 5.2 构造器注入

```java
public AccessEventProducer(
        StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
}
```

Spring 创建 `AccessEventProducer` 时：

```text
发现构造器参数
  -> 查找 StringRedisTemplate Bean
  -> 调用构造器
```

### 5.3 为什么用 Map

```java
Map<String, String> message = new HashMap<>();
```

Stream 的每条消息本质上是多个 field-value：

```text
link_id 1
short_uri abc123
visitor_ip 1.2.3.4
```

### 5.4 写入 Stream

```java
redisTemplate.opsForStream()
        .add(MapRecord.create(STREAM_KEY, message));
```

底层：

```text
XADD shortlink:access:stream *
  link_id 1
  short_uri abc123
  ...
```

### 5.5 裁剪 Stream

```java
redisTemplate.opsForStream()
        .trim(STREAM_KEY, MAX_LEN, false);
```

底层近似：

```text
XTRIM shortlink:access:stream MAXLEN ~ 100000
```

如果不裁剪，Stream 会持续增长。

### 5.6 为什么 catch 异常

访问日志是辅助功能。
Redis Stream 写入失败不应该让用户跳转失败。

---

## 6. 消费者完整实现

下面是一个完整的核心版本：

```java
package com.app.mq;

import com.app.entity.AccessLogEntity;
import com.app.mapper.AccessLogMapper;
import com.app.util.GeoIpUtil;
import com.app.util.UserAgentUtil;
import jakarta.annotation.PostConstruct;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Component
public class AccessEventConsumer {

    private static final String STREAM_KEY =
            "shortlink:access:stream";
    private static final String GROUP =
            "admin-consumer-group";
    private static final int BATCH_SIZE = 100;
    private static final Duration PENDING_IDLE =
            Duration.ofSeconds(30);

    private final StringRedisTemplate redisTemplate;
    private final AccessLogMapper accessLogMapper;
    private final UserAgentUtil userAgentUtil;
    private final GeoIpUtil geoIpUtil;

    private final String consumerName =
            "admin-" + ProcessHandle.current().pid();
    private volatile boolean groupReady;

    public AccessEventConsumer(
            StringRedisTemplate redisTemplate,
            AccessLogMapper accessLogMapper,
            UserAgentUtil userAgentUtil,
            GeoIpUtil geoIpUtil) {
        this.redisTemplate = redisTemplate;
        this.accessLogMapper = accessLogMapper;
        this.userAgentUtil = userAgentUtil;
        this.geoIpUtil = geoIpUtil;
    }

    @PostConstruct
    public void init() {
        ensureGroup();
    }

    @Scheduled(fixedDelay = 1000)
    public void consume() {
        ensureGroup();
        consumePending();
        consumeNew();
    }

    private synchronized void ensureGroup() {
        if (groupReady) {
            return;
        }

        try {
            byte[] key = redisTemplate
                    .getStringSerializer()
                    .serialize(STREAM_KEY);

            redisTemplate.execute(
                    (RedisCallback<String>) connection ->
                            connection.streamCommands()
                                    .xGroupCreate(
                                            key,
                                            GROUP,
                                            ReadOffset.from("0"),
                                            true));
            groupReady = true;
        } catch (Exception e) {
            if (e.getMessage() != null
                    && e.getMessage().contains("BUSYGROUP")) {
                groupReady = true;
            } else {
                throw e;
            }
        }
    }

    private void consumeNew() {
        List<MapRecord<String, Object, Object>> records =
                redisTemplate.opsForStream().read(
                        Consumer.from(GROUP, consumerName),
                        StreamReadOptions.empty()
                                .count(BATCH_SIZE),
                        StreamOffset.create(
                                STREAM_KEY,
                                ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record
                : records) {
            if (process(record)) {
                ack(record.getId());
            }
        }
    }

    private void consumePending() {
        PendingMessages pending =
                redisTemplate.opsForStream().pending(
                        STREAM_KEY,
                        GROUP,
                        Range.unbounded(),
                        BATCH_SIZE);

        if (pending == null || pending.isEmpty()) {
            return;
        }

        for (PendingMessage message : pending) {
            if (message.getElapsedTimeSinceLastDelivery()
                    .compareTo(PENDING_IDLE) < 0) {
                continue;
            }

            List<MapRecord<String, Object, Object>> claimed =
                    redisTemplate.opsForStream().claim(
                            STREAM_KEY,
                            GROUP,
                            consumerName,
                            PENDING_IDLE,
                            message.getId());

            if (claimed == null) {
                continue;
            }

            for (MapRecord<String, Object, Object> record
                    : claimed) {
                if (process(record)) {
                    ack(record.getId());
                }
            }
        }
    }

    private boolean process(
            MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> map = record.getValue();
            String ua = string(map, "user_agent");
            String ip = string(map, "visitor_ip");

            AccessLogEntity entity = new AccessLogEntity();
            entity.setMessageId(record.getId().getValue());
            entity.setLinkId(
                    longValue(map, "link_id"));
            entity.setLinkGid(string(map, "link_gid"));
            entity.setShortUri(string(map, "short_uri"));
            entity.setVisitorId(string(map, "visitor_id"));
            entity.setVisitorIp(ip);
            entity.setReferer(string(map, "referer"));
            entity.setUserAgent(ua);

            entity.setBrowser(
                    userAgentUtil.parseBrowser(ua));
            entity.setOs(
                    userAgentUtil.parseOs(ua));
            entity.setDevice(
                    userAgentUtil.parseDevice(ua));

            entity.setCountry(
                    geoIpUtil.parseCountry(ip));
            entity.setProvince(
                    geoIpUtil.parseProvince(ip));
            entity.setCity(
                    geoIpUtil.parseCity(ip));

            long millis = Long.parseLong(
                    string(map, "access_time"));
            entity.setAccessTime(
                    LocalDateTime.ofInstant(
                            Instant.ofEpochMilli(millis),
                            ZoneId.of("Asia/Shanghai")));

            accessLogMapper.insert(entity);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void ack(RecordId id) {
        redisTemplate.opsForStream()
                .acknowledge(STREAM_KEY, GROUP, id.getValue());
    }

    private String string(
            Map<Object, Object> map,
            String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private Long longValue(
            Map<Object, Object> map,
            String key) {
        String value = string(map, key);
        return value == null ? null : Long.valueOf(value);
    }
}
```

---

## 7. 消费者执行顺序

```text
Spring 启动
  -> 创建 StringRedisTemplate
  -> 创建 AccessEventConsumer
  -> 调用 @PostConstruct init()
  -> 创建消费组

每秒 fixedDelay 调度
  -> 确保消费组存在
  -> 先处理 Pending
  -> 再处理新消息
  -> 写 MySQL
  -> 成功 ACK
```

---

## 8. 每个 Redis 方法对应什么命令

### 创建消费组

```java
xGroupCreate(...)
```

```text
XGROUP CREATE shortlink:access:stream
  admin-consumer-group 0 MKSTREAM
```

### 读取新消息

```java
read(Consumer.from(GROUP, consumerName), ...)
```

```text
XREADGROUP GROUP admin-consumer-group consumer
  COUNT 100
  STREAMS shortlink:access:stream >
```

### 查看 Pending

```java
pending(STREAM_KEY, GROUP, Range.unbounded(), 100)
```

```text
XPENDING shortlink:access:stream
  admin-consumer-group - + 100
```

### 重新领取

```java
claim(...)
```

```text
XCLAIM shortlink:access:stream
  admin-consumer-group new-consumer
  30000 message-id
```

### ACK

```java
acknowledge(...)
```

```text
XACK shortlink:access:stream
  admin-consumer-group message-id
```

---

## 9. 为什么必须做数据库幂等

Redis Stream 通常保证至少一次消费：

```text
消费者写库成功
  -> 准备 ACK
  -> 进程在 ACK 前崩溃
  -> 消息回到 Pending
  -> 另一个消费者再次写库
```

所以表要加唯一键：

```sql
CREATE TABLE t_access_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id VARCHAR(64),
    link_gid VARCHAR(32),
    link_id BIGINT,
    visitor_ip VARCHAR(45),
    user_agent VARCHAR(1024),
    access_time DATETIME(3),
    UNIQUE KEY uk_link_gid_message (
        link_gid, message_id
    )
);
```

重复消息：

```text
insert 抛出 DuplicateKeyException
  -> 说明业务已经成功
  -> ACK
```

`message_id` 就是 Redis Stream 的消息 ID，例如：

```text
1720000000000-0
```

---

## 10. 生产失败与消费失败

### 生产失败

```text
XADD 失败
  -> 访问日志丢失
  -> 用户仍应成功跳转
```

因此生产者要 catch。

### 消费失败

```text
解析失败
数据库失败
  -> 不 ACK
  -> 消息留在 Pending
```

因此消费者要返回 boolean。

---

## 11. 测试 Stream 命令

写入一条：

```bash
redis-cli XADD shortlink:access:stream '*' \
  link_id 1 \
  link_gid u_1 \
  short_uri abc123 \
  visitor_id v1 \
  visitor_ip 127.0.0.1 \
  user_agent Mozilla \
  access_time 1720000000000
```

查看长度：

```bash
redis-cli XLEN shortlink:access:stream
```

查看消费组：

```bash
redis-cli XINFO GROUPS shortlink:access:stream
```

查看 Pending：

```bash
redis-cli XPENDING shortlink:access:stream \
  admin-consumer-group
```

---

## 12. 从零实现顺序

```text
1. 加 starter-data-redis
2. 加定时任务启动注解
3. 写生产者，先只 XADD 一条消息
4. 用 redis-cli XLEN 验证
5. 写消费者组创建
6. 写 XREADGROUP
7. 打印消息
8. 写数据库 insert
9. 写 ACK
10. 人为让 insert 失败，确认不 ACK
11. 加 Pending 重试
12. 加唯一索引和重复消息处理
```

---

## 13. 本篇自检

```text
[ ] 能写出 XADD 生产者
[ ] 能解释 MapRecord
[ ] 能创建消费者组
[ ] 能写 XREADGROUP
[ ] 能解释 Pending
[ ] 能使用 XCLAIM
[ ] 能解释为什么失败不能 ACK
[ ] 能写唯一索引实现幂等
[ ] 能用 redis-cli 查看消息状态
```
