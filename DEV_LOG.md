# 开发日志

> 项目：短链接服务 (shortlink)
> 最后更新：2026-06-09

---

## 2026-06-08 — Project 模块 redirect 开发

### 完成事项

#### 1. 统一响应类 Result<T>
- **文件**: `project/src/main/java/com/app/entity/Result.java`
- 从 `ResponseEntity` 重命名为 `Result`，避免与 Spring 同名类冲突
- 泛型 `<T>` 支持任意数据类型
- 静态工厂方法：`success()` / `success(data)` / `success(msg, data)` / `fail()` / `fail(msg)` / `fail(code, msg)`
- 状态码常量：200/400/401/403/404/409/500
- 实例方法：`isSuccess()`

#### 2. 多级缓存 CacheService
- **接口**: `project/src/main/java/com/app/service/CacheService.java`
- **实现**: `project/src/main/java/com/app/service/Impl/CacheServiceImpl.java`
- Redis String: `shortlink:{shortUri}` → originUrl
- 布隆过滤器: Redis Bitmap (`shortlink:bf:short_uri`)，Guava murmur3_32 双重哈希，~4MB
- 缓存穿透保护：布隆过滤判否 → 直接返回 null，不查 DB

#### 3. 短链接跳转 RedirectService
- **接口**: `project/src/main/java/com/app/service/RedirectService.java`
- **实现**: `project/src/main/java/com/app/service/Impl/RedirectServiceImpl.java`
- 查询链路：L2 Redis → 布隆过滤器 → MySQL 兜底 → 回写缓存
- 缓存 TTL：min(30分钟, 链接剩余有效期)
- 已知限制：缓存只存 originUrl，状态字段仍需查 DB；优化方向 → JSON 序列化完整实体

#### 4. 访问事件生产者 AccessEventProducer
- **文件**: `project/src/main/java/com/app/mq/AccessEventProducer.java`
- Redis Stream: `shortlink:access:stream`，MAXLEN ~100k
- 消息字段：link_id, link_gid, short_uri, visitor_id, visitor_ip, user_agent, referer, access_time
- 客户端 IP 获取：X-Forwarded-For → X-Real-IP → RemoteAddr
- 访客标识：IP + UA 的 UUID hash
- 发送失败仅记录日志，不影响跳转响应

#### 5. 跳转控制器 RedirectController
- **文件**: `project/src/main/java/com/app/controller/RedirectController.java`
- 端点：`GET /{shortUri}`
- 校验链路：不存在(404) → 已删除(410) → 已停用(403) → 已过期(410) → 302 重定向
- 支持 301/302 按 redirectType 字段选择
- Swagger 注解：@Tag, @Operation, @ApiResponse, @Parameter
- 错误响应使用项目统一的 `Result<T>` JSON 格式

#### 6. Swagger/SpringDoc 集成
- **依赖**: `springdoc-openapi-starter-webmvc-ui` 2.1.0
- **配置**: `project/src/main/java/com/app/config/OpenApiConfig.java`
- **application.yml**: `springdoc.swagger-ui.path: /swagger-ui.html`
- Swagger UI 访问：`http://localhost:8083/swagger-ui.html`

#### 7. POM 修复
- 删除不存在的 `aggregation` 模块（重复声明）
- 父 POM modules 现为：admin, project, gateway

### 当前项目结构 (project 模块)

```
project/src/main/java/com/app/
├── shortlinkApplication.java
├── entity/
│   ├── LinkEntity.java          # 短链接实体 (MyBatis-Plus)
│   └── Result.java              # 统一 API 响应
├── mapper/
│   └── LinkMapper.java          # MyBatis-Plus Mapper
├── service/
│   ├── RedirectService.java     # 跳转服务接口
│   ├── CacheService.java        # 缓存服务接口
│   └── Impl/
│       ├── RedirectServiceImpl.java
│       └── CacheServiceImpl.java
├── controller/
│   └── RedirectController.java  # GET /{shortUri} → 302
├── config/
│   ├── OpenApiConfig.java       # Swagger 配置
│   ├── RedisConfig.java         # 空壳，待实现
│   ├── CaffeineConfig.java      # 空壳，待实现
│   └── BloomFilterConfig.java   # 空壳，待实现
└── mq/
    └── AccessEventProducer.java # Redis Stream 生产者
```

### 下一步

- [ ] Project 模块：实现 Redis 实时计数器（PV INCR / UV PFADD / IP PFADD）
- [ ] Project 模块：L1 Caffeine 本地缓存
- [ ] Admin 模块：短链 CRUD（LinkController + LinkService）
- [ ] Admin 模块：JWT 鉴权（AuthController + JwtUtil）
- [ ] Admin 模块：Redis Stream 消费者（AccessEventConsumer）
- [ ] Admin 模块：统计查询接口
- [ ] Gateway 模块：路由 + JWT 过滤器 + 限流过滤器

---

## 2026-06-09 — Project 模块收尾

### 新增：L1 Caffeine 本地缓存
- **依赖**: `caffeine` 3.1.6
- **配置**: `config/CaffeineConfig.java` — maxSize=10000, TTL=5min, recordStats
- **实现**: CacheServiceImpl 正式实现 L1→L2→DB 三级查询链
  - L1 命中 → 纳秒级返回（无网络 IO）
  - L1 未命中 → L2 Redis → 命中后回写 L1
  - L1+L2 都未命中 → 返回 null → 上层查 DB

### 新增：Redis 实时计数器
- **文件**: RedirectServiceImpl.incrementCounters()
- PV: `INCR shortlink:click:{link_id}:{date}:pv`（原子自增，无竞态）
- UV: `PFADD shortlink:click:{link_id}:{date}:uv {visitor_id}`（HyperLogLog, 12KB 固定内存）
- IP: `PFADD shortlink:click:{link_id}:{date}:ip {client_ip}`
- TTL: 3 天，超时后 Redis 自动清理

### 新增：Thymeleaf 错误页面
- **模板路径**: `resources/templates/error/`
  - `404.html` — 短链接不存在
  - `403.html` — 短链接已停用
  - `410.html` — 短链接已过期/删除
- **Controller 改动**: `@RestController` → `@Controller`（支持返回 HTML 视图）
  - 成功：`return "redirect:" + originUrl`（302）或手动 301
  - 错误：`return "error/xxx"` → Thymeleaf 渲染 HTML 页面

### 新增：PROJECT_DOC.md
- 面向初学者的完整技术文档
- 覆盖：架构图、每行代码解释、多级缓存原理、布隆过滤器原理、HyperLogLog 原理、消息队列模式、术语表

### Project 模块完成度

| 功能 | 状态 |
|------|------|
| `Result<T>` 统一响应 | ✅ |
| `LinkEntity` + `LinkMapper` | ✅ |
| L1 Caffeine 本地缓存 | ✅ |
| L2 Redis 分布式缓存 | ✅ |
| 布隆过滤器（Redis Bitmap） | ✅ |
| `GET /{shortUri}` → 302 重定向 | ✅ |
| 状态校验（删除/停用/过期） | ✅ |
| Thymeleaf 错误页面 | ✅ |
| Redis 实时计数器（PV/UV/IP） | ✅ |
| Redis Stream 消息生产者 | ✅ |
| Swagger 接口文档 | ✅ |
| 面向初学者的项目文档 | ✅ |
