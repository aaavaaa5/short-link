# Project 模块详解 — 短链接跳转服务

> 适用读者：Java 初学者，已了解 Spring Boot 基本概念
> 目标：理解短链接跳转的完整实现和背后的技术原理

---

## 一、模块概览

Project 模块是整个短链接系统的**核心**——负责接收短链接访问请求，找到原始 URL，然后 302 重定向过去。

```
用户浏览器                    Project 模块
    │                            │
    │  GET /abc123               │
    │ ──────────────────────────▶│
    │                            │ 1. L1 Caffeine 本地缓存（纳秒级）
    │                            │ 2. L2 Redis 分布式缓存（毫秒级）
    │                            │ 3. 布隆过滤器防穿透
    │                            │ 4. MySQL 兜底查询
    │                            │ 5. 校验状态（过期/停用/删除）
    │  ← 302 Location: URL       │ 6. 记录访问（Redis 计数 + Stream）
    │                            │ 7. 返回 302 重定向
    │ ──→ 跳转到原始 URL          │
```

---

## 二、目录结构

```
project/src/main/java/com/app/
├── shortlinkApplication.java       ← Spring Boot 启动类
├── entity/
│   ├── LinkEntity.java             ← 数据库实体（对应 t_link 表）
│   └── Result.java                 ← 统一 API 响应封装
├── mapper/
│   └── LinkMapper.java             ← MyBatis-Plus 数据访问层
├── service/
│   ├── CacheService.java           ← 多级缓存接口
│   ├── RedirectService.java        ← 跳转服务接口
│   └── Impl/
│       ├── CacheServiceImpl.java   ← 缓存实现（L1 + L2 + 布隆过滤器）
│       └── RedirectServiceImpl.java ← 跳转实现（缓存查询 + 计数）
├── controller/
│   └── RedirectController.java     ← GET /{shortUri} 跳转入口
├── config/
│   ├── OpenApiConfig.java          ← Swagger 文档配置
│   └── CaffeineConfig.java         ← L1 本地缓存配置
└── mq/
    └── AccessEventProducer.java    ← Redis Stream 消息生产者

project/src/main/resources/
├── application.yml                 ← 配置文件
└── templates/error/                ← Thymeleaf 错误页面
    ├── 404.html                    ← 短链接不存在
    ├── 403.html                    ← 短链接已停用
    └── 410.html                    ← 短链接已过期/删除
```

---

## 三、核心文件详解

### 3.1 Result.java — 统一响应格式

```java
@Data
public class Result<T> implements Serializable {
    private Integer code;    // 业务状态码：200 成功，500 失败...
    private String message;  // 提示信息
    private T data;          // 响应数据（泛型）
}
```

**为什么要统一响应格式？**

前端（或调用方）需要一种**统一的、可预测的**方式来判断请求是否成功。如果每个接口返回格式不同，前端需要写很多种判断逻辑。

```json
// 所有接口都返回这个格式，前端只需判断 code 是否为 200
{ "code": 200, "message": "操作成功", "data": { "shortUri": "abc123" } }
{ "code": 404, "message": "短链接不存在", "data": null }
```

**为什么叫 `Result` 而不是 `ResponseEntity`？**

Spring 框架自带了 `org.springframework.http.ResponseEntity` 类（HTTP 级别的响应封装）。如果我们也叫 `ResponseEntity`，在 Controller 中同时使用两个类时会冲突（编译器不知道该用哪个）。`Result` 简洁、不会冲突。

**泛型 `<T>` 的作用**：让 `data` 字段可以承载任意类型：

```java
Result<LinkVO>          // data 是单个短链接对象
Result<List<LinkVO>>    // data 是列表
Result<IPage<LinkVO>>   // data 是分页对象
Result<Void>            // data 为 null，不返回数据
```

**`isSuccess()` 为什么存在？**

```java
Result<String> result = linkService.createLink(dto);
if (result.isSuccess()) {
    // 成功处理
} else {
    // 失败处理，可以拿到 result.getCode() 和 result.getMessage()
}
```

让代码更语义化——`result.isSuccess()` 比 `result.getCode() == 200` 更易读。

---

### 3.2 CacheService + CacheServiceImpl — 多级缓存

这是 Project 模块**最关键的性能组件**。

#### 什么是多级缓存？

```
请求速度金字塔：
┌─────────────────────────────────┐
│  L1: Caffeine (JVM 堆内存)      │  纳秒级 ← 最快，容量最小
│  容量: 10000 条, TTL: 5 分钟     │
├─────────────────────────────────┤
│  L2: Redis (独立进程/服务器)      │  毫秒级 ← 较快，数据共享
│  容量: 受内存限制, TTL: ≤30分钟   │
├─────────────────────────────────┤
│  L3: MySQL (磁盘)               │  毫秒~秒级 ← 最慢，全量数据
└─────────────────────────────────┘
```

