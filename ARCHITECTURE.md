# 短链接服务 — 架构设计文档

> 版本：v1.0 | 最后更新：2026-06-09

---

## 一、项目概述

短链接服务是一个基于 Spring Boot 3 + Spring Cloud 的微服务项目，支持短链接生成、跳转、管理后台和数据统计。项目采用**分库分表 + 多级缓存 + 异步消息**架构，确保短链接跳转的 P99 延迟 < 100ms。

### 技术栈

| 领域 | 选型 |
|------|------|
| 语言 | Java 17 |
| 框架 | Spring Boot 3.0.7 + Spring Cloud 2022.0.3 |
| 注册/配置中心 | Nacos |
| 数据库 | MySQL 8.0 + ShardingSphere-JDBC 5.3.2 |
| 缓存 | Redis + Redisson + Caffeine |
| 消息队列 | Redis Stream |
| 网关 | Spring Cloud Gateway |
| ORM | MyBatis-Plus 3.5.3.1 |
| 前端 | Vue 3 + TypeScript + Element Plus + ECharts |
| 认证 | JWT (jjwt 0.11.5) |

### 模块结构

```
shortlink/
├── gateway/     # 网关（端口 9000）：统一入口、路由转发、限流、CORS
├── admin/       # 管理后台（端口 8081）：短链接 CRUD、用户认证、数据统计
├── project/     # 跳转服务（端口 8083）：短码解析、302 重定向、缓存查询
└── resources/   # 数据库脚本、配置模板
```

### 请求链路

```
浏览器 ──→ Gateway (:9000) ──┬── /s/**      ──→ project 模块 (C 端跳转)
                             │
                             └── /api/**    ──→ admin 模块 (管理后台)
                                      │
                                      ├── LoginInterceptor (JWT 校验)
                                      ├── LinkController (CRUD)
                                      └── StatsController (统计)

project 模块 ──→ Redis Stream ──→ admin 模块 (异步消费访问日志)
```

---

## 二、JWT 认证

### 2.1 认证流程

```
用户登录 → AuthController.login()
    │
    ▼
UserServiceImpl 验证用户名/密码（BCrypt）
    │
    ▼
JwtUtil.generateToken(userId, username) → 生成 JWT
    │
    ▼
返回 { token, userId, username, realName } 给前端
    │
    ▼
前端存 localStorage，每次请求带 Header: Authorization: Bearer {token}
    │
    ▼
LoginInterceptor.preHandle() 拦截 /api/** 请求
    │
    ├── 白名单放行: /api/auth/register、/api/auth/login
    │
    ├── 提取 Bearer Token → JwtUtil.parseToken() 校验签名+过期
    │
    ├── 解析 userId/username → 存入 UserContext (ThreadLocal)
    │
    └── 校验失败 → 返回 401
    │
    ▼
afterCompletion() → UserContext.clear() 清理 ThreadLocal
```

### 2.2 JWT 结构

```
Header:   { "alg": "HS256" }
Payload:  {
    "sub":   "1",           // 用户 ID
    "username": "admin",    // 用户名
    "iss":  "shortlink-admin",
    "iat":  1717948800,     // 签发时间
    "exp":  1718035200      // 过期时间（默认 24 小时）
}
Signature: HMAC-SHA256(Header.Payload, secret)
```

### 2.3 设计要点

| 要点 | 说明 |
|------|------|
| **无状态** | JWT 自包含用户信息，Server 不需要 Session，天然支持水平扩展 |
| **ThreadLocal 隔离** | 每个请求线程独立存储用户上下文，避免跨请求串值 |
| **必须清理** | 请求结束后 `UserContext.clear()`，防止线程池复用导致内存泄漏和串值 |
| **白名单** | `/api/auth/register`、`/api/auth/login` 跳过拦截 |
| **密钥安全** | 签名密钥通过配置注入，至少 256 位，生产环境必须修改 |

---

## 三、限流

### 3.1 架构

```
┌───────────────────────────────────────────────┐
│  Gateway 层（全局入口）                         │
│  Spring Cloud Gateway RequestRateLimiter       │
│  基于 Redis + Lua 令牌桶算法                    │
├───────────────────────────────────────────────┤
│  C 端短链接跳转: 100 req/s (replenishRate)      │
│  管理后台 API:   20 req/s                       │
│  限流维度: 按 IP (ipKeyResolver)                │
└───────────────────────────────────────────────┘
```

