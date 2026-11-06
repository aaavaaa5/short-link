# 03 缓存与 Caffeine

## 1. 本章解决什么问题

理解跳转服务为什么需要两级缓存，以及代码如何从：

```text
Controller
  -> RedirectService
  -> CacheService
  -> Caffeine/Redis
  -> MySQL
```

## 2. 依赖

Caffeine：

```xml
<dependency>
    <groupId>com.github.ben-manes.caffeine</groupId>
    <artifactId>caffeine</artifactId>
</dependency>
```

Redis：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

项目自己的共享模块：

```xml
<dependency>
    <groupId>com.app</groupId>
    <artifactId>common</artifactId>
    <version>${project.version}</version>
</dependency>
```

## 3. Caffeine 配置类

```java
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

参数：

```text
Key：shortUri
Value：LinkEntity JSON 字符串
maximumSize：最多 1 万个 Key
expireAfterWrite：写入 60 秒后过期
recordStats：记录命中率等统计
```

注意：Caffeine 是 JVM 进程内缓存，不是 Redis。

多实例时：

```text
project-1 有一份本地缓存
project-2 也有自己的一份
两边不会自动同步
```

## 4. 为什么 Value 是 String

如果存 Java 对象：

```java
Cache<String, LinkEntity>
```

也能工作。

但项目选择 `String`，并在其中存 JSON：

```java
String json = JSON.toJSONString(entity);
```

原因：

```text
Redis 本来就存字符串
L1/L2 使用同一份 JSON，反序列化逻辑一致
避免本地缓存是对象、Redis 是字节，两套处理
```

解析：

```java
LinkEntity entity =
        JSON.parseObject(json, LinkEntity.class);
```

## 5. CacheService 的职责

接口把缓存能力抽出来：

```java
public interface CacheService {
    LinkEntity getLinkEntity(String shortUri);
    void cacheLinkEntity(String shortUri, LinkEntity entity, Duration ttl);
    boolean isNullCached(String shortUri);
    void cacheNullStatus(String shortUri, Duration ttl);
    boolean mightExist(String shortUri);
    void addToBloomFilter(String shortUri);
}
```

业务层只依赖接口：

```java
@Autowired
private CacheService cacheService;
```

好处：

```text
业务层不知道 Caffeine/Redis 细节
可以单独测试缓存实现
以后替换缓存方案只改实现类
```

## 6. CacheServiceImpl 的注入

```java
@Autowired
private StringRedisTemplate redisTemplate;

@Autowired
@Qualifier("shortLinkEntityCache")
private Cache<String, String> localCache;

@Autowired
private BloomFilterHelper bloomFilterHelper;
```

`@Qualifier` 的原因：

```text
类型 Cache<String, String> 不一定只有一个 Bean
必须指定名字 shortLinkEntityCache
```

## 7. 查 L1

```java
String json = localCache.getIfPresent(shortUri);
if (json != null) {
    return parse(json, shortUri);
}
```

底层行为：

```text
直接访问 JVM 内的 ConcurrentHashMap 类似结构
没有网络 IO
没有 Redis 命令
```

命中时速度最快。

## 8. 查 L2

```java
json = redisTemplate.opsForValue()
        .get(CacheKeys.linkEntity(shortUri));
```

Key：

```java
"shortlink:" + shortUri
```

例如：

```text
shortlink:abc123
```

底层命令：

```text
GET shortlink:abc123
```

Redis 命中后回填 L1：

```java
localCache.put(shortUri, json);
```

原因：

```text
下一次同一个短码可以完全不走 Redis
```

## 9. 写缓存

```java
public void cacheLinkEntity(
        String shortUri,
        LinkEntity entity,
        Duration ttl) {
    String json = JSON.toJSONString(entity);
    localCache.put(shortUri, json);
    redisTemplate.opsForValue()
            .set(CacheKeys.linkEntity(shortUri), json, ttl);
}
```

流程：

```text
实体对象
  -> JSON
  -> 写本地缓存
  -> SET Redis Key JSON EX ttl
```

## 10. RedirectServiceImpl 的业务顺序

```java
LinkEntity cached = cacheService.getLinkEntity(shortUri);
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
```

完整顺序：

```text
L1
  -> L2
  -> 空值缓存
  -> 布隆过滤器
  -> MySQL
```

为什么 L1/L2 在 Bloom 前面：

```text
实体缓存命中后可以直接返回
不必再做一次 Redis Bitmap 查询
```

为什么空值标记在 Bloom 前面：

```text
空值标记也是 Redis
但它是单个 EXISTS
先判断可以快速返回已知不存在的数据
```

## 11. 回源数据库

```java
List<LinkEntity> results =
        linkMapper.selectList(wrapper);
```

因为 `short_uri` 不是分片键，SQL 会广播到多个分片。

这也是缓存重要的原因：

```text
高频访问时尽量不进入数据库广播查询
```

## 12. 回填和 TTL

```java
if (entity != null) {
    cacheService.cacheLinkEntity(
            shortUri, entity, calcCacheTtl(entity));
    cacheService.addToBloomFilter(shortUri);
} else {
    cacheService.cacheNullStatus(
            shortUri, Duration.ofMinutes(1));
}
```

TTL：

```text
普通链接：30 分钟 + 0~300 秒随机扰动
有明确过期时间：不能超过链接剩余时间
不存在：空值标记 1 分钟
```

随机扰动避免大量 Key 同时过期。

## 13. Cache-Aside 模式

本项目使用 Cache-Aside。

读：

```text
读缓存
未命中读数据库
写回缓存
```

写：

```text
更新数据库
删除缓存
```

删除代码：

```java
linkMapper.update(null, wrapper);
redisTemplate.delete(CacheKeys.linkEntity(shortUri));
```

为什么不是更新缓存？

```text
并发写时更新顺序难控制
缓存值可能需要复杂计算
删除后下次读取重建更简单
```

## 14. L1 一致性

管理端只能删除 Redis：

```java
redisTemplate.delete(...);
```

它不能直接删除 project JVM 中的 Caffeine。

因此：

```text
L2 立即失效
L1 最多 60 秒后失效
```

可选改进：

```text
Redis Pub/Sub 广播失效
MQ 广播失效
版本号比对
缩短 L1 TTL
```

## 15. 缓存问题

### 穿透

查询一定不存在的数据。

当前方案：

```text
布隆过滤器
空值缓存
```

### 雪崩

大量 Key 同时过期。

当前方案：

```text
TTL 随机扰动
```

### 击穿

热点 Key 过期时大量并发回源。

当前方案：

```text
未专门实现
```

改进思路：

```text
Key 级别互斥锁
逻辑过期
后台刷新
```

## 16. 和 @Cacheable 对比

简单场景：

```java
@Cacheable(value = "link", key = "#shortUri")
public LinkEntity getLink(String shortUri) {
    return queryFromDb(shortUri);
}
```

优点：

```text
代码少
删除缓存用 @CacheEvict
```

局限：

```text
复杂空值策略要配置
多级缓存不够直观
布隆过滤器和动态 TTL 不好表达
```

本项目手写控制，便于理解每个步骤。

## 17. 本章自检

1. Caffeine 和 Redis 分别在哪一层？
2. 为什么实体缓存命中后不查 Bloom？
3. Redis 命中后为什么还要写 Caffeine？
4. 空值缓存解决什么问题？
5. 为什么空值不写 L1？
6. TTL 随机扰动解决什么问题？
7. L1 为什么不能立即被 admin 删除？
8. Cache-Aside 为什么通常删除缓存而不是更新缓存？