**为什么需要 L1 + L2 两层缓存？**

- **L1 本地缓存**：在 JVM 堆内存中，读取**不需要网络 IO**，纳秒级返回。但：
  - 容量有限（内存宝贵）
  - 多个服务实例各自维护，数据不一致
  - 服务重启丢失

- **L2 Redis 缓存**：独立的缓存服务器，读取需要网络 IO（约 0.1~1ms）。但：
  - 所有实例共享同一份数据
  - 支持持久化
  - 容量大

将两者组合：L1 兜住热点数据（最快），L2 兜住大部分数据（较快），DB 兜底（最慢）。

#### 查询流程

```java
public String getOriginUrl(String shortUri) {
    // 第 1 步：查 L1 Caffeine
    String url = localCache.getIfPresent(shortUri);
    if (url != null) return url;          // 命中 L1，直接返回

    // 第 2 步：查 L2 Redis
    url = redisTemplate.opsForValue().get("shortlink:" + shortUri);
    if (url != null) {
        localCache.put(shortUri, url);    // 回写 L1，下次命中更快
        return url;
    }

    return null;  // 缓存未命中，上层需查 MySQL
}
```

**为什么 L2 命中后要回写 L1？**

假设 `abc123` 这个短链接很热门，每分钟被访问 100 次：

- 无 L1：100 次全部走 Redis 网络 IO → 总耗时 ≈ 100ms
- 有 L1 回写：第 1 次走 Redis → 回写 L1 → 后面 99 次走 L1 → 总耗时 ≈ 1ms

回写 L1 的成本很低（一次内存写入），收益巨大。

#### 布隆过滤器 — 防缓存穿透

**什么是缓存穿透？**

攻击者用大量**不存在的短码**（如随机字符串）访问跳转接口。如果每次请求都穿透缓存查 DB，数据库会被打垮。

**布隆过滤器原理**

```
布隆过滤器回答一个问题："这个元素可能存在吗？"

答案有两种：
  ✗ "一定不存在" → 直接返回，不查 DB  ← 100% 准确
  ✓ "可能存在"   → 还是要查 DB       ← 有小概率误判

关键优势：无论数据量多大，布隆过滤器空间固定（本项目 ≈ 4MB）
```

**实现原理**：将短码用 3 个哈希函数映射到 Bitmap 的 3 个位置，全部置为 1。查询时检查这 3 个位置是否全为 1——缺一个就是"一定不存在"。

```
添加 "abc123":
  hash1("abc123") = 位置 100
  hash2("abc123") = 位置 5000     → 将这 3 位设为 1
  hash3("abc123") = 位置 30000

查询 "xyz999":
  hash1("xyz999") = 位置 50
  hash2("xyz999") = 位置 5000     → 位置 50 是 0 → "一定不存在"
  hash3("xyz999") = 位置 20000
```

本项目用 Redis Bitmap 实现。`SETBIT key offset 1` 设置某一位为 1，`GETBIT key offset` 读取某一位。

---

### 3.3 RedirectServiceImpl — 跳转核心逻辑

#### 完整查询链路

```java
public LinkEntity getLinkEntity(String shortUri) {
    // 第 1 关：L1 + L2 缓存查询（CacheService 内部串联）
    String cachedUrl = cacheService.getOriginUrl(shortUri);
    if (cachedUrl != null) {
        return queryFromDb(shortUri);  // 仍需 DB 获取状态字段
    }

    // 第 2 关：布隆过滤器防穿透
    if (!cacheService.mightExist(shortUri)) {
        return null;  // 短码一定不存在，直接返回
    }

    // 第 3 关：MySQL 兜底
    LinkEntity entity = queryFromDb(shortUri);
    if (entity != null) {
        cacheService.cacheOriginUrl(shortUri, entity.getOriginUrl(), ttl);
        cacheService.addToBloomFilter(shortUri);
    }
    return entity;
}
```

**为什么缓存命中后还要查 DB？**

当前缓存只存了 `shortUri → originUrl`（一个字符串），但 Controller 需要校验：
- `enableStatus`：是否被管理员停用
- `delFlag`：是否被软删除
- `expireTime`：是否已过期

这些状态字段在缓存中不存在，所以需要查 DB 获取完整实体。

**优化方向**：将 `LinkEntity` 序列化为 JSON 存入 Redis，命中后直接反序列化，省去 DB 查询。

#### 实时计数器

