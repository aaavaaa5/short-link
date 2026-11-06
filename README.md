# 短链接服务（Shortlink）

基于 Spring Cloud 的短链接服务：生成短链、跳转、管理后台、访问统计。

短链形如 `http://localhost:9000/s/wTrJvy`，访问后重定向到原始长链接，同时**异步**记录访问明细，用于统计点击量、访客地域分布和设备分布。

## 技术栈

| 领域 | 选型 |
|---|---|
| 语言 / 框架 | Java 17、Spring Boot 3.0.7、Spring Cloud 2022.0.3 |
| 服务注册 | Nacos |
| 数据库 | MySQL 8.0 + ShardingSphere-JDBC 5.3.2（2 库 × 8 表） |
| ORM | MyBatis-Plus 3.5.3.1 |
| 缓存 | Caffeine（L1）+ Redis（L2）+ Redis Bitmap 布隆过滤器 |
| 消息队列 | Redis Stream |
| 分布式锁 | Redisson |
| 限流 | Gateway 令牌桶 + Redis 固定窗口 + Sentinel |
| 认证 | JWT（jjwt 0.11.5） |
| 地理分析 | ip2region 离线库 |

## 模块结构

```
shortlink/
├── common/    公共模块：布隆过滤器实现、跨服务共享的 Redis Key 规范
├── gateway/   网关（:9000）：路由转发、CORS、按 IP 限流
├── project/   跳转服务（:8083）：短码解析、多级缓存、302/301 重定向
├── admin/     管理后台（:8081）：认证、短链 CRUD、统计分析、Stream 消费、定时任务
└── resources/ 数据库初始化脚本
```

## 请求链路

```
浏览器 → Gateway :9000
   ├─ /s/**   → project :8083   短链跳转（C 端，只读）
   └─ /api/** → admin   :8081   管理后台（B 端，读写）

跳转链路：
  短码 → L1 Caffeine → L2 Redis → 空值缓存 → 布隆过滤器 → MySQL
       → 同步写 Redis 计数器（PV/UV/IP）
       → 异步发 Redis Stream 消息
       → 重定向

统计链路：
  project 写入 Stream → admin 消费者每秒拉取
       → 解析 UA / GeoIP → 写 t_access_log
  admin 定时任务每天 01:00 把 Redis 计数器聚合进 t_access_stats
```

## 核心功能

- **短链生成**：MurmurHash3 + 58 字符集编码生成 6 位短码，Redisson 分布式锁保证并发下不重复
- **多级缓存**：Caffeine（L1，60 秒）+ Redis（L2，30 分钟±抖动），缓存整个实体 JSON
- **缓存穿透防护**：布隆过滤器（创建时写入、读路径短路、启动时回填历史）+ 空值缓存
- **缓存一致性**：管理端写操作后删除 L2 缓存；L1 靠短 TTL 收敛
- **异步统计流水线**：Redis Stream 消费组 + PEL 重试，不阻塞跳转
- **PV / UV / IP 统计**：PV 用 INCR，UV 和独立 IP 用 HyperLogLog（固定 12KB 估算）
- **地域与设备分析**：ip2region 离线库 + UA 正则解析
- **三层限流**：Gateway 按 IP 令牌桶 → admin 按用户固定窗口 → Sentinel 按接口 QPS

## 快速开始

### 环境要求

- JDK 17、Maven 3.8+
- MySQL 8.0
- Redis 6.0+
- Nacos 2.x（namespace 使用 `shortlink`）

### 步骤

**1. 初始化数据库**

执行 `resources/database/link.sql`，会创建 `shortlink_ds0` / `shortlink_ds1` 两个库及全部分片表。

**2. 启动 Nacos**（默认地址 `127.0.0.1:8848`）

**3. 编译打包**

```bash
mvn clean package -DskipTests
```

**4. 启动服务**

```bash
java -jar admin/target/admin.jar
java -jar project/target/project.jar
java -jar gateway/target/gateway.jar
```

> **注意启动顺序**：admin 启动时会执行一次布隆过滤器历史数据回填（把库中已有的短码补进位图）。
> 读路径的布隆短路依赖位图完整，所以**请先启动 admin 完成回填，再启动 project**。

**5. 验证**

```bash
# 注册并登录，拿到 token
curl -X POST http://localhost:9000/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

# 创建短链（带上 token）
curl -X POST http://localhost:9000/api/link \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"originUrl":"https://www.example.com"}'

# 访问短链，应返回 302 并跳转到原始地址
curl -i http://localhost:9000/s/<shortUri>
```

## 接口概览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` | 注册 |
| POST | `/api/auth/login` | 登录，返回 JWT |
| GET | `/api/user/me` | 当前登录用户信息 |
| POST | `/api/link` | 创建短链 |
| PUT | `/api/link` | 更新短链 |
| DELETE | `/api/link/{id}` | 删除短链（逻辑删除） |
| GET | `/api/link/page` | 分页查询当前用户的短链 |
| GET | `/api/link/{id}` | 短链详情 |
| GET | `/api/stats/link/{linkId}` | 单链接统计（`range=today/7d/30d`） |
| GET | `/api/stats/overall` | 总量统计 |
| GET | `/api/stats/trend` | 趋势数据（`range=7d/30d`） |
| GET | `/api/stats/dashboard/distributions` | 地区/浏览器/设备分布 |
| GET | `/s/{shortUri}` | 短链跳转（经网关） |

Swagger UI：

- admin：`http://localhost:8081/swagger-ui.html`
- project：`http://localhost:8083/swagger-ui.html`

## 关键设计

**分库分表**：`t_link` 按 `gid` 分片（2 库 × 8 表），`gid = "u_" + 用户ID`。同一用户的链接落在同一分片，
访问日志表 `t_access_log` 用 `link_gid` 分片，与主表同分片以免跨库 JOIN。

**为什么缓存整个实体**：跳转时需要判断启用状态、删除标记、过期时间，如果只缓存 URL，命中后仍要回表拿完整实体，
而 `short_uri` 不是分片键、该查询会广播到 16 个分片，缓存就失去了意义。缓存整个实体后命中即返回。

**布隆过滤器为什么抽到 common 模块**：project 负责查位图、admin 负责在建链时写位图，两边操作同一个 Redis key，
哈希算法与参数必须完全一致——如果各写一份实现，一旦不一致会出现"admin 写的位置 project 查不到"，
过滤器静默失效。因此收拢到公共模块，两个服务依赖同一份代码。

**为什么先写布隆再写库**：位图写失败就中止创建，避免出现"库里有、过滤器说没有"的短码（那会让它永久 404）；
位图写成功但入库失败只会留下一个无害的假阳性。

## 设计取舍与已知边界

- **L1 缓存最长 60 秒不一致**：L1 在各实例的 JVM 内，管理端写操作只能删除 Redis（L2），
  因此把 L1 的 TTL 设为 60 秒，把"停用/删除"的生效延迟控制在 1 分钟内。
- **UV 是近似值**：访客标识用 IP + UA 的哈希，不是真正的用户 ID——短链是服务端重定向，跳转时不经过前端、
  无法种 Cookie。同一 WiFi 下多人可能被算成一个，同一人换网络会被算成多个。
- **未做缓存击穿防护**：热点 key 过期瞬间仍可能并发回源。
- **权限目前只做了认证**：JWT 认证已完成，但 `t_user.role` 字段尚未接入角色校验，所有登录用户权限相同。