### 3.2 令牌桶算法原理

```
每个 IP 在 Redis 中维护两个 key:

  request_rate_limiter.{IP}.tokens      ← 当前令牌数
  request_rate_limiter.{IP}.timestamp    ← 上次补充时间

每次请求处理流程:
  1. 计算距上次补充过去了多少秒
  2. 按 replenishRate × 秒数 补充令牌
  3. 令牌数不能超过 burstCapacity（桶容量上限）
  4. 如果有可用令牌 → 消耗 1 个 → 放行
  5. 如果没有令牌 → 返回 429 Too Many Requests
```

### 3.3 令牌桶 vs 漏桶

```
漏桶：强制恒定速率，无法应对突发流量
令牌桶：允许 burstCapacity 个突发请求，同时限制平均速率

本项目选择令牌桶：
  - 短链接跳转有突发特征（营销活动、热点事件）
  - burstCapacity=200 允许短时间内处理 200 个并发请求
  - replenishRate=100 限制长期平均 QPS
```

### 3.4 配置

```yaml
# gateway/application.yml
spring:
  cloud:
    gateway:
      routes:
        # C 端短链接跳转
        - id: shortlink-project
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100   # 每秒补充 100 个令牌
                redis-rate-limiter.burstCapacity: 200   # 桶容量 200
                key-resolver: "#{@ipKeyResolver}"        # 按 IP 限流

        # 管理后台 API（防暴力破解）
        - id: shortlink-admin
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 20    # 每秒 20 个令牌
                redis-rate-limiter.burstCapacity: 50
                key-resolver: "#{@ipKeyResolver}"
```

---

## 四、多级缓存

### 4.1 三级缓存架构

```
请求 shortUri
    │
    ▼
┌─ L1: Caffeine 本地缓存 ─────────────────────────────┐
│  容量: 10,000 条                                     │
│  过期: 写入后 5 分钟                                  │
│  淘汰: W-TinyLFU                                     │
│  延迟: 纳秒级                                         │
└──────────────────────────────────────────────────────┘
    │ miss
    ▼
┌─ L2: Redis 分布式缓存 ───────────────────────────────┐
│  Key: shortlink:{shortUri}                           │
│  TTL: min(30分钟, 过期时间)                            │
│  延迟: 毫秒级（网络 IO）                                │
└──────────────────────────────────────────────────────┘
    │ miss
    ▼
┌─ 布隆过滤器 (Redis Bitmap) ──────────────────────────┐
│  Bitmap 大小: 2^25 ≈ 33,554,432 bits ≈ 4 MB         │
│  哈希函数: 3 个 (MurmurHash3)                         │
│  误判率: ≈ 0.1%                                      │
│  判否: 一定不存在 → 直接返回 null                       │
│  判是: 可能存在 → 继续查 DB                             │
└──────────────────────────────────────────────────────┘
    │ might exist
    ▼
┌─ MySQL (ShardingSphere 分库分表) ────────────────────┐
│  2 库 × 8 表 = 16 分片                                │
│  查询短码（无分片键）→ 广播到所有分片                    │
│  命中后 → 回写 L2 Redis + L1 Caffeine + 布隆过滤器     │
└──────────────────────────────────────────────────────┘
```

### 4.2 L1 — Caffeine 本地缓存

```java
// CaffeineConfig.java
Cache<String, String> shortLinkLocalCache() {
    return Caffeine.newBuilder()
        .maximumSize(10_000)                       // 最多 1 万条
        .expireAfterWrite(Duration.ofMinutes(5))   // 写入 5 分钟后过期
        .recordStats()                             // 记录命中率统计
        .build();
}
```

**为什么选 Caffeine？** 基于 W-TinyLFU 淘汰算法，综合了 LRU（最近使用）和 LFU（最频繁使用）的优势，命中率接近理论最优。纳秒级访问延迟，非常适合高频访问的热点短链接。

### 4.3 L2 — Redis 分布式缓存

```java
// CacheServiceImpl.java — 查询流程
public String getOriginUrl(String shortUri) {
    // 1. L1 Caffeine 本地缓存（纳秒级）
    String url = localCache.getIfPresent(shortUri);
    if (url != null) return url;

    // 2. L2 Redis 分布式缓存（毫秒级）
    url = redisTemplate.opsForValue().get("shortlink:" + shortUri);
    if (url != null) {
        localCache.put(shortUri, url);  // ★ 回写 L1，下次命中更快
        return url;
    }

    return null;  // 缓存全 miss
}
```

