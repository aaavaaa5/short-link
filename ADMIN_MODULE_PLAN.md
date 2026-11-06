# Admin 模块开发方案

> 日期：2026-06-09
> 状态：待评审

---

## 一、背景与目标

Admin 模块是短链接服务的**管理后台**，负责短链接 CRUD、用户认证（JWT）、数据统计展示以及 Redis Stream 消费。

当前状态：Admin 模块只有一个空壳（启动类 + 不完整的配置），需要从零构建完整的业务能力。Project 模块（跳转核心）已完成，Admin 需要消费 Project 发送到 Redis Stream 的访问事件。

---

## 二、当前状态盘点

### 2.1 现有文件

```
admin/
├── pom.xml                                    # 基本依赖，缺少 jjwt/springdoc/jsoup/nacos-config
├── src/main/java/com/app/
│   └── userManageApplication.java             # 启动类，命名不规范，@MapperScan() 空的
├── src/main/resources/
│   └── application.yml                        # ds1 的 jdbc-url 缺少数据库名
└── src/test/java/com/app/
    └── AppTest.java
```

### 2.2 待修复问题

| 问题 | 位置 | 修复方案 |
|------|------|----------|
| 启动类命名 | `userManageApplication.java` | 重命名为 `AdminApplication.java` |
| @MapperScan 为空 | 启动类 | 改为 `@MapperScan("com.app.mapper")` |
| 缺少 @EnableScheduling | 启动类 | 添加注解（后续定时任务需要） |
| ds1 URL 不完整 | `application.yml` | `jdbc:mysql://localhost:3306/` → `jdbc:mysql://localhost:3306/shortlink_ds1` |
| 缺少 jjwt 依赖 | `pom.xml` | JWT 鉴权必需 |
| 缺少 springdoc 依赖 | `pom.xml` | Swagger 文档必需 |
| 缺少 jsoup 依赖 | `pom.xml` | 网页标题抓取必需 |
| 缺少 nacos-config 依赖 | `pom.xml` | Nacos 配置中心 |

---

## 三、代码复用策略

> **决策：直接复制，不创建 common 模块。**

理由：
- 需要复用的只有 `LinkEntity.java` 和 `Result.java` 两个文件
- 创建 common 模块会增加模块间耦合和构建复杂度
- 两个文件都很稳定，不会频繁变更
- Admin 和 Project 作为独立微服务，各自持有自己的实体定义更灵活

---

## 四、目标目录结构

```
admin/src/main/java/com/app/
├── AdminApplication.java              # 启动类
├── entity/
│   ├── LinkEntity.java                # [复制] 短链接实体
│   ├── Result.java                    # [复制] 统一响应类
│   ├── GroupEntity.java               # [新增] 分组实体
│   ├── UserEntity.java                # [新增] 用户实体
│   ├── AccessLogEntity.java           # [新增] 访问日志实体
│   ├── AccessStatsEntity.java         # [新增] 访问统计实体
│   └── OperationLogEntity.java        # [新增] 操作日志实体
├── dto/
│   ├── req/
│   │   ├── LoginReqDTO.java           # 登录请求
│   │   ├── RegisterReqDTO.java        # 注册请求
│   │   ├── LinkCreateReqDTO.java      # 创建短链请求
│   │   ├── LinkUpdateReqDTO.java      # 更新短链请求
│   │   ├── LinkPageReqDTO.java        # 短链分页查询
│   │   └── GroupCreateReqDTO.java     # 创建分组请求
│   └── resp/
│       ├── LoginRespDTO.java          # 登录响应
│       ├── LinkVO.java                # 短链视图对象
│       ├── StatsVO.java               # 统计视图对象
│       └── GroupVO.java               # 分组视图对象
├── mapper/
│   ├── LinkMapper.java                # 短链 Mapper
│   ├── GroupMapper.java               # 分组 Mapper
│   ├── UserMapper.java                # 用户 Mapper
│   ├── AccessLogMapper.java           # 访问日志 Mapper
│   ├── AccessStatsMapper.java         # 访问统计 Mapper
│   └── OperationLogMapper.java        # 操作日志 Mapper
├── service/
│   ├── LinkService.java               # 短链服务接口
│   ├── GroupService.java              # 分组服务接口
│   ├── UserService.java               # 用户服务接口
│   ├── StatsService.java              # 统计服务接口
│   └── Impl/
│       ├── LinkServiceImpl.java       # 短链服务实现
│       ├── GroupServiceImpl.java      # 分组服务实现
│       ├── UserServiceImpl.java       # 用户服务实现
│       └── StatsServiceImpl.java      # 统计服务实现
├── controller/
│   ├── AuthController.java            # POST /api/v1/auth/*
│   ├── LinkController.java            # /api/v1/links/*
│   ├── GroupController.java           # /api/v1/groups/*
│   ├── StatsController.java           # /api/v1/stats/*
│   └── UserController.java            # /api/v1/users/*
├── config/
│   ├── WebMvcConfig.java              # 拦截器注册 + CORS
│   ├── LoginInterceptor.java          # JWT 鉴权拦截器
│   └── OpenApiConfig.java             # Swagger 配置
├── mq/
│   └── AccessEventConsumer.java       # Redis Stream 消费者
├── util/
│   ├── JwtUtil.java                   # JWT 工具类
│   ├── UserAgentUtil.java             # UA 解析工具
│   ├── GeoIpUtil.java                 # GeoIP 解析工具（Phase1 占位）
│   ├── ShortLinkGenerator.java        # 短码生成器
│   └── UserContext.java               # ThreadLocal 用户上下文
└── scheduled/
    ├── StatsAggregationTask.java      # 每5分钟统计聚合
    └── MidnightResetTask.java         # 每日零点重置
```

