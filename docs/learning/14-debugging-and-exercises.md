# 14 调试、测试与练习路线

## 1. 本章目标

把“看懂代码”变成“能运行、能验证、能修改”。

推荐顺序：

```text
先跑单测
再启动基础中间件
再启动三个服务
最后逐个模块做小实验
```

## 2. 环境检查

项目需要：

```text
JDK 17
Maven
MySQL 8
Redis
Nacos 2.x
```

检查命令：

```bash
java -version
mvn -version
redis-cli ping
mysql --version
```

## 3. 启动顺序

```text
1. MySQL
2. Redis
3. Nacos
4. admin
5. project
6. gateway
```

为什么 admin 先于 project：

```text
admin 启动时执行布隆过滤器历史数据回填
project 读路径依赖位图完整
```

端口：

```text
Nacos   8848
admin   8081
project 8083
gateway 9000
```

## 4. 数据库初始化

脚本：

```text
resources/database/link.sql
```

迁移脚本：

```text
resources/database/migration/
```

执行后检查：

```sql
SHOW DATABASES LIKE 'shortlink_ds%';
SHOW TABLES FROM shortlink_ds0;
```

## 5. Redis 观察命令

测试连接：

```bash
redis-cli ping
```

短链实体缓存：

```bash
redis-cli GET shortlink:abc123
```

TTL：

```bash
redis-cli TTL shortlink:abc123
```

空值缓存：

```bash
redis-cli EXISTS shortlink:null:abc123
```

布隆过滤器：

```bash
redis-cli GETBIT shortlink:bf:short_uri 123456
redis-cli SETBIT shortlink:bf:short_uri 123456 1
```

PV：

```bash
redis-cli GET shortlink:click:1:2026-10-26:pv
```

UV：

```bash
redis-cli PFCOUNT shortlink:click:1:2026-10-26:uv
```

Stream：

```bash
redis-cli XLEN shortlink:access:stream
redis-cli XINFO GROUPS shortlink:access:stream
redis-cli XPENDING shortlink:access:stream admin-consumer-group
```

分布式锁：

```bash
redis-cli KEYS 'shortlink:lock:*'
```

## 6. 查看 SQL 分片

配置：

```yaml
spring:
  shardingsphere:
    props:
      sql-show: true
```

日志中观察：

```text
Logic SQL
Actual SQL
Data source
Actual table
```

如果没有看到预期真实表，重点检查：

```text
SQL 是否带 gid
参数是否被识别为分片键
逻辑表名是否正确
```

## 7. 测试代码

运行全部测试：

```bash
mvn -q test
```

当前项目测试重点：

```text
Stream 消费失败不 ACK
重复消息视为成功
Pending 超过阈值才 claim
消费组只创建一次
非法 access_time 不丢消息
UA/GeoIP 重复值使用本地缓存
```

这些测试体现了消息消费的关键边界。

## 8. 用日志验证调用链

建议临时增加日志：

```java
log.info("cache hit, shortUri={}", shortUri);
log.info("cache miss, shortUri={}", shortUri);
log.info("db query, shortUri={}", shortUri);
```

然后观察：

```text
第一次请求：L1 miss -> L2 miss -> DB
第二次请求：L1 hit
删除 Redis：L1 可能仍 hit
等待 60 秒：L1 miss -> L2 miss -> DB
```

## 9. 用断点

在 IDEA 中设置断点：

```text
RedirectController.redirect
RedirectServiceImpl.getLinkEntity
CacheServiceImpl.getLinkEntity
BloomFilterHelper.mightContain
RedirectServiceImpl.queryFromDb
```

观察：

```text
参数
局部变量
返回结果
调用栈
```

## 10. 从零重写练习

### 练习 1：Redis GET/SET

自己写一个 `DemoService`：

```java
public void set(String key, String value) {
    redisTemplate.opsForValue().set(key, value);
}

public String get(String key) {
    return redisTemplate.opsForValue().get(key);
}
```

### 练习 2：加 TTL

设置 30 秒过期，用 `redis-cli TTL` 观察。

### 练习 3：手写 Cache-Aside

```text
查缓存
未命中查数据库
写缓存
更新数据库后删除缓存
```

### 练习 4：加 Caffeine

在 Redis 前加本地缓存，观察 60 秒一致性问题。

### 练习 5：加布隆过滤器

创建短码时写位图，查询时先判断位图。

### 练习 6：写 Stream

先用 redis-cli 手敲：

```text
XADD
XGROUP CREATE
XREADGROUP
XACK
```

再写 Java 生产者/消费者。

### 练习 7：写分布式锁

先用 `SET NX PX`，再换成 Redisson。

### 练习 8：写定时任务

使用：

```java
@Scheduled(fixedDelay = 5000)
```

确认启动类有 `@EnableScheduling`。

## 11. 常见故障

### Nacos 连不上

检查：

```text
Nacos 是否启动
端口 8848
namespace 是否存在
application.yml 的 server-addr
```

### Gateway 404

检查：

```text
Path predicate
StripPrefix
目标服务是否注册成功
Controller 实际映射
```

### Redis 能连上但 Key 乱码

检查 serializer 配置。

### 分片 SQL 没有路由

检查：

```text
SQL 和实体字段是否带分片键
逻辑表名
分片算法配置
sql-show 日志
```

### Stream 消息堆积

检查：

```text
消费者是否启动
消费组是否存在
Pending 是否持续增长
数据库是否写入失败
Consumer 处理速度
```

### 定时任务不执行

检查：

```text
@EnableScheduling
方法是否 public
Bean 是否被 Spring 管理
cron 表达式
线程池是否被长任务占满
```

## 12. 后续深入学习路线

### 第一轮

能说明每段代码做什么，不要求看框架源码。

### 第二轮

能自己写出简化版：

```text
Redis 缓存
Caffeine
Bloom
Stream
Redisson 锁
一个定时任务
```

### 第三轮

能解释底层：

```text
Redis 命令
Spring Bean 生命周期
ShardingSphere 路由
Stream Pending
锁续期
```

### 第四轮

做改进：

```text
缓存击穿互斥重建
L1 广播失效
全局唯一 ID
带分片键的查询优化
完整角色权限
集成测试
```

## 13. 总检查清单

```text
[ ] 我能解释 @Bean 和自动配置的关系
[ ] 我能从 RedisConfig 追到 StringRedisTemplate
[ ] 我能解释 Cache-Aside 的读写流程
[ ] 我能解释 Caffeine 和 Redis 的职责
[ ] 我能解释 Bloom 的假阳性和假阴性
[ ] 我能手写最小 Redis Stream 消费流程
[ ] 我能解释 Pending 和 ACK
[ ] 我能解释 HLL 的误差
[ ] 我能解释 Redisson tryLock 两个时间参数
[ ] 我能解释 gid 如何指导分片
[ ] 我能解释 short_uri 查询为什么广播
[ ] 我能解释 Gateway 路由和 Nacos 服务发现
[ ] 我能解释三层限流
[ ] 我能解释 @Scheduled 为什么需要分布式锁
[ ] 我能解释 JWT 拦截器和 ThreadLocal 清理
```