**回写策略**：采用 Cache-Aside 模式，L2 命中后回写 L1。下次查询直接命中 Caffeine，延迟从毫秒降到纳秒。

### 4.4 布隆过滤器 — 防缓存穿透

**原理**：

```
对每个 shortUri:
  1. MurmurHash3(shortUri) → hash1
  2. MurmurHash3(shortUri + salt) → hash2
  3. 生成 N 个位置: (hash1 + i × hash2) % bitmapSize  (i = 0,1,2)
  4. 将 Redis Bitmap 对应位置设为 1

查询时:
  如果 N 个位置有任意一个为 0 → ★ 一定不存在（100% 准确）
  如果 N 个位置全部为 1     → 可能存在（误判率 ≈ 0.1%）
```

```
为什么能防穿透？

攻击者随机生成 10 万个不存在的短码:
  → 布隆过滤器直接判否 → 不会打到 DB
  → 10 万次请求只在 Redis 做 Bit 查询，几乎无开销

如果没布隆过滤器:
  → 10 万个查询穿透缓存 → 全部打到 16 个 DB 分片
  → 每个分片收到 10 万次查询 → DB 被打垮
```

### 4.5 缓存 TTL 策略

```java
// RedirectServiceImpl.java — 动态计算缓存过期时间
private Duration calcCacheTtl(LinkEntity entity) {
    if (entity.getExpireTime() != null) {
        long seconds = Duration.between(now, entity.getExpireTime()).getSeconds();
        // 缓存 TTL = min(链接过期时间, 30分钟)
        return Duration.ofSeconds(Math.min(seconds, CACHE_TTL.getSeconds()));
    }
    return Duration.ofMinutes(30);  // 永久链接缓存 30 分钟
}
```

避免短链接已过期但缓存仍有效的脏读问题。

---

## 五、Redis Stream 消息队列

### 5.1 为什么用 Redis Stream？

本项目是练手项目，Redis 已是必需依赖。Redis Stream 提供轻量级消息队列能力，**零额外组件**，适合日志采集场景。生产环境可用 Kafka/RocketMQ 替代。

### 5.2 架构

```
project 模块（生产者）                  admin 模块（消费者）
     │                                        │
     │ 用户访问短链接                            │ @Scheduled(fixedDelay=1000)
     │ RedirectController.redirect()           │ 每秒拉取一次
     │   ↓                                     │
     │ RedirectServiceImpl.recordAccess()      │
     │   ├─ ① 同步: Redis 计数器 PV/UV/IP      │
     │   └─ ② 异步: AccessEventProducer        │
     │          .sendAccessEvent()             │
     ▼                                        ▼
┌─────────────────────────────────────────────────────┐
│              Redis Stream                            │
│         shortlink:access:stream                      │
│                                                      │
│  消息格式:                                            │
│  {                                                   │
│    link_id, link_gid, short_uri,                    │
│    visitor_id, visitor_ip, user_agent,              │
│    referer, access_time                             │
│  }                                                   │
│                                                      │
│  自动 trim: 保留最新 10 万条                           │
└─────────────────────────────────────────────────────┘
                                     │
                                     ▼
                            AccessEventConsumer
                            ┌─ consumePending()  重试失败消息
                            ├─ consumeNew()      消费新消息
                            │   ├─ UA 解析 → 浏览器 / OS / 设备
                            │   ├─ GeoIP 解析 → 国家 / 省份 / 城市
                            │   └─ 写入 t_access_log 分片表
                            └─ XACK 确认消费
```

### 5.3 生产者 — 跳转时异步发送

```java
// RedirectServiceImpl.recordAccess()
public void recordAccess(LinkEntity entity, HttpServletRequest request) {
    // ① 同步：Redis 实时计数器
    incrementCounters(entity, request);  // PV 自增 + HyperLogLog UV/IP
    // ★ 此步骤同步执行，因为 Redis 操作纳秒级，不影响跳转性能

    // ② 异步：Redis Stream 消息
    accessEventProducer.sendAccessEvent(entity, request);
    // ★ UA 解析、GeoIP 查询、DB 写入不阻塞跳转响应
}
```

