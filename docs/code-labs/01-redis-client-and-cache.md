# Redis 客户端与缓存完整实现

## 0. 读完这一篇能做什么

你应当能够在一个新的 Spring Boot 3 项目中独立写出：

```text
Redis 连接配置
StringRedisTemplate Bean
RedissonClient Bean
Caffeine 本地缓存
CacheService 接口
CacheServiceImpl 实现
Cache-Aside 查询和回填
管理端更新后删除 Redis
用 redis-cli 验证最终结果
```

本篇直接展示完整代码，再逐段解释。
不需要先找到源码位置，重点是把代码写出来并理解调用顺序。

---

## 1. 先看最终链路

短链跳转时：

```text
RedirectController
  -> RedirectServiceImpl.getLinkEntity(shortUri)
  -> CacheServiceImpl.getLinkEntity(shortUri)
       -> Caffeine getIfPresent
       -> Redis GET shortlink:{shortUri}
       -> 命中 Redis 后回填 Caffeine
  -> 未命中时查 MySQL
  -> CacheServiceImpl.cacheLinkEntity(...)
       -> Caffeine put
       -> Redis SET ... EX ttl
```

管理端更新短链时：

```text
LinkServiceImpl.update(...)
  -> linkMapper.update(...)
  -> redisTemplate.delete("shortlink:" + shortUri)
```

---

## 2. Maven 依赖

### 2.1 Spring Data Redis

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>
        spring-boot-starter-data-redis
    </artifactId>
</dependency>
```

它提供：

```text
RedisTemplate
StringRedisTemplate
LettuceConnectionFactory
Redis 自动配置
```

### 2.2 Redisson

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>
        redisson-spring-boot-starter
    </artifactId>
</dependency>
```

主要提供：

```text
RedissonClient
RLock
Redisson 自动配置
```

### 2.3 Caffeine

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

### 2.4 项目公共模块

```xml
<dependency>
    <groupId>com.app</groupId>
    <artifactId>common</artifactId>
    <version>${project.version}</version>
</dependency>
```

`common` 中放：

```text
CacheKeys
BloomFilterHelper
BloomFilterConfig
```

### 2.5 JSON 序列化

```xml
<dependency>
    <groupId>com.alibaba.fastjson2</groupId>
    <artifactId>fastjson2</artifactId>
</dependency>
```

项目用它把：

```text
LinkEntity <-> JSON 字符串
```

---

## 3. YAML 配置

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

如果 Redis 有密码：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: 123456
      database: 0
```

如果要给自定义配置类提供更多值：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password:
      database: 0
      timeout: 3000
      lettuce:
        pool:
          max-active: 8
          max-idle: 8
          min-idle: 2
          max-wait: 3000
```

这些键最终会成为 Spring Environment 中的属性：

```text
spring.data.redis.host
spring.data.redis.port
spring.data.redis.password
spring.data.redis.database
spring.data.redis.timeout
```

---

## 4. 项目中的完整 RedisConfig

完整代码：

```java
package com.app.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;

@Configuration
public class RedisConfig {

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

    @Value("${spring.data.redis.lettuce.pool.max-active:8}")
    private int maxActive;

    @Value("${spring.data.redis.lettuce.pool.max-idle:8}")
    private int maxIdle;

    @Value("${spring.data.redis.lettuce.pool.min-idle:2}")
    private int minIdle;

    @Value("${spring.data.redis.lettuce.pool.max-wait:3000}")
    private long maxWait;

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration standaloneConfig =
                new RedisStandaloneConfiguration();
        standaloneConfig.setHostName(host);
        standaloneConfig.setPort(port);
        standaloneConfig.setDatabase(database);

        if (password != null && !password.isBlank()) {
            standaloneConfig.setPassword(password);
        }

        LettuceClientConfiguration clientConfig =
                LettuceClientConfiguration.builder()
                        .commandTimeout(Duration.ofMillis(timeout))
                        .build();

        LettuceConnectionFactory factory =
                new LettuceConnectionFactory(
                        standaloneConfig, clientConfig);
        factory.setShareNativeConnection(false);
        return factory;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(
            RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);

        RedisSerializer<String> stringSerializer =
                RedisSerializer.string();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        String address = "redis://" + host + ":" + port;
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setTimeout((int) timeout)
                .setConnectionPoolSize(maxActive)
                .setConnectionMinimumIdleSize(minIdle);

        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }

        return Redisson.create(config);
    }
}
```

---

## 5. 逐段解释 RedisConfig

