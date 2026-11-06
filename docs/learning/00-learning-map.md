# 00 学习地图与知识栈确认

## 1. 这份资料的目标

这套文档不讲“八股答案”，而是讲一个功能在 Spring Boot 项目里如何从零写出来。

每一章尽量回答同样几个问题：

```text
这个技术解决什么问题？
需要引入什么依赖？
配置写在哪里？
Spring 在启动时如何装配？
业务代码通过什么对象调用？
底层最终执行什么命令或 SQL？
这个项目具体在哪个类里使用？
同类方案有哪些，为什么选当前方案？
自己能做哪些练习验证理解？
```

## 2. 对你当前知识栈的确认

根据你之前提供的信息，可以把起点整理为：

### 已经知道的概念

- Java 基础语法、类、接口、实现、注解
- Spring IoC 和依赖注入
- Spring Boot 自动配置的基本概念
- Spring 常见注解
- MySQL 基本 CRUD
- Redis、MQ、缓存穿透/雪崩/击穿等概念
- 数据库分片的基本概念
- HTTP 和网络协议基础
- Caffeine、Redis、ShardingSphere、Redisson 等名词

### 当前更薄弱的环节

不是“不知道 Redis 是什么”，而是：

```text
依赖为什么要这样引？
配置类为什么能被 Spring 创建？
@Bean 方法为什么不需要继承接口？
@Value 的值从哪里来？
对象什么时候被注入到 Service？
RedisTemplate 的每个方法对应什么 Redis 命令？
缓存命中后为什么还要回填本地缓存？
配置文件、配置类、业务代码之间的调用顺序是什么？
```

### 暂时不需要掌握的深层内容

下面这些可以先知道名字，不必在第一次阅读时全部啃透：

- Lettuce 和 Netty 的连接复用细节
- Redis 单线程事件循环源码
- ShardingSphere SQL 改写器源码
- Redisson Lua 脚本完整实现
- Spring Framework 容器启动全部源码

学习顺序应该是：

```text
先会写业务代码
  -> 再知道底层执行什么命令
  -> 最后再选择读源码
```

## 3. 这个项目需要的知识栈

### 3.1 必会

| 知识 | 在本项目中的位置 |
|---|---|
| Maven 多模块 | 根 `pom.xml` 和四个子模块 |
| Spring IoC / DI | `@Service`、`@Component`、`@Autowired` |
| `@Configuration` / `@Bean` | `RedisConfig`、`CaffeineConfig`、`ShardingSphereConfig` |
| `@Value` | Redis、JWT、Bloom Filter 参数 |
| Spring MVC | Controller、`@RequestMapping`、`@PathVariable` |
| MyBatis-Plus | `BaseMapper`、`@TableName` |
| RedisTemplate | 缓存、计数器、Bitmap、HLL、Stream |
| Caffeine | 跳转服务的本地缓存 |
| Redisson | 创建短链和定时任务锁 |

### 3.2 需要理解但可以边做边学

| 知识 | 在本项目中的位置 |
|---|---|
| Spring Boot 自动配置条件 | Redis 默认 Bean 为什么会退让 |
| 序列化 | Redis Key/Value 为什么能直接看到字符串 |
| Cache-Aside | `CacheServiceImpl` 和 `RedirectServiceImpl` |
| 消息消费组和 ACK | Redis Stream 访问日志 |
| Pending 重试 | 访问事件写库失败 |
| 至少一次消费和幂等 | `(link_gid, message_id)` 唯一索引 |
| 分片键与 SQL 广播 | `link_gid`、`short_uri` |
| 令牌桶/固定窗口 | Gateway、用户限流 |
| Sentinel AOP | `@SentinelResource` |

### 3.3 可以先知道结论的内容

| 知识 | 先记住的结论 |
|---|---|
| Lettuce 连接复用 | `RedisConnectionFactory` 管理连接，`RedisTemplate` 是操作入口 |
| Redisson watchdog | 不指定 leaseTime 时会自动续期 |
| Redis Stream Pending | 未 ACK 的消息可被重新领取 |
| HyperLogLog | 固定小内存估算基数，不是精确集合 |
| ShardingSphere | 业务写逻辑表，框架改写 SQL 并路由 |

## 4. 模块和请求链路

```text
shortlink/
├── common/   共享 Redis Key、布隆过滤器
├── admin/    管理端、Stream 消费、统计、定时任务
├── project/  跳转端、缓存、访问计数、Stream 生产
└── gateway/  路由、CORS、IP 限流
```

```text
浏览器
  -> gateway:9000
       /api/** -> admin:8081 -> MySQL
       /s/**   -> project:8083 -> Caffeine/Redis -> MySQL

project -> Redis Stream -> admin consumer -> t_access_log
admin @Scheduled -> Redis PV/HLL -> t_access_stats
```

## 5. 文档章节

建议按顺序阅读：

概念部分：

1. [Spring Boot 基础与 Bean 装配](01-spring-boot-core.md)
2. [Redis 依赖、配置和 Template](02-redis-configuration.md)
3. [缓存与 Caffeine](03-cache-and-caffeine.md)
4. [布隆过滤器](04-bloom-filter.md)
5. [Redis Stream](05-redis-stream.md)
6. [HyperLogLog、PV/UV 与统计聚合](06-hyperloglog-and-stats.md)
7. [Redisson 分布式锁](07-redisson-lock.md)
8. [MySQL 与 ShardingSphere 分片](08-mysql-sharding.md)
9. [短链生成与跳转](09-shortlink-and-redirect.md)
10. [Gateway、Nacos 与微服务](10-gateway-nacos.md)
11. [三层限流与 Sentinel](11-rate-limiting.md)
12. [定时任务](12-scheduled-tasks.md)
13. [JWT、拦截器与用户上下文](13-management-auth.md)
14. [调试、测试与练习路线](14-debugging-and-exercises.md)
15. [UA 解析与离线 GeoIP](15-ua-and-geoip.md)

完整代码实现部分：

1. [Spring Boot Bean 装配](../code-labs/00-spring-boot-bean-wiring.md)
2. [Redis 客户端与缓存](../code-labs/01-redis-client-and-cache.md)
3. [Redis Stream](../code-labs/02-redis-stream.md)
4. [Redisson 分布式锁](../code-labs/03-redisson-lock.md)
5. [ShardingSphere 分库分表](../code-labs/04-mysql-sharding.md)
6. [Gateway、Nacos 与三层限流](../code-labs/05-gateway-and-rate-limiting.md)
7. [HyperLogLog、定时任务与统计聚合](../code-labs/06-hyperloglog-and-scheduled.md)
8. [JWT、拦截器与用户上下文](../code-labs/07-jwt-and-interceptor.md)
9. [短链生成与布隆过滤器](../code-labs/08-shortlink-and-bloom-filter.md)
10. [UA 与离线 GeoIP](../code-labs/09-ua-and-offline-geoip.md)

## 6. 每一章的学习方法

不要只看代码。
每读完一章，至少做三件事：

```text
1. 在 IDE 中找到对应类和配置文件
2. 用调试器或日志确认方法调用顺序
3. 用 redis-cli/mysql/sql-show 观察实际命令
```

例如学习 Redis 缓存时，观察：

```text
第一次请求：Redis GET 未命中
第二次请求：Caffeine 命中，没有 Redis 命令
删除缓存后：下一次 Redis GET 再次未命中
```

把“代码分支”和“外部命令”对应起来，才会真正建立实现能力。