**为什么拆分同步/异步？**
- 计数器是**轻量操作**（Redis INCR + PFADD，纳秒级），可同步执行
- UA 解析和 GeoIP 查询**耗时长**（正则匹配、IP 库查询，几十到上百毫秒）
- 跳转响应 P99 必须 < 100ms，异步化是唯一的方案

### 5.4 消费者 — 定时批量拉取 + 消费者组

```java
@Scheduled(fixedDelay = 1000)  // 每秒拉取一次
public void consume() {
    ensureGroup();        // ① 保证消费者组存在
    consumePending();     // ② 先处理上次失败的 Pending 消息
    consumeNew();         // ③ 再拉取新消息
}
```

**消费者组机制**：
- `XREADGROUP` 读取 → 处理（UA + GeoIP 解析 + DB 写入）→ `XACK` 确认
- 处理失败的消息自动进入 Pending 列表
- 下次消费时**优先重试** Pending 消息
- 支持多实例部署（消费者组内自动负载均衡）

### 5.5 计数器实现

```java
// PV: 原子自增
redisTemplate.opsForValue().increment("shortlink:click:{linkId}:{date}:pv");

// UV: HyperLogLog 去重（visitorId = IP + UA 的哈希）
redisTemplate.opsForHyperLogLog().add("shortlink:click:{linkId}:{date}:uv", visitorId);

// IP: HyperLogLog 去重
redisTemplate.opsForHyperLogLog().add("shortlink:click:{linkId}:{date}:ip", clientIp);
```

**为什么用 HyperLogLog？**
- 精确去重需要存储所有 visitorId → 内存巨大
- HyperLogLog 用 12KB 存储即可估算 2^64 个独立元素
- 标准误差仅 0.81%，对 PV/UV 统计完全可接受

---

## 六、分库分表

### 6.1 分片策略

```
分片路由:
  gid = "G-618001"
       │
       ▼
  第一步: HASH_MOD(gid, 2) → 定库 ds_0 / ds_1
       │
       ▼
  第二步: HASH_MOD(gid, 8)  → 定表 t_link_0 ~ t_link_7
       │
       ▼
  最终: ds_0.t_link_3
```

| 配置项 | 值 |
|--------|-----|
| 分片键 | `gid`（分组标识） |
| 逻辑库 | 2（可扩至 4/8） |
| 每库表数 | 8 |
| 总分片 | 16 |
| 库路由算法 | HASH_MOD(gid, 2) |
| 表路由算法 | HASH_MOD(gid, 8) |

### 6.2 分片表

| 表名 | 是否分片 | 分片键 | 说明 |
|------|----------|--------|------|
| `t_link` | ✅ | `gid` | 核心表，按分组隔离 |
| `t_access_log` | ✅ | `link_gid` | 访问日志，与 t_link 同分片 |
| `t_access_stats` | ✅ | `link_gid` | 统计聚合表，与 t_link 同分片 |
| `t_group` | ❌ | — | 数据量小，全局表 |
| `t_user` | ❌ | — | 数据量小，全局表 |

### 6.3 广播查询

短链接跳转时按 `short_uri` 查询（无分片键 `gid`），ShardingSphere 会**广播到所有 16 个分片**，然后合并结果。这看似低效，但因为有**多级缓存**，实际 DB 查询次数极少。

---

## 七、各机制分工总结

| 机制 | 解决的问题 | 核心技术 |
|------|-----------|---------|
| **JWT** | 无状态认证，支持水平扩展 | HMAC-SHA256 + ThreadLocal |
| **限流** | 防刷 / 防暴力破解 | Redis 令牌桶 (Gateway) |
| **L1 缓存** | 降低响应延迟至纳秒级 | Caffeine W-TinyLFU |
| **L2 缓存** | 多实例共享缓存 | Redis String |
| **布隆过滤器** | 防缓存穿透（随机短码攻击） | Redis Bitmap + MurmurHash3 |
| **消息队列** | 跳转响应与日志解析解耦 | Redis Stream + 消费者组 |
| **分库分表** | 海量数据水平扩展 | ShardingSphere-JDBC HASH_MOD |

**核心设计理念**：**同步路径极速响应**（缓存 + Redis 计数，P99 < 100ms），**异步路径慢慢处理**（UA/GeoIP 解析 + 写 DB，不阻塞跳转）。
