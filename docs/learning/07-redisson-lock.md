# 07 Redisson 分布式锁

## 1. 本章解决什么问题

理解什么情况需要分布式锁，以及项目如何用 Redisson 保护：

```text
短码查重和插入
统计聚合任务
零点重置任务
```

## 2. 单机锁为什么不够

```java
synchronized (this) {
}
```

只能锁住当前 JVM。

两个 admin 实例：

```text
admin-1 的 synchronized
admin-2 的 synchronized
```

彼此不可见。

分布式锁把互斥状态放到共享存储：

```text
Redis
ZooKeeper
etcd
数据库
```

## 3. 依赖

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson-spring-boot-starter</artifactId>
</dependency>
```

配置类：

```java
@Bean(destroyMethod = "shutdown")
public RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer()
            .setAddress("redis://localhost:6379")
            .setDatabase(0);
    return Redisson.create(config);
}
```

## 4. 最短使用模板

```java
RLock lock = redissonClient.getLock("lock:key");

try {
    if (lock.tryLock(1, 5, TimeUnit.SECONDS)) {
        try {
            // 临界区
        } finally {
            lock.unlock();
        }
    }
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
}
```

三个动作：

```text
getLock      获取锁对象
tryLock      尝试加锁
unlock       释放锁
```

## 5. tryLock 参数

```java
tryLock(waitTime, leaseTime, unit)
```

含义：

```text
waitTime：最多等待多久获取锁
leaseTime：拿到锁后租期多久
```

示例：

```java
tryLock(1, 5, TimeUnit.SECONDS)
```

```text
最多等 1 秒
拿到锁后 5 秒自动过期
```

返回：

```text
true  -> 获取成功
false -> 超时未获取
```

## 6. 租期和 watchdog

需要区分两个重载：

```java
lock.tryLock(1, TimeUnit.SECONDS);
lock.tryLock(1, 5, TimeUnit.SECONDS);
```

第一个不指定 `leaseTime`：

```text
Redisson 使用 watchdog
业务未结束时自动续期
```

第二个指定固定租期：

```text
5 秒到期后释放
不会依赖 watchdog 自动续期
```

当前创建短链使用：

```java
tryLock(1, 5, TimeUnit.SECONDS)
```

数据库操作通常很短，5 秒足够。

如果业务可能执行很久，应使用 watchdog，或主动续期。

## 7. 为什么 finally 中 unlock

```java
try {
    // 业务
} finally {
    lock.unlock();
}
```

如果不放在 finally：

```text
业务抛异常
锁一直保留到租期结束
其他请求被阻塞
```

更严谨：

```java
if (lock.isHeldByCurrentThread()) {
    lock.unlock();
}
```

避免在锁已经过期、已被别人获取时误释放。

## 8. 创建短链为什么加锁

错误流程：

```text
请求 A 查 candidate 不存在
请求 B 查 candidate 不存在
请求 A insert
请求 B insert
```

可能产生重复短码。

正确流程：

```text
获取候选短码锁
  -> 查重
  -> 写 Bloom
  -> insert
  -> unlock
```

锁 Key：

```text
shortlink:lock:create:{candidate}
```

锁粒度是单个候选短码。

好处：

```text
不同候选短码互不阻塞
同一候选短码串行处理
```

## 9. 重试

生成短码可能碰撞：

```text
生成候选
加锁
发现存在
释放
重新生成
最多重试 3 次
```

## 10. 统计任务锁

```java
RLock lock = redissonClient.getLock(
        "shortlink:lock:stats-aggregation");
if (lock.tryLock(0, 1800, TimeUnit.SECONDS)) {
    doAggregate();
}
```

`waitTime=0`：

```text
拿不到立即放弃
```

定时任务不需要排队等待。
另一个实例已经在执行时，本实例直接跳过。

## 11. 零点重置锁

```java
tryLock(0, 30, TimeUnit.SECONDS)
```

同样防止多实例重复执行。

## 12. Redisson 锁的内部思路

简化过程：

```text
1. Redis 中存在锁 Key 吗？
2. 不存在，则写入锁并设置过期时间
3. 存在且是当前线程，则重入次数 + 1
4. 释放时重入次数 - 1
5. 次数为 0 才真正删除
```

这些判断和修改通过 Lua 脚本原子执行。

为什么需要原子：

```text
“判断锁是否属于自己”和“删除锁”
必须一次性完成
否则中间可能被其他线程插入
```

## 13. 手动 SETNX 的问题

最简单方案：

```text
SET lock owner NX PX 30000
```

问题：

```text
业务超时后锁自动释放
业务还没结束，别人可能拿到锁
释放时必须判断 owner
判断和删除需要 Lua 保证原子
不可重入
续期需要自己实现
```

Redisson 封装了这些细节。

## 14. 同类方案

| 方案 | 特点 |
|---|---|
| `SET NX` + Lua | 简单，需要自己处理续期和重入 |
| Redisson | 完整，支持重入、watchdog、多种部署 |
| 数据库行锁 | 依赖数据库事务，锁范围受连接影响 |
| ZooKeeper | 临时节点，强一致，组件更重 |
| etcd lease | 云原生常见 |

## 15. 本章自检

1. `synchronized` 为什么不能保护多实例？
2. `tryLock` 的 waitTime 和 leaseTime 分别是什么？
3. 指定 leaseTime 后 watchdog 是否自动续期？
4. 为什么 unlock 要放 finally？
5. 为什么创建短链锁 Key 要包含候选短码？
6. 定时任务为什么常用 `waitTime=0`？
7. Redisson 为什么用 Lua 脚本？