---

## 五、详细设计

### 5.1 实体类设计

#### LinkEntity（复制自 Project）
- 表: `t_link`（分片，shard-key=gid）
- 26 个字段，使用 `@TableName("t_link")`
- 与 Project 模块完全一致

#### Result<T>（复制自 Project）
- 统一响应: `{ code, message, data }`
- 状态码常量: 200/400/401/403/404/409/500
- 静态工厂: `success()` / `fail()`
- 实例方法: `isSuccess()`

#### GroupEntity
- 表: `t_group`（全局表，不分片）
- 字段: id, gid, name, description, sortOrder, createUserId, createTime, updateTime, delFlag

#### UserEntity
- 表: `t_user`（全局表）
- 字段: id, username, password(BCrypt加密), realName, email, role(ADMIN/USER), status, lastLoginTime, createTime, updateTime, delFlag

#### AccessLogEntity
- 表: `t_access_log`（分片，shard-key=linkGid）
- 字段: id, linkId, linkGid, shortUri, visitorId, visitorIp, referer, refererDomain, userAgent, os, osVersion, browser, browserVersion, deviceType, country, province, city, accessTime

#### AccessStatsEntity
- 表: `t_access_stats`（分片，shard-key=linkGid）
- 字段: id, linkId, linkGid, statDate, pv, uv, ipCount, scanPv, createTime, updateTime

#### OperationLogEntity
- 表: `t_operation_log`（全局表）
- 字段: id, userId, username, operationType, targetType, targetId, targetName, detail, requestIp, createTime

### 5.2 Mapper 层

所有 Mapper 继承 `BaseMapper<T>`，与 Project 模块的 `LinkMapper` 风格一致。

分片表（t_link, t_access_log, t_access_stats）的 Mapper 查询时必须传入 `gid` 作为分片键，确保 ShardingSphere 路由到正确分片。

### 5.3 工具类

#### JwtUtil
- 依赖: jjwt 0.9.1
- 方法: `generateToken(userId, username, role)`, `parseToken(token)`
- 有效期: 24 小时
- 密钥: 从 `application.yml` 的 `jwt.secret` 读取

#### UserAgentUtil
- 依赖: Hutool 的 `cn.hutool.http.useragent.UserAgentUtil`
- 解析 User-Agent → os, osVersion, browser, browserVersion, deviceType(PC/Mobile/Tablet)

#### GeoIpUtil
- Phase1: 占位实现，固定返回 "未知"
- 后续可集成 GeoLite2 离线数据库

