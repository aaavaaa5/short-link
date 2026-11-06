# Redisson 分布式锁完整实现

## 0. 实现目标

在一个多实例项目中实现：

```text
同一个短码只允许一个请求检查和插入
多个定时任务实例只有一个真正执行
业务异常时锁能释放
锁不会无限期卡死
```

本篇不从面试概念出发，而是直接写代码。

---

## 1. Maven 依赖

```xml
<dependency>
    <groupId>org.redisson</groupId>
    <artifactId>
        redisson-spring-boot-starter
    </artifactId>
</dependency>
```

这个 starter 提供：

```text
RedissonClient
Redisson 自动配置
RLock
```

如果使用 Spring Data Redis 的 `StringRedisTemplate`，还需要：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>
        spring-boot-starter-data-redis
    </artifactId>
</dependency>
```

---

## 2. 用自动配置创建 RedissonClient

最少配置：

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

Redisson starter 会自动创建 `RedissonClient`。

但不同 starter 版本支持的配置前缀可能不同。
如果自动配置不生效，就手写：

```java
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionPoolSize(8)
                .setConnectionMinimumIdleSize(2);

        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }

        return Redisson.create(config);
    }
}
```

---

## 3. 每个配置项什么意思

```java
config.useSingleServer()
```

单机 Redis。

```java
.setAddress("redis://localhost:6379")
```

连接地址。

```java
.setDatabase(0)
```

Redis 逻辑库。

```java
.setConnectionPoolSize(8)
```

Redisson 普通命令连接池大小。

```java
.setConnectionMinimumIdleSize(2)
```

保持在池中的最小空闲连接数。

```java
@Bean(destroyMethod = "shutdown")
```

Spring 容器关闭时调用：

```java
redissonClient.shutdown();
```

---

## 4. 最短可运行锁代码

```java
@Service
public class DemoLockService {

    private final RedissonClient redissonClient;