### 5.1 `@Configuration`

```java
@Configuration
public class RedisConfig {
}
```

Spring 启动扫描到它后：

```text
把它当成配置类
解析其中所有 @Bean 方法
```

默认 `proxyBeanMethods = true`，Spring 会为配置类生成代理，保证 `@Bean` 方法调用获得单例。

### 5.2 `@Value`

```java
@Value("${spring.data.redis.host:localhost}")
private String host;
```

拆开：

```text
${...}          占位符
spring.data...  YAML 属性名
:localhost      找不到属性时的默认值
```

Spring 创建 `RedisConfig` 对象后，通过反射给字段赋值。

### 5.3 为什么 `int` 也能直接从 YAML 注入

```java
@Value("${spring.data.redis.port:6379}")
private int port;
```

YAML 中虽然是数字，进入 Environment 后首先是文本形式。
Spring 的转换服务会把它转换为 `int`。

### 5.4 `RedisConnectionFactory`

```java
@Bean
public RedisConnectionFactory redisConnectionFactory() {
}
```

`@Bean` 告诉 Spring：

```text
这个方法的返回值是一个 Bean
Bean 名称默认是 redisConnectionFactory
Bean 类型是 RedisConnectionFactory
```

返回值实际是：

```java
LettuceConnectionFactory
```

它实现了：

```text
RedisConnectionFactory
```

所以按接口类型注入时能找到。

### 5.5 `RedisStandaloneConfiguration`

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

这里不是建立连接，只是保存连接参数。

### 5.6 密码判断

```java
if (password != null && !password.isBlank()) {
    standaloneConfig.setPassword(password);
}
```

避免空字符串被当作真实密码。

### 5.7 `LettuceClientConfiguration`

```java
LettuceClientConfiguration clientConfig =
        LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeout))
                .build();
```

表示：

```text
Redis 命令最多等待 timeout 毫秒
```

它不是 Redis 地址。

### 5.8 创建连接工厂

```java
LettuceConnectionFactory factory =
        new LettuceConnectionFactory(
                standaloneConfig, clientConfig);
```

`standaloneConfig` 决定连哪里。
`clientConfig` 决定客户端行为。

### 5.9 连接池字段的真实情况

当前代码注入了：

```java
maxActive
maxIdle
minIdle
maxWait
```

但：

```java
LettuceClientConfiguration.builder()
```

不是连接池配置。

所以这四个字段在 Lettuce 这里没有真正使用。

真正启用连接池要改为：

```java
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.springframework.data.redis.connection.lettuce.LettucePoolingClientConfiguration;

GenericObjectPoolConfig<?> poolConfig =
        new GenericObjectPoolConfig<>();
poolConfig.setMaxTotal(maxActive);
poolConfig.setMaxIdle(maxIdle);
poolConfig.setMinIdle(minIdle);

LettucePoolingClientConfiguration clientConfig =
        LettucePoolingClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeout))
                .poolConfig(poolConfig)
                .build();
```

同时需要 `commons-pool2` 依赖。

### 5.10 `StringRedisTemplate`

```java
@Bean
public StringRedisTemplate stringRedisTemplate(
        RedisConnectionFactory connectionFactory) {
}
```

方法参数：

```text
Spring 按 RedisConnectionFactory 类型注入
```

方法返回：

```text
一个 StringRedisTemplate Bean
```

业务代码可以写：

```java
@Autowired
private StringRedisTemplate redisTemplate;
```

因为 Bean 类型与字段类型一致。

### 5.11 序列化器

```java
RedisSerializer<String> stringSerializer =
        RedisSerializer.string();
```

Redis 最终存字节。
这个序列化器负责：

```text
String -> UTF-8 bytes
bytes -> String
```

设置四个位置：

```java
template.setKeySerializer(stringSerializer);
template.setValueSerializer(stringSerializer);
template.setHashKeySerializer(stringSerializer);
template.setHashValueSerializer(stringSerializer);
```

对应：

```text
普通 Key
普通 Value
Hash Field
Hash Value
```

保证 Redis 中内容可读。

### 5.12 `afterPropertiesSet()`

```java
template.afterPropertiesSet();
```

`RedisTemplate` 实现了 `InitializingBean`。
它用于检查配置、完成初始化。

不是连接 Redis。
第一次执行命令时才真正获取连接。

### 5.13 RedissonClient

```java
@Bean(destroyMethod = "shutdown")
public RedissonClient redissonClient() {
}
```

`destroyMethod`：