#### ShortLinkGenerator
- 自动生成: Redis INCR `shortlink:seq:counter` → Base62 编码 → 左补零至 6 位
- 自定义短码: 检查唯一索引 + 分布式锁防并发冲突

#### UserContext
- ThreadLocal 存储当前登录用户: userId, username, role
- 方法: `get()`, `set(UserInfo)`, `clear()`

### 5.4 Service 层

#### LinkService
| 方法 | 说明 |
|------|------|
| `createLink(dto, userId)` | 生成短码 → 写 DB → 写 Redis 缓存 → 写操作日志 |
| `updateLink(dto)` | 检查权限 → 更新 DB → 更新缓存 → 写操作日志 |
| `deleteLink(id)` | 软删除 (delFlag=1) → 删缓存 |
| `pauseLink(id)` / `enableLink(id)` | 切换 enableStatus |
| `pageQuery(dto)` | 分页 + 按 gid/status/keyword 筛选 |
| `getByShortUri(shortUri)` | 单条查询 |

权限控制: 普通用户只能操作自己创建的链接 (`createUserId`)，管理员可操作全部。

#### GroupService
| 方法 | 说明 |
|------|------|
| `createGroup(dto, userId)` | 生成 gid（G-xxx）→ 写 DB |
| `updateGroup(dto)` | 更新分组信息 |
| `deleteGroup(id)` | 软删除前检查是否有关联短链 |
| `listGroups(userId)` | 当前用户的分组列表 |

#### UserService
| 方法 | 说明 |
|------|------|
| `login(dto)` | 查用户 → BCrypt 验密 → 生成 JWT → 更新 lastLoginTime |
| `register(dto)` | 检查用户名唯一 → BCrypt 加密 → 写入 DB |

#### StatsService
| 方法 | 说明 |
|------|------|
| `getLinkStats(shortUri, range)` | 实时汇总 + 每日趋势 (7d/30d) + 地域 Top10 |
| `getGroupStats(gid, range)` | 分组聚合统计 |

统计查询数据来源：
- 实时汇总：t_link 的 total_pv/uv/ip + Redis 最新计数器值（取 max）
- 每日趋势：t_access_stats 按日期范围查询
- 地域分布：t_access_region_stats 按 PV 降序 Top10

### 5.5 Controller 层

所有接口统一返回 `Result<T>`，Swagger 注解与 Project 的 RedirectController 风格一致。

#### AuthController — `@RequestMapping("/api/v1/auth")`
| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/login` | 登录，返回 JWT | ❌ 白名单 |
| POST | `/register` | 注册 | ❌ 白名单 |

#### LinkController — `@RequestMapping("/api/v1/links")`
| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/` | 创建短链接 | ✅ |
| GET | `/` | 分页列表 (支持筛选) | ✅ |
| GET | `/{id}` | 短链详情 | ✅ |
| PUT | `/{id}` | 更新短链接 | ✅ |
| DELETE | `/{id}` | 软删除 | ✅ |
| PUT | `/{id}/pause` | 暂停 | ✅ |
| PUT | `/{id}/enable` | 启用 | ✅ |

#### GroupController — `@RequestMapping("/api/v1/groups")`
| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| POST | `/` | 创建分组 | ✅ |
| GET | `/` | 分组列表 | ✅ |
| PUT | `/{id}` | 更新分组 | ✅ |
| DELETE | `/{id}` | 删除分组 | ✅ |

#### StatsController — `@RequestMapping("/api/v1/stats")`
| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/{shortUri}` | 单链统计 (?range=7d/30d) | ✅ |
| GET | `/group/{gid}` | 分组统计 | ✅ |

#### UserController — `@RequestMapping("/api/v1/users")`
| 方法 | 路径 | 说明 | 鉴权 |
|------|------|------|------|
| GET | `/` | 用户列表 | ✅ 管理员 |
| GET | `/{id}` | 用户详情 | ✅ 管理员 |
| PUT | `/{id}/status` | 启用/禁用 | ✅ 管理员 |

### 5.6 JWT 鉴权流程

```
请求到达 → LoginInterceptor.preHandle()
  ├── 路径在白名单? → 放行
  ├── 无 Authorization 头? → 返回 401
  ├── Token 解析失败/过期? → 返回 401
  └── 解析成功 → UserContext.set(userInfo) → 放行

