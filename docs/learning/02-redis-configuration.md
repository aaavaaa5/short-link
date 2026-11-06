# 02 Redis 依赖、配置和 Template

## 1. 本章解决什么问题

理解项目中的：

```java
@Autowired
private StringRedisTemplate redisTemplate;
```

为什么能直接使用，以及它背后连接了谁。

本章只讲 Redis 客户端配置和基础操作。
缓存业务放到下一篇。

## 2. 依赖

Spring Data Redis 基础依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

它提供：

```text
spring-data-redis
Lettuce
RedisTemplate
StringRedisTemplate
Redis 自动配置
```

Redisson：

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
</dependency>
```

Redisson 是另一套客户端，本项目主要用于分布式锁。

## 3. YAML 配置

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

这些配置最终进入 Spring `Environment`：

```text
spring.data.redis.host
spring.data.redis.port
```

YAML 本身不连接 Redis，只提供属性。

## 4. RedisConfig 中的字段

```java
@Value("${spring.data.redis.host:localhost}")
private String host;

@Value("${spring.data.redis.port:6379}")
private int port;

@Value("${spring.data.redis.password:}")
private String password;

@Value("${spring.data.redis.database:0}")
private int database;

@Value("${spring.data.redis.timeout:3000}")
private long timeout;
```

字段含义：

| 字段 | 含义 | 单位 |
|---|---|---|
| `host` | Redis 服务地址 | - |
| `port` | Redis 端口 | - |
| `password` | 密码 | - |
| `database` | 逻辑库编号 | - |
| `timeout` | 客户端命令超时 | 毫秒 |

另外四个字段：

```java
@Value("${spring.data.redis.lettuce.pool.max-active:8}")
private int maxActive;

@Value("${spring.data.redis.lettuce.pool.max-idle:8}")
private int maxIdle;

@Value("${spring.data.redis.lettuce.pool.min-idle:2}")
private int minIdle;

@Value("${spring.data.redis.lettuce.pool.max-wait:3000}")
private long maxWait;
```

它们原本用于连接池，但当前代码没有真正装配 `LettucePoolingClientConfiguration`。

所以：

```text
这些字段被读取了
但没有实际控制 Lettuce 连接池
```

如果确实需要连接池：

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-pool2</artifactId>
</dependency>
```

然后使用：

```java
GenericObjectPoolConfig<StatefulConnection<?, ?>> poolConfig =
        new GenericObjectPoolConfig<>();
poolConfig.setMaxTotal(maxActive);
poolConfig.setMaxIdle(maxIdle);
poolConfig.setMinIdle(minIdle);
poolConfig.setMaxWait(Duration.ofMillis(maxWait));

LettucePoolingClientConfiguration clientConfig =
        LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeout))
                .poolConfig(poolConfig)
                .build();
```

## 5. RedisConnectionFactory

```java
@Bean
public RedisConnectionFactory redisConnectionFactory() {
    // ...
}
```

`RedisConnectionFactory` 是连接工厂接口。

它不是 Redis 连接本身。
它负责按需获取连接。

Lettuce 实现：

```text
RedisConnectionFactory
  -> LettuceConnectionFactory
```

Jedis 则会有 `JedisConnectionFactory`。

## 6. RedisStandaloneConfiguration

```java
RedisStandaloneConfiguration standaloneConfig =
        new RedisStandaloneConfiguration();
standaloneConfig.setHostName(host);
standaloneConfig.setPort(port);
standaloneConfig.setDatabase(database);
```

它描述 Redis 服务地址：

```text
host
port
database
password
```

对应常用启动参数：

```text
redis-server --port 6379
```

逻辑库通过：

```text
SELECT database
```

切换。

生产环境集群模式一般没有 `database` 选择。

## 7. LettuceClientConfiguration

```java
LettuceClientConfiguration clientConfig =
        LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeout))
                .build();
```

这个对象描述客户端行为：

```text
命令超时
SSL
连接池
客户端名称
```

它不是 Redis 地址。

创建工厂：

```java
LettuceConnectionFactory factory =
        new LettuceConnectionFactory(standaloneConfig, clientConfig);
return factory;
```

当前项目还调用了：

```java
factory.setShareNativeConnection(false);
```

它影响 native connection 的共享方式。
阅读时不要把这一行等同于“启用了连接池”。

## 8. StringRedisTemplate

```java
@Bean
public StringRedisTemplate stringRedisTemplate(
        RedisConnectionFactory connectionFactory) {
    StringRedisTemplate template = new StringRedisTemplate();
    template.setConnectionFactory(connectionFactory);
    return template;
}
```