```text
Spring 容器关闭时调用 redissonClient.shutdown()
```

Redisson 是独立客户端：

```text
StringRedisTemplate -> Lettuce
RedissonClient      -> Redisson Netty 客户端
```

它们连接同一个 Redis 服务，但不共享连接池。

---

## 6. Spring Boot 自动配置为什么不会冲突

`@SpringBootApplication` 包含 `@EnableAutoConfiguration`。

Spring Boot Redis 自动配置大致逻辑：

```java
@ConditionalOnClass(RedisOperations.class)
@ConditionalOnMissingBean(RedisConnectionFactory.class)
public RedisConnectionFactory redisConnectionFactory(...) {
}
```

用户自己的 `@Bean` 已存在时：

```text
@ConditionalOnMissingBean 条件不满足
默认 Bean 不创建
```

所以当前项目不需要关闭自动配置。

---

## 7. Caffeine 完整配置

完整代码：

```java
package com.app.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<String, String> shortLinkEntityCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .recordStats()
                .build();
    }
}
```

解释：

```text
Bean 名称：shortLinkEntityCache
Bean 类型：Cache<String, String>
Key：shortUri
Value：LinkEntity JSON
maximumSize：最多 1 万个 Key
expireAfterWrite：写入 60 秒后过期
recordStats：开启命中率统计
```

---

## 8. Redis Key 完整代码

完整代码：

```java
package com.app.common.cache;

public final class CacheKeys {

    private CacheKeys() {
    }

    private static final String LINK_ENTITY_PREFIX =
            "shortlink:";
    private static final String NULL_MARK_PREFIX =
            "shortlink:null:";

    public static String linkEntity(String shortUri) {
        return LINK_ENTITY_PREFIX + shortUri;
    }

    public static String nullMark(String shortUri) {
        return NULL_MARK_PREFIX + shortUri;
    }
}
```

为什么放公共模块：

```text
project 负责写缓存
admin 负责删除缓存
两边必须拼出完全相同的 Key
```

如果分别手写字符串，很容易：

```text
project 写 shortlink:abc123
admin 删 short-link:abc123
```

---

## 9. CacheService 完整接口

```java
package com.app.service;

import com.app.entity.LinkEntity;
import java.time.Duration;

public interface CacheService {

    LinkEntity getLinkEntity(String shortUri);

    void cacheLinkEntity(
            String shortUri,
            LinkEntity entity,
            Duration ttl);

    boolean mightExist(String shortUri);

    void addToBloomFilter(String shortUri);

    boolean isNullCached(String shortUri);

    void cacheNullStatus(String shortUri, Duration ttl);
}
```

为什么先定义接口：

```text
RedirectServiceImpl 只依赖缓存能力
不直接依赖 Caffeine 和 Redis
方便替换实现和测试
```

---

## 10. CacheServiceImpl 完整代码

```java
package com.app.service.Impl;

import com.alibaba.fastjson2.JSON;
import com.app.common.bloom.BloomFilterHelper;
import com.app.common.cache.CacheKeys;
import com.app.entity.LinkEntity;
import com.app.service.CacheService;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class CacheServiceImpl implements CacheService {

    private static final Logger log =
            LoggerFactory.getLogger(CacheServiceImpl.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("shortLinkEntityCache")
    private Cache<String, String> localCache;

    @Autowired
    private BloomFilterHelper bloomFilterHelper;

    @Override
    public LinkEntity getLinkEntity(String shortUri) {
        String json = localCache.getIfPresent(shortUri);
        if (json != null) {
            return parse(json, shortUri);
        }

        json = redisTemplate.opsForValue()
                .get(CacheKeys.linkEntity(shortUri));
        if (json != null) {
            localCache.put(shortUri, json);
            return parse(json, shortUri);
        }

        return null;
    }

    @Override
    public void cacheLinkEntity(
            String shortUri,
            LinkEntity entity,
            Duration ttl) {
        String json = JSON.toJSONString(entity);
        localCache.put(shortUri, json);
        redisTemplate.opsForValue()
                .set(CacheKeys.linkEntity(shortUri), json, ttl);
    }

    @Override
    public boolean mightExist(String shortUri) {
        return bloomFilterHelper.mightContain(shortUri);
    }

    @Override
    public void addToBloomFilter(String shortUri) {
        bloomFilterHelper.add(shortUri);
    }

    @Override
    public boolean isNullCached(String shortUri) {
        return Boolean.TRUE.equals(
                redisTemplate.hasKey(
                        CacheKeys.nullMark(shortUri)));
    }

    @Override
    public void cacheNullStatus(
            String shortUri,
            Duration ttl) {
        redisTemplate.opsForValue()
                .set(CacheKeys.nullMark(shortUri), "1", ttl);
    }

    private LinkEntity parse(String json, String shortUri) {
        try {
            return JSON.parseObject(json, LinkEntity.class);
        } catch (Exception e) {
            log.warn("缓存反序列化失败: {}", shortUri, e);
            return null;
        }
    }
}
```