```java
// PV：每次访问原子自增 1
redisTemplate.opsForValue().increment("shortlink:click:123:2026-06-09:pv");

// UV：HyperLogLog 去重统计
redisTemplate.opsForHyperLogLog().add("shortlink:click:123:2026-06-09:uv", visitorId);

// 独立 IP：HyperLogLog 去重统计
redisTemplate.opsForHyperLogLog().add("shortlink:click:123:2026-06-09:ip", clientIp);
```

**INCR 是什么？为什么用它做 PV 计数？**

`INCR` 是 Redis 的**原子自增**命令。原子意味着在高并发下不会出现"读-改-写"的竞态条件。

想象两个请求同时到来：

```
非原子操作（错误）:
  请求A 读到 PV=100            请求B 读到 PV=100
  请求A 计算 100+1=101         请求B 计算 100+1=101
  请求A 写入 PV=101            请求B 写入 PV=101
  结果：两次访问，PV 只增加了 1  ← 丢失了一次

Redis INCR（正确）:
  Redis 内部保证"读-改-写"是一个不可分割的操作
  请求A INCR → Redis 返回 101
  请求B INCR → Redis 返回 102
  结果：两次访问，PV 正确增加了 2
```

**HyperLogLog 是什么？为什么用它做 UV 去重？**

UV（独立访客）需要**去重**——同一个用户访问 100 次只算 1 个 UV。

传统做法是用 `Set` 存储每个访客 ID：
- 100 万 UV → 需要存储 100 万个 ID → 约 50MB 内存
- 1 亿 UV → 约 5GB 内存

HyperLogLog 使用**概率算法**：
- 无论 UV 是 100 万还是 1 亿 → 固定使用 **12KB** 内存
- 代价：计数有约 0.81% 的误差（100 万 UV 可能显示为 99 万 ~ 101 万）

对于 UV 这类"趋势性"指标，0.81% 误差完全可以接受。

---

### 3.4 RedirectController — 请求入口

```java
@Controller  // 不是 @RestController，因为需要返回 HTML 页面
public class RedirectController {

    @GetMapping("/{shortUri}")
    public String redirect(@PathVariable String shortUri,
                           HttpServletRequest request,
                           HttpServletResponse response,
                           Model model) throws IOException {
        // 校验 → 错误返回页面视图，成功返回重定向
    }
}
```

**为什么用 `@Controller` 而不是 `@RestController`？**

| | `@Controller` | `@RestController` |
|------|------|------|
| 方法返回值 | 视图名（如 `"error/404"`）或 `"redirect:..."` | JSON 字符串 |
| 适合场景 | 浏览器访问，需要渲染 HTML 页面 | API 接口，返回 JSON 数据 |

跳转接口是**浏览器访问**的，错误时需要展示一个好看的 HTML 页面（不是冷冰冰的 JSON），所以用 `@Controller`。

**重定向是怎么实现的？**

```java
// 方式 1: Spring "redirect:" 前缀（默认 302）
return "redirect:" + entity.getOriginUrl();

// 方式 2: 手动设置 301 永久重定向
response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
response.setHeader("Location", entity.getOriginUrl());
return null;  // 告诉 Spring：响应我已经手动处理了
```

**301 vs 302 的区别：**

| | 301 永久重定向 | 302 临时重定向 |
|------|------|------|
| 浏览器行为 | 缓存重定向结果，下次直接跳到目标 URL | 每次都先访问短链接 |
| SEO 影响 | 搜索引擎收录目标 URL | 搜索引擎收录短链接地址 |
| 适用场景 | 域名迁移等永久变化 | 临时跳转、链接可能被修改 |
| 默认 | | ✓ |

本项目允许创建短链时选择 301 或 302，存储在 `redirectType` 字段中。

---

### 3.5 AccessEventProducer — 消息生产者

```java
@Component
public class AccessEventProducer {
    public static final String STREAM_KEY = "shortlink:access:stream";

    public void sendAccessEvent(LinkEntity entity, HttpServletRequest request) {
        // 发送消息到 Redis Stream
    }
}
```

**为什么要用消息队列？**

跳转请求需要尽可能快（目标 P99 < 100ms），但有些操作很慢：

- 解析 User-Agent（浏览器型号、操作系统）→ 需要正则匹配
- GeoIP 解析（IP 归属地）→ 需要查离线数据库
- 写入 `t_access_log` 表 → 数据库 INSERT

如果这些操作在跳转请求中**同步**执行，跳转延迟会从 1ms 飙升到 50ms+。

消息队列实现**异步解耦**：