请求结束 → afterCompletion() → UserContext.clear()
```

白名单路径:
- `/api/v1/auth/**`
- `/swagger-ui/**`
- `/v3/api-docs/**`
- `/error`

### 5.7 Redis Stream 消费者

**AccessEventConsumer** — 消费 Project 模块发送的访问事件：

```
Redis Stream Key: shortlink:access:stream
Consumer Group:   shortlink-stats-group
Consumer:         consumer-admin-1

消费流程:
  1. XREADGROUP 批量读取 (COUNT 100, BLOCK 1000ms)
  2. 逐条处理:
     ├── UserAgentUtil.parse(userAgent) → os/browser/device
     ├── GeoIpUtil.lookup(visitorIp) → country/province/city
     ├── 解析 Referer → refererDomain
     └── 写入 t_access_log (MyBatis-Plus insert)
  3. XACK 确认消费
  4. 失败不 ACK → 消息重新投递 (At-Least-Once)
```

实现细节:
- `@PostConstruct` 启动时创建消费者组 + 启动消费线程
- 消费线程使用 daemon 线程
- 异常时 sleep 重试，不丢消息

### 5.8 定时任务

#### StatsAggregationTask — 每 5 分钟
- 扫描 Redis Set `shortlink:dirty:links` 中的 link_id
- 逐个读取 Redis 计数器 (PV/UV/IP/Region)
- UPSERT 到 t_access_stats 和 t_access_region_stats
- 更新 t_link 的 total_pv/uv/ip + today_pv/uv/ip
- 完成后从 Set 中移除

#### MidnightResetTask — 每日零点
- 重置 t_link.today_pv/uv/ip_count = 0

---

## 六、配置补充

### 6.1 pom.xml 新增依赖

```xml
<!-- JWT -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt</artifactId>
</dependency>

<!-- Swagger -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>

<!-- HTML 解析（网页标题抓取） -->
<dependency>
    <groupId>org.jsoup</groupId>
    <artifactId>jsoup</artifactId>
    <version>${jsoup.version}</version>
</dependency>

<!-- Nacos 配置中心 -->
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
</dependency>
```

### 6.2 application.yml 补充

```yaml
jwt:
  secret: shortlink-jwt-secret-key-2026
  expiration: 86400000

springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /v3/api-docs
```

### 6.3 修复 ds1 jdbc-url

```yaml
# 修复前: jdbc-url: jdbc:mysql://localhost:3306/
# 修复后:
jdbc-url: jdbc:mysql://localhost:3306/shortlink_ds1
```

---

## 七、开发顺序

```
第1批 (基础设施):  修复配置 + 复制实体 + 创建所有 Entity + Mapper
第2批 (工具类):    JwtUtil + UserAgentUtil + GeoIpUtil + ShortLinkGenerator + UserContext
第3批 (服务层):    DTO + LinkService + GroupService + UserService + StatsService
第4批 (接口层):    所有 Controller + LoginInterceptor + WebMvcConfig
第5批 (消费者):    AccessEventConsumer + 定时任务
第6批 (收尾):      Swagger 配置 + 编译验证 + 启动测试
```

---

## 八、验证清单

- [ ] `mvn clean compile -pl admin` — 编译通过
- [ ] 启动 Admin 模块，Nacos 注册成功
- [ ] Swagger UI `http://localhost:8081/swagger-ui.html` 可访问
- [ ] `POST /api/v1/auth/register` — 注册用户成功
- [ ] `POST /api/v1/auth/login` — 登录成功返回 JWT
- [ ] `POST /api/v1/links` — 带 Token 创建短链接成功，返回短码
- [ ] `GET /api/v1/links` — 分页查询正常
- [ ] `PUT /api/v1/links/{id}/pause` — 暂停成功
- [ ] 无 Token 访问接口 → 返回 401
- [ ] `GET /api/v1/stats/{shortUri}?range=7d` — 统计查询正常
- [ ] 跨模块联调：Project 跳转 → Admin 消费者收到消息 → 写入 t_access_log