---

## 11. CacheServiceImpl 每一段怎么执行

### 11.1 注入本地缓存

```java
@Autowired
@Qualifier("shortLinkEntityCache")
private Cache<String, String> localCache;
```

`@Qualifier` 使用 Bean 名称。

`shortLinkEntityCache` 来自：

```java
@Bean
public Cache<String, String> shortLinkEntityCache() {
}
```

### 11.2 查询 L1

```java
String json = localCache.getIfPresent(shortUri);
```

执行：

```text
JVM 内存查找
```

不访问 Redis。

### 11.3 查询 L2

```java
json = redisTemplate.opsForValue()
        .get(CacheKeys.linkEntity(shortUri));
```

最终 Redis：

```text
GET shortlink:abc123
```

### 11.4 回填 L1

```java
localCache.put(shortUri, json);
```

为什么：

```text
下一次相同 shortUri 可以直接走 JVM
```

### 11.5 写 L1 和 L2

```java
String json = JSON.toJSONString(entity);
localCache.put(shortUri, json);
redisTemplate.opsForValue()
        .set(CacheKeys.linkEntity(shortUri), json, ttl);
```

最终 Redis：

```text
SET shortlink:abc123 "{...json...}" EX 1800
```

### 11.6 空值标记

```java
redisTemplate.hasKey(
        CacheKeys.nullMark(shortUri));
```

最终 Redis：

```text
EXISTS shortlink:null:abc123
```

写入：

```text
SET shortlink:null:abc123 1 EX 60
```

---

## 12. RedirectServiceImpl 完整查询代码

保留核心流程：

```java
@Service
public class RedirectServiceImpl implements RedirectService {

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private CacheService cacheService;

    private static final Duration CACHE_TTL =
            Duration.ofMinutes(30);

    private static final Duration NULL_CACHE_TTL =
            Duration.ofMinutes(1);

    private static final long CACHE_TTL_JITTER_SECONDS =
            300;

    @Override
    public LinkEntity getLinkEntity(String shortUri) {
        if (shortUri == null || shortUri.isBlank()) {
            return null;
        }

        LinkEntity cached =
                cacheService.getLinkEntity(shortUri);
        if (cached != null) {
            return cached;
        }

        if (cacheService.isNullCached(shortUri)) {
            return null;
        }

        if (!cacheService.mightExist(shortUri)) {
            return null;
        }

        LinkEntity entity = queryFromDb(shortUri);
        if (entity != null) {
            cacheService.cacheLinkEntity(
                    shortUri,
                    entity,
                    calcCacheTtl(entity));
            cacheService.addToBloomFilter(shortUri);
        } else {
            cacheService.cacheNullStatus(
                    shortUri, NULL_CACHE_TTL);
        }

        return entity;
    }

    private LinkEntity queryFromDb(String shortUri) {
        LambdaQueryWrapper<LinkEntity> wrapper =
                Wrappers.lambdaQuery();
        wrapper.eq(LinkEntity::getShortUri, shortUri);
        List<LinkEntity> results =
                linkMapper.selectList(wrapper);
        return results == null || results.isEmpty()
                ? null
                : results.get(0);
    }

    private Duration calcCacheTtl(LinkEntity entity) {
        long seconds = CACHE_TTL.getSeconds()
                + ThreadLocalRandom.current()
                        .nextLong(CACHE_TTL_JITTER_SECONDS);

        if (entity.getExpireTime() != null) {
            LocalDateTime now = LocalDateTime.now();
            if (entity.getExpireTime().isAfter(now)) {
                long remain = Duration.between(
                        now, entity.getExpireTime()).getSeconds();
                seconds = Math.min(
                        remain, CACHE_TTL.getSeconds());
            }
        }

        return Duration.ofSeconds(Math.max(seconds, 1));
    }
}
```

### 12.1 为什么先查 CacheService

```text
实体缓存命中后不需要数据库
也不需要 Bloom
```

### 12.2 为什么再查空值

```text
同一个不存在短码短时间内重复请求
直接返回
```