    public DemoLockService(
            RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    public void doWork() {
        RLock lock = redissonClient.getLock("demo:lock");

        try {
            boolean locked = lock.tryLock(
                    1, 5, TimeUnit.SECONDS);

            if (!locked) {
                return;
            }

            try {
                // 这里写需要互斥的业务
                System.out.println("拿到锁");
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

执行过程：

```text
getLock               创建锁对象，不访问 Redis
tryLock               尝试加锁
isHeldByCurrentThread 判断锁是否仍属于当前线程
unlock                释放锁
```

---

## 5. tryLock 参数

```java
tryLock(1, 5, TimeUnit.SECONDS)
```

第一个参数：

```text
最多等待 1 秒
```

第二个参数：

```text
拿到锁后保留 5 秒
```

返回：

```text
true  获取成功
false 等待超时
```

如果不想固定租期：

```java
lock.tryLock(1, TimeUnit.SECONDS);
```

这种写法依赖 Redisson watchdog 自动续期。

---

## 6. 创建短链的完整锁逻辑

目标：

```text
同一个候选短码只能有一个请求完成查重和插入
```

完整代码：

```java
@Service
public class LinkCreateService {

    private static final int MAX_RETRY = 3;

    private final LinkMapper linkMapper;
    private final ShortLinkGenerator generator;
    private final RedissonClient redissonClient;

    public LinkCreateService(
            LinkMapper linkMapper,
            ShortLinkGenerator generator,
            RedissonClient redissonClient) {
        this.linkMapper = linkMapper;
        this.generator = generator;
        this.redissonClient = redissonClient;
    }

    public LinkEntity create(
            String originUrl,
            Long userId) throws InterruptedException {

        LinkEntity entity = new LinkEntity();
        entity.setOriginUrl(originUrl);
        entity.setGid("u_" + userId);
        entity.setCreateUserId(userId);
        entity.setEnableStatus(1);

        for (int i = 0; i < MAX_RETRY; i++) {
            String candidate = generator.generate(originUrl);
            String lockKey =
                    "shortlink:lock:create:" + candidate;

            RLock lock = redissonClient.getLock(lockKey);
            boolean locked = lock.tryLock(
                    1, 5, TimeUnit.SECONDS);

            if (!locked) {
                continue;
            }

            try {
                if (exists(candidate)) {
                    continue;
                }

                entity.setShortUri(candidate);
                linkMapper.insert(entity);
                return entity;
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        throw new IllegalStateException("短码生成失败");
    }

    private boolean exists(String shortUri) {
        LambdaQueryWrapper<LinkEntity> wrapper =
                Wrappers.lambdaQuery();
        wrapper.eq(
                LinkEntity::getShortUri, shortUri);
        return linkMapper.selectCount(wrapper) > 0;
    }
}
```

---

## 7. 为什么锁要把查重和插入都包住

错误代码：

```java
if (exists(candidate)) {
    return;
}

RLock lock = getLock(candidate);
lock.lock();
insert(candidate);
lock.unlock();
```

问题：

```text
线程 A 查重通过
线程 B 查重通过
线程 A 加锁插入
线程 B 等 A 释放后加锁插入
```

第二个线程没有重新检查数据库。

正确临界区：

```java
加锁
  -> 查重
  -> 插入
解锁
```

---

## 8. 定时任务锁

### 8.1 统计聚合

```java
@Scheduled(cron = "0 0 1 * * ?")
public void aggregate() {
    RLock lock = redissonClient.getLock(
            "shortlink:lock:stats-aggregation");

    try {
        boolean locked = lock.tryLock(
                0, 1800, TimeUnit.SECONDS);

        if (!locked) {
            return;
        }

        try {
            doAggregate();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

参数：

```text
waitTime = 0
不等待
```

定时任务场景下，拿不到锁直接跳过即可。

### 8.2 零点清零

```java
@Scheduled(cron = "0 0 0 * * ?")
public void reset() {
    RLock lock = redissonClient.getLock(
            "shortlink:lock:midnight-reset");

    try {
        if (lock.tryLock(0, 30, TimeUnit.SECONDS)) {
            try {
                linkMapper.update(
                        null,
                        Wrappers.lambdaUpdate()
                                .set(LinkEntity::getTodayPv, 0)
                                .set(LinkEntity::getTodayUv, 0)
                                .set(LinkEntity::getTodayIpCount, 0));
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
}
```

---

## 9. Redisson 锁内部大致做了什么

第一次加锁，概念上执行：

```text
判断锁 Key 是否存在
不存在：
  写入锁持有者标识
  设置过期时间
  重入次数设为 1
存在且属于自己：
  重入次数 + 1
存在且属于别人：
  等待或返回 false
```

释放时：

```text
判断是否属于自己
重入次数 - 1
为 0 时删除锁
```

这些操作必须原子执行，所以 Redisson 使用 Lua 脚本。

---

## 10. 为什么不能简单地 SETNX 后 DEL

错误示例：

```text
SET lock owner NX PX 5000

业务执行超过 5 秒
  -> 锁过期
  -> 别人拿到锁

原线程 finally:
  -> DEL lock
  -> 误删别人的锁
```

正确释放需要：

```text
只有 value 等于自己的 owner 才删除
```

并且判断和删除要在一个 Lua 脚本里原子完成。

---

## 11. watchdog 什么时候生效

指定 leaseTime：

```java
tryLock(1, 5, TimeUnit.SECONDS)
```

```text
5 秒固定租期
不会自动续期
```

不指定 leaseTime：

```java
tryLock(1, TimeUnit.SECONDS)
```

```text
Redisson watchdog 自动续期
```

选择：

```text
业务很短：明确 leaseTime
业务耗时不可预测：watchdog
```

---

## 12. 常见错误

### 错误 1：unlock 不放在 finally

```java
lock.lock();
doSomething();
lock.unlock();
```

业务异常时不会执行 unlock。

### 错误 2：使用不同的锁 Key

```text
创建时锁 shortlink:lock:create:abc
定时任务锁 shortlink:lock:create
```

它们不是同一把锁。

### 错误 3：锁粒度太大

```text
lock:create
```

所有用户创建短链都串行。

应该：

```text
lock:create:{candidate}
```

### 错误 4：业务执行时间超过 leaseTime

可能锁提前失效，导致其他实例进入临界区。

---

## 13. 验证锁

Redis 中查看：

```bash
redis-cli KEYS 'shortlink:lock:*'
```

查看某个锁：

```bash
redis-cli TYPE shortlink:lock:create:abc123
redis-cli HGETALL shortlink:lock:create:abc123
redis-cli PTTL shortlink:lock:create:abc123
```

并发验证：

```text
两个请求同时创建相同候选短码
只有一个请求插入成功
另一个请求重新生成或返回失败
```

---

## 14. 从零实现顺序

```text
1. 加 redisson starter
2. 配置 Redis 地址
3. 注入 RedissonClient
4. 写 demo lock
5. 验证两个线程只有一个进入
6. 把查重和插入放进临界区
7. finally 中 unlock
8. 定时任务加全局锁
9. 验证多实例只执行一次
```

---

## 15. 本篇自检

```text
[ ] 能写 RedissonConfig
[ ] 能解释 tryLock 两个时间参数
[ ] 知道指定 leaseTime 后 watchdog 不续期
[ ] 能把查重和插入放在同一临界区
[ ] 能解释为什么 unlock 必须 finally
[ ] 能写定时任务锁
[ ] 能用 redis-cli 查看锁 Key
```