`RedisTemplate` 是 Redis 操作入口。

常用 API：

```java
opsForValue()
opsForHash()
opsForList()
opsForSet()
opsForZSet()
opsForHyperLogLog()
opsForStream()
```

`StringRedisTemplate` 等价于：

```text
RedisTemplate<String, String>
```

适合：

```text
字符串值
JSON 字符串
计数器
Bitmap
HyperLogLog
Stream 字段
```

## 9. RedisSerializer

Redis 只认字节。
Java 对象必须先序列化。

```java
RedisSerializer<String> stringSerializer =
        RedisSerializer.string();
```

设置序列化器：

```java
template.setKeySerializer(stringSerializer);
template.setValueSerializer(stringSerializer);
template.setHashKeySerializer(stringSerializer);
template.setHashValueSerializer(stringSerializer);
```

含义：

```text
普通 Key          -> UTF-8 字符串
普通 Value        -> UTF-8 字符串
Hash Field        -> UTF-8 字符串
Hash Value        -> UTF-8 字符串
```

因此 `SET user:1 zhangsan` 在 redis-cli 中可读。

如果使用默认 JDK 序列化，Key 可能出现乱码或额外字节。

## 10. afterPropertiesSet()

```java
template.afterPropertiesSet();
```

`RedisTemplate` 实现了 `InitializingBean`。

Spring 创建 Bean 后也会调用初始化回调。
这里手动调用用于在返回前完成检查。

注意：

```text
afterPropertiesSet 不等于建立 Redis 网络连接
第一次执行命令时才会按需获取连接
```

## 11. RedissonClient

```java
@Bean(destroyMethod = "shutdown")
public RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            .setDatabase(database)
            .setTimeout((int) timeout)
            .setConnectionPoolSize(maxActive)
            .setConnectionMinimumIdleSize(minIdle);
    return Redisson.create(config);
}
```

主要参数：

| 方法 | 含义 |
|---|---|
| `useSingleServer()` | 单机 Redis |
| `setAddress()` | 地址 |
| `setDatabase()` | 逻辑库 |
| `setTimeout()` | 命令超时，毫秒 |
| `setConnectionPoolSize()` | Redisson 连接池大小 |
| `setConnectionMinimumIdleSize()` | 最小空闲连接 |

Redisson 不使用 `RedisConnectionFactory`。
它通过自己的 Netty 客户端建立连接。

## 12. 其他 Redis 部署形态

单机：

```java
config.useSingleServer()
        .setAddress("redis://127.0.0.1:6379");
```

Sentinel：

```java
config.useSentinelServers()
        .setMasterName("mymaster")
        .addSentinelAddress("redis://127.0.0.1:26379");
```

Cluster：

```java
config.useClusterServers()
        .addNodeAddress(
                "redis://127.0.0.1:7001",
                "redis://127.0.0.1:7002",
                "redis://127.0.0.1:7003");
```

Spring Boot 自动配置也可以读取 YAML 并创建连接工厂。
但本项目手写配置，便于理解对象关系和控制细节。

## 13. 常用 RedisTemplate 操作

字符串：

```java
redisTemplate.opsForValue().set("k", "v");
redisTemplate.opsForValue().get("k");
```

```text
SET k v
GET k
```

过期：

```java
redisTemplate.opsForValue()
        .set("k", "v", Duration.ofMinutes(30));
```

```text
SET k v EX 1800
```

计数：

```java
redisTemplate.opsForValue().increment("counter");
```

```text
INCR counter
```

简单分布式占用：

```java
redisTemplate.opsForValue()
        .setIfAbsent("lock", "1", Duration.ofSeconds(30));
```

```text
SET lock 1 NX EX 30
```

Bitmap：

```java
redisTemplate.opsForValue().setBit("bitmap", 10, true);
redisTemplate.opsForValue().getBit("bitmap", 10);
```

```text
SETBIT bitmap 10 1
GETBIT bitmap 10
```

HyperLogLog：

```java
redisTemplate.opsForHyperLogLog().add("uv", "user1");
redisTemplate.opsForHyperLogLog().size("uv");
```

```text
PFADD uv user1
PFCOUNT uv
```

## 14. 本章自检

1. 为什么 YAML 写了 host/port 还不能直接执行 Redis 命令？
2. `RedisConnectionFactory` 和 `RedisTemplate` 分别负责什么？
3. `StringRedisTemplate` 为什么能直接存 JSON 字符串？
4. 四个序列化器分别控制什么？
5. `afterPropertiesSet()` 为什么不会立即建立网络连接？
6. Redisson 为什么必须单独配置？
7. 当前连接池字段为什么没有真正生效？