### 12.3 为什么最后才查 Bloom

```text
Bloom 也要访问 Redis
已经命中实体缓存时没必要访问
```

### 12.4 回源后为什么加 Bloom

如果短码在数据库中存在，但 Bitmap 缺失，就会永久返回 404。

### 12.5 数据库不存在为什么写空值

```text
防止相同无效短码连续打数据库
```

### 12.6 TTL 为什么加随机数

避免大量 Redis Key 同时过期：

```text
30 分钟 + 0~300 秒随机值
```

---

## 13. Controller 如何调用

```java
@Controller
public class RedirectController {

    @Autowired
    private RedirectService redirectService;

    @GetMapping("/{shortUri}")
    public String redirect(
            @PathVariable String shortUri,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) {

        LinkEntity entity =
                redirectService.getLinkEntity(shortUri);

        if (entity == null) {
            response.setStatus(404);
            return "error/404";
        }

        // 状态判断省略

        redirectService.recordAccess(entity, request);
        return "redirect:" + entity.getOriginUrl();
    }
}
```

调用链：

```text
GET /abc123
  -> RedirectController.redirect
  -> RedirectServiceImpl.getLinkEntity
  -> CacheServiceImpl
```

---

## 14. 管理端如何删除缓存

短链更新后：

```java
linkMapper.update(null, wrapper);
evictCache(exist.getShortUri());
```

删除方法完整代码：

```java
private void evictCache(String shortUri) {
    if (shortUri == null || shortUri.isBlank()) {
        return;
    }

    try {
        redisTemplate.delete(
                CacheKeys.linkEntity(shortUri));
    } catch (Exception e) {
        log.warn("删除短链缓存失败: {}", shortUri, e);
    }
}
```

底层：

```text
DEL shortlink:abc123
```

为什么只删 Redis：

```text
project 实例的 Caffeine 在各自 JVM 中
admin 无法直接删除
```

因此 L1 最长 60 秒后才失效。

---

## 15. 用 redis-cli 验证

### 15.1 第一次访问前

```bash
redis-cli DEL shortlink:abc123
```

### 15.2 第一次访问短链

访问：

```text
http://localhost:9000/s/abc123
```

观察日志：

```text
Caffeine miss
Redis miss
MySQL query
```

检查 Redis：

```bash
redis-cli GET shortlink:abc123
redis-cli TTL shortlink:abc123
```

### 15.3 第二次访问

如果仍在 60 秒内：

```text
Caffeine hit
```

可以观察 Redis `MONITOR`，但生产环境慎用：

```bash
redis-cli MONITOR
```

第二次请求应该主要由 Caffeine 命中。

### 15.4 删除 Redis

```bash
redis-cli DEL shortlink:abc123
```

如果还没有超过 60 秒：

```text
Caffeine 可能仍然命中
```

这就是本地缓存一致性延迟。

### 15.5 等待 60 秒后

```text
Caffeine miss
Redis miss
MySQL query
```

然后：

```bash
redis-cli GET shortlink:abc123
```

又会出现缓存。

---

## 16. 从零写到另一个项目

### 第一步

引入：

```xml
spring-boot-starter-data-redis
caffeine
fastjson2
redisson-spring-boot-starter（需要锁时）
```

### 第二步

配置：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### 第三步

创建：

```text
RedisConnectionFactory Bean
StringRedisTemplate Bean
```

如果只需要自动配置，可以不手写 RedisConfig。
但必须知道默认 Bean 是什么。

### 第四步

创建 Caffeine Bean：

```text
shortLinkEntityCache
```

### 第五步

创建缓存接口：

```text
get
put
delete
```

### 第六步

实现 Cache-Aside：

```text
查 L1
查 L2
查数据库
回填 L1/L2
更新数据库后删除 L2
```

### 第七步

用命令行验证：

```text
GET
TTL
DEL
```

不要在没有验证的情况下认为缓存链路已经正确。

---

## 17. 本篇必须掌握的代码能力

```text
[ ] 能写 @Configuration + @Bean
[ ] 能解释 @Value 默认值语法
[ ] 能创建 RedisConnectionFactory
[ ] 能创建 StringRedisTemplate
[ ] 能设置四个序列化器
[ ] 能创建 Caffeine Bean
[ ] 能用 @Qualifier 注入本地缓存
[ ] 能写 Cache-Aside 查询
[ ] 能写缓存回填
[ ] 能写空值缓存
[ ] 能写管理端缓存删除
[ ] 能用 redis-cli 验证
```
