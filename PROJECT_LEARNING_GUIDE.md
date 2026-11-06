# Shortlink 项目学习指南

这套文档按“从零写出这个功能”的顺序讲解项目，不讲面试套话。

每篇文档尽量包含：

```text
依赖
配置
Spring 装配
业务调用
底层命令或 SQL
同类方案
自检问题
```

文档分为两层：

```text
docs/learning   概念和原理解释
docs/code-labs  完整代码实现，重点阅读
```

## 先确认知识栈

你已经知道：

```text
Java 基础
Spring IoC / DI
Spring Boot 自动配置
常见 Spring 注解
MySQL CRUD
Redis、MQ、缓存和分片的基本概念
```

当前需要重点补齐：

```text
依赖如何变成可注入对象
配置类和业务代码如何连接
注解在启动时由谁处理
业务方法最终执行什么 Redis/MySQL 操作
如何自己从零搭出简化版
```

完整知识栈确认见：

[00 学习地图与知识栈确认](docs/learning/00-learning-map.md)

## 代码实现篇

需要照着写代码时，优先阅读：

1. [Spring Boot Bean 装配完整实现](docs/code-labs/00-spring-boot-bean-wiring.md)
2. [Redis 客户端与缓存完整实现](docs/code-labs/01-redis-client-and-cache.md)
3. [Redis Stream 完整实现](docs/code-labs/02-redis-stream.md)
4. [Redisson 分布式锁完整实现](docs/code-labs/03-redisson-lock.md)
5. [ShardingSphere 分库分表完整实现](docs/code-labs/04-mysql-sharding.md)
6. [Gateway、Nacos 与三层限流完整实现](docs/code-labs/05-gateway-and-rate-limiting.md)
7. [HyperLogLog、定时任务与统计聚合完整实现](docs/code-labs/06-hyperloglog-and-scheduled.md)
8. [JWT、拦截器与用户上下文完整实现](docs/code-labs/07-jwt-and-interceptor.md)
9. [短链生成与布隆过滤器完整实现](docs/code-labs/08-shortlink-and-bloom-filter.md)
10. [UA 与离线 GeoIP 完整实现](docs/code-labs/09-ua-and-offline-geoip.md)

代码实现篇不再标注源码位置，直接提供：

```text
完整 Maven 依赖
完整 YAML
完整配置类
完整业务类
方法执行顺序
底层 Redis 命令或 SQL
验证命令
从零实现步骤
```

## 学习目录

1. [Spring Boot 基础与 Bean 装配](docs/learning/01-spring-boot-core.md)
2. [Redis 依赖、配置和 Template](docs/learning/02-redis-configuration.md)
3. [缓存与 Caffeine](docs/learning/03-cache-and-caffeine.md)
4. [布隆过滤器](docs/learning/04-bloom-filter.md)
5. [Redis Stream](docs/learning/05-redis-stream.md)
6. [HyperLogLog、PV/UV 与统计聚合](docs/learning/06-hyperloglog-and-stats.md)
7. [Redisson 分布式锁](docs/learning/07-redisson-lock.md)
8. [MySQL 与 ShardingSphere 分片](docs/learning/08-mysql-sharding.md)
9. [短链生成与跳转](docs/learning/09-shortlink-and-redirect.md)
10. [Gateway、Nacos 与微服务](docs/learning/10-gateway-nacos.md)
11. [三层限流与 Sentinel](docs/learning/11-rate-limiting.md)
12. [定时任务](docs/learning/12-scheduled-tasks.md)
13. [JWT、拦截器与用户上下文](docs/learning/13-management-auth.md)
14. [调试、测试与练习路线](docs/learning/14-debugging-and-exercises.md)
15. [UA 解析与离线 GeoIP](docs/learning/15-ua-and-geoip.md)

## 推荐阅读顺序

```text
代码实现篇 00 Spring Boot Bean
  -> 代码实现篇 01 Redis 与缓存
  -> 代码实现篇 02 Stream
  -> 代码实现篇 03 Redisson
  -> 代码实现篇 04 分库分表
  -> 代码实现篇 05 Gateway 与限流
  -> 代码实现篇 06 HLL 与定时任务
  -> 代码实现篇 07 JWT
  -> 代码实现篇 08 短链与 Bloom
  -> 代码实现篇 09 UA 与 GeoIP
```

## 项目请求总览

```text
浏览器
  -> Gateway :9000

  /api/** -> admin :8081
              -> JWT
              -> 用户限流
              -> Sentinel
              -> 业务 Service
              -> MySQL

  /s/**   -> project :8083
              -> Caffeine
              -> Redis
              -> Bloom
              -> MySQL
              -> Redis 计数
              -> Redis Stream

Redis Stream -> admin Consumer -> t_access_log
定时任务     -> Redis PV/HLL -> t_access_stats
```

## 学习要求

不要只阅读代码。

每篇至少完成：

```text
1. 在 IDE 中找到对应类
2. 在配置文件中找到对应配置
3. 用断点或日志确认调用顺序
4. 用 redis-cli 或 SQL 日志确认底层操作
5. 不看原文，自己写一个最小示例
```

后续对话会以这些文档为底稿逐篇补充、修正和增加练习。