```
同步模式（慢）：
  收到请求 → 查缓存 → 写日志 → 重定向
                        ↑ 阻塞跳转响应

异步模式（快）：
  收到请求 → 查缓存 → 发消息 → 重定向
                  ↓         ↑ 不阻塞
              [Redis Stream]
                  ↓
           Admin 消费者（异步处理日志）
```

**Redis Stream 是什么？**

Redis Stream 是 Redis 5.0 引入的轻量级消息队列，类似 Kafka 但更简单：

- 生产者：`XADD stream_key * field1 value1 field2 value2 ...`
- 消费者组：`XREADGROUP GROUP group_name consumer_name ...`
- ACK 机制：处理完毕后 `XACK`，保证消息不丢失

本项目选择 Redis Stream 而非 Kafka/RabbitMQ 的原因是：
- 项目已依赖 Redis，无需引入新的中间件
- Stream 的吞吐量完全可以满足访问日志场景

---

## 四、数据流全景

```
http://localhost:8083/abc123
        │
        ▼
┌──────────────────────────────────────────┐
│ RedirectController.redirect()            │
│                                          │
│ 1. redirectService.getLinkEntity("abc")  │
│    ├── L1 Caffeine（缓存命中率 > 70%）    │
│    ├── L2 Redis（缓存命中率 > 27%）       │
│    ├── 布隆过滤器（拒绝不存在短码）        │
│    └── MySQL（兜底，命中率 < 3%）         │
│                                          │
│ 2. 状态校验                              │
│    ├── entity == null        → 404 页面  │
│    ├── delFlag == 1          → 410 页面  │
│    ├── enableStatus == 0     → 403 页面  │
│    ├── expireTime < now      → 410 页面  │
│    └── 全部通过 → 继续        ↓          │
│                                          │
│ 3. redirectService.recordAccess()        │
│    ├── Redis INCR(PV)                   │
│    ├── Redis PFADD(UV)                  │
│    ├── Redis PFADD(IP)                  │
│    └── AccessEventProducer → Stream     │
│                                          │
│ 4. 302 重定向                            │
│    return "redirect:" + originUrl        │
└──────────────────────────────────────────┘
```

---

## 五、关键设计模式

### 5.1 模板方法模式（间接使用）

MyBatis-Plus 的 `BaseMapper<T>` 提供了通用的 CRUD 方法：

```java
public interface LinkMapper extends BaseMapper<LinkEntity> {
    // 无需写任何方法，继承即可使用 selectOne、selectList、insert、update...
}
```

### 5.2 静态工厂方法模式

`Result` 类的 `success()` / `fail()` 方法隐藏了对象构造细节：

```java
// 不用这样（暴露内部结构）
Result<String> r = new Result<>();
r.setCode(200);
r.setMessage("操作成功");
r.setData(data);

// 而是这样（语义清晰、代码简洁）
Result<String> r = Result.success(data);
```

### 5.3 生产者-消费者模式

```
Producer（Project）  →  Redis Stream  →  Consumer（Admin）
     发送消息                              消费消息
```

生产者只负责"把消息扔出去"，不关心谁消费、怎么消费。消费者独立处理，互不影响。

---

## 六、常见术语

| 术语 | 解释 |
|------|------|
| **PV** | Page View，页面访问量，每次访问 +1，不区分是否同一用户 |
| **UV** | Unique Visitor，独立访客数，同一用户多次访问只算 1 个 |
| **IP** | 独立 IP 数，同一 IP 多次访问只算 1 个 |
| **301** | 永久重定向，浏览器会缓存，下次直接跳到目标 |
| **302** | 临时重定向，浏览器每次都先访问原地址 |
| **L1/L2/L3** | 缓存层级，数字越小越接近 CPU/内存，速度越快 |
| **TTL** | Time To Live，缓存生存时间，到期自动删除 |
| **缓存穿透** | 查询不存在的数据，每次请求都打到 DB |
| **缓存击穿** | 热点数据过期瞬间，大量请求同时打到 DB |
| **缓存雪崩** | 大量缓存同时过期，DB 瞬间承受巨大压力 |
| **布隆过滤器** | 用极小内存判断"一定不存在"，但无法判断"一定存在" |
| **HyperLogLog** | 概率去重算法，固定内存，误差约 0.81% |
| **Stream** | Redis 5.0 的消息队列数据结构 |
| **ACK** | Acknowledgment，消息确认，告诉队列"这条我处理完了" |
| **软删除** | 逻辑删除（设 delFlag=1），数据还在但不对外展示 |
| **IOC** | Inversion of Control，控制反转——Spring 帮你创建和管理对象 |
| **DI** | Dependency Injection，依赖注入——IOC 的具体实现方式（@Autowired） |
