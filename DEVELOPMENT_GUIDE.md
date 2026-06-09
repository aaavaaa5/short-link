# 短链接服务 — 开发指南

> 2026-06-08 | 从零开始，按步骤推进

---

## 一、当前状态

```
shortlink/
├── pom.xml                    ✅ 父 POM，依赖版本管理完成
├── link.sql                   ✅ 数据库初始化脚本完成
│
├── gateway/                   ⬜ 空壳（只有 App.java）
├── admin/                     ⬜ 空壳（只有 App.java）
├── project/                   ⬜ 空壳（只有 App.java）
└── aggregation/               ⬜ 空壳（预留，暂不开发）
```

**已有的东西**：
- Maven 多模块骨架
- 父 POM 统一管理所有依赖版本
- 3 份设计文档（不参与 Git）
- 数据库 DDL 脚本

**没有的东西**：
- 任何业务代码
- 配置文件（application.yml）
- 实体类、Mapper、Service、Controller

---

## 二、环境准备

开发前确保以下环境已就绪：

| 组件 | 版本 | 端口 | 用途 |
|------|------|------|------|
| JDK | 17 | — | 编译运行 |
| MySQL | 8.0+ | 3306 | 持久化 |
| Redis | 7.0+ | 6379 | 缓存/锁/计数器/消息队列 |
| Nacos | 2.x | 8848 | 服务注册 + 配置中心 |
| Maven | 3.8+ | — | 构建 |

**启动顺序**：MySQL → Redis → Nacos → 各微服务模块

---

## 三、开发顺序（重要）

```
第1步: 基础设施 (1天)
  ├── 执行 link.sql 建库建表
  ├── 补充各模块 application.yml
  └── 配置 ShardingSphere 数据源

第2步: Project 模块 (2-3天)  ← 核心，最先做
  ├── 实体类 Entity
  ├── Mapper (MyBatis-Plus)
  ├── 短链跳转 Service（多级缓存）
  ├── 短链跳转 Controller
  └── Redis Stream 消息生产者

第3步: Admin 模块 (2-3天)
  ├── 实体类 + Mapper
  ├── 短链 CRUD Service
  ├── 短链 CRUD Controller
  ├── 用户注册/登录 (JWT)
  ├── Redis Stream 消息消费者
  └── 统计查询接口

第4步: Gateway 模块 (1天)
  ├── 路由配置
  ├── JWT 鉴权过滤器
  ├── Redis 滑动窗口限流
  └── CORS 配置

第5步: 联调测试 (1-2天)
  ├── 全链路测试
  └── 修复 bug
```

**为什么按这个顺序？**

| 顺序 | 原因 |
|------|------|
| Project 最先做 | 它是最核心的模块（跳转），不依赖 Admin，可以独立启动和测试 |
| Admin 第二 | 依赖 Project 的一些能力，同时也消费 Redis Stream |
| Gateway 最后 | 它只是代理转发，需要 Admin 和 Project 先能正常工作 |

---

## 四、第 1 步：基础设施搭建

### 4.1 执行 DDL

```bash
mysql -u root -p < resources/database/link.sql
```

### 4.2 Project 模块最小配置文件

创建 `project/src/main/resources/application.yml`：

```yaml
server:
  port: 8082

spring:
  application:
    name: shortlink-project

  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: shortlink
      config:
        server-addr: 127.0.0.1:8848
        namespace: shortlink

  # ShardingSphere 数据源（读写 ds_0，ds_1 两个库）
  # 这一步配置好后，MyBatis-Plus 操作 t_link 会自动路由到正确分片
  shardingsphere:
    datasource:
      names: ds0, ds1
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://localhost:3306/shortlink_ds0
        username: root
        password: ${DB_PASSWORD}
      ds1:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name: com.mysql.cj.jdbc.Driver
        jdbc-url: jdbc:mysql://localhost:3306/shortlink_ds1
        username: root
        password: ${DB_PASSWORD}
    rules:
      sharding:
        tables:
          t_link:
            actual-data-nodes: ds$->{0..1}.t_link_$->{0..7}
            database-strategy:
              standard:
                sharding-column: gid
                sharding-algorithm-name: db-hash
            table-strategy:
              standard:
                sharding-column: gid
                sharding-algorithm-name: tbl-mod
        sharding-algorithms:
          db-hash:
            type: HASH_MOD
            props:
              sharding-count: 2
          tbl-mod:
            type: HASH_MOD
            props:
              sharding-count: 8
    props:
      sql-show: true   # 开发时打印 SQL，上线关闭

  # Redis 连接
  data:
    redis:
      host: localhost
      port: 6379
```

### 4.3 Admin 模块最小配置文件

创建 `admin/src/main/resources/application.yml`，与上面类似，改 `server.port: 8081` 和 `spring.application.name: shortlink-admin`。

### 4.4 Gateway 模块最小配置文件

创建 `gateway/src/main/resources/application.yml`：

```yaml
server:
  port: 8080

spring:
  application:
    name: shortlink-gateway
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: shortlink
    gateway:
      routes:
        - id: shortlink-project
          uri: lb://shortlink-project
          predicates:
            - Path=/s/**
        - id: shortlink-admin
          uri: lb://shortlink-admin
          predicates:
            - Path=/api/**
```

---

## 五、第 2 步：Project 模块开发

### 5.1 目录结构

```
project/src/main/java/com/app/
├── ProjectApplication.java          (已有)
├── entity/
│   └── LinkEntity.java             短链接实体
├── mapper/
│   └── LinkMapper.java             MyBatis-Plus Mapper
├── service/
│   ├── RedirectService.java        跳转核心服务
│   └── CacheService.java          多级缓存服务
├── controller/
│   └── RedirectController.java     跳转接口
├── config/
│   ├── CaffeineConfig.java         本地缓存配置
│   ├── RedisConfig.java           Redis 配置
│   └── BloomFilterConfig.java     布隆过滤器配置
└── mq/
    └── AccessEventProducer.java    Redis Stream 消息生产者
```

### 5.2 开发顺序

```
1. LinkEntity         ← 对应 t_link 表，加 @TableName + MyBatis-Plus 注解
2. LinkMapper         ← 继承 BaseMapper<LinkEntity>，只需一个 findByShortUri 方法
3. CacheService       ← L1 Caffeine + L2 Redis + 布隆过滤器
4. RedirectService    ← 核心：查缓存 → 校验过期/状态 → 返回原始URL
5. AccessEventProducer ← XADD 发送访问事件到 Redis Stream
6. RedirectController ← GET /s/{shortUri} → 302 重定向
```

### 5.3 关键代码骨架

**LinkEntity.java**：

```java
@Data
@TableName("t_link")  // ShardingSphere 自动路由到正确分片
public class LinkEntity {
    private Long id;
    private String shortUri;
    private String originUrl;
    private String gid;
    private String title;
    private String describe;
    private Integer redirectType;
    private Integer expireType;
    private LocalDateTime expireTime;
    private Long totalPv;
    private Long totalUv;
    private Long totalIpCount;
    private Integer todayPv;
    private Integer todayUv;
    private Integer todayIpCount;
    private Integer enableStatus;
    private Integer createdType;
    private Long createUserId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer delFlag;
}
```

**RedirectController.java**：

```java
@RestController
public class RedirectController {

    @Autowired
    private RedirectService redirectService;

    @GetMapping("/s/{shortUri}")
    public ResponseEntity<Void> redirect(
            @PathVariable String shortUri,
            HttpServletRequest request) {

        // 1. 查缓存获取原始URL
        String originUrl = redirectService.getOriginUrl(shortUri);

        if (originUrl == null) {
            return ResponseEntity.notFound().build();
        }

        // 2. 构建 302 响应
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(originUrl));

        // 3. 注意：这里不能用 @Async 直接发消息，因为 Project 不依赖 MQ 消费
        //    方案 A: 在 Project 里 XADD 到 Redis Stream
        //    方案 B: 通过 Feign 调用 Admin 记录
        //    推荐方案 A，Project 只发消息，不关心消费
        redirectService.recordAccess(shortUri, request);

        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }
}
```

**验证方式**：
- 启动 Nacos + Redis + MySQL
- 启动 Project 应用
- 先在 MySQL 手动插入一条 t_link 测试数据
- 浏览器访问 `http://localhost:8082/s/{shortUri}`
- 应该 302 跳转到目标 URL

---

## 六、第 3 步：Admin 模块开发

### 6.1 目录结构

```
admin/src/main/java/com/app/
├── AdminApplication.java
├── entity/
│   ├── LinkEntity.java
│   ├── GroupEntity.java
│   └── UserEntity.java
├── mapper/
│   ├── LinkMapper.java
│   ├── GroupMapper.java
│   └── UserMapper.java
├── service/
│   ├── LinkService.java          短链 CRUD
│   ├── GroupService.java         分组管理
│   ├── UserService.java          用户管理
│   └── StatsService.java         统计查询
├── controller/
│   ├── LinkController.java       /api/v1/links
│   ├── GroupController.java      /api/v1/groups
│   ├── UserController.java       /api/v1/users
│   ├── AuthController.java       /api/v1/auth (登录/注册)
│   └── StatsController.java      /api/v1/stats
├── config/
│   └── SecurityConfig.java       JWT 配置
├── mq/
│   └── AccessEventConsumer.java  Redis Stream 消费者
└── util/
    ├── JwtUtil.java              JWT 工具类
    ├── UserAgentUtil.java        UA 解析
    └── GeoIpUtil.java            GeoIP 解析
```

### 6.2 开发顺序

```
1. Entity + Mapper         ← LinkEntity 和 Project 里的一样
2. JwtUtil                 ← 生成/解析 JWT Token
3. AuthController          ← POST /login, POST /register
4. LinkService + LinkController  ← CRUD
5. GroupService + GroupController ← 分组管理
6. AccessEventConsumer     ← XREADGROUP 消费 + UA解析 + GeoIP + 写 t_access_log
7. StatsService + StatsController ← 统计查询（PV/UV/IP/地区/趋势）
```

### 6.3 Redis Stream 消费者关键逻辑

```java
@Component
public class AccessEventConsumer {

    private static final String STREAM_KEY = "shortlink:access:stream";
    private static final String GROUP = "shortlink-stats-group";
    private static final String CONSUMER = "consumer-1";

    @PostConstruct
    public void start() {
        // 创建消费者组（幂等，已存在则忽略）
        try {
            stringRedisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception e) {
            // Group already exists
        }

        // 启动消费线程
        new Thread(this::consume).start();
    }

    private void consume() {
        while (true) {
            // 批量读取消息，阻塞1秒
            List<MapRecord<String, Object, Object>> records =
                stringRedisTemplate.opsForStream()
                    .read(Consumer.from(GROUP, CONSUMER),
                          StreamReadOptions.empty().count(100).block(Duration.ofSeconds(1)),
                          StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

            for (MapRecord<String, Object, Object> record : records) {
                try {
                    processRecord(record);  // UA解析 → GeoIP → 写 t_access_log
                    stringRedisTemplate.opsForStream()
                        .acknowledge(STREAM_KEY, GROUP, record.getId().getValue());
                } catch (Exception e) {
                    log.error("消费失败: {}", record.getId(), e);
                    // 不 ACK，消息会被重新投递
                }
            }
        }
    }

    private void processRecord(MapRecord<String, Object, Object> record) {
        Map<String, Object> map = record.getValue();
        // 1. 从消息中提取字段
        // 2. UserAgentUtil.parse(userAgent) → os, browser, device
        // 3. GeoIpUtil.lookup(ip) → country, province, city
        // 4. 构建 AccessLogEntity
        // 5. accessLogMapper.insert(entity)
    }
}
```

---

## 七、第 4 步：Gateway 模块开发

### 7.1 核心组件

```
gateway/src/main/java/com/app/
├── GatewayApplication.java
├── filter/
│   ├── AuthFilter.java            JWT 鉴权过滤器
│   └── RateLimiterFilter.java     Redis 滑动窗口限流过滤器
└── config/
    └── CorsConfig.java           跨域配置
```

### 7.2 路由规则

| 路径 | 转发到 | 鉴权 | 限流 |
|------|--------|------|------|
| `/s/**` | `lb://shortlink-project` | ❌ 不鉴权 | ✅ 按IP限流 |
| `/api/v1/auth/**` | `lb://shortlink-admin` | ❌ 登录注册是白名单 | ✅ |
| `/api/v1/**`（其他） | `lb://shortlink-admin` | ✅ JWT 校验 | ✅ |

### 7.3 Redis 滑动窗口限流 Lua 脚本

```java
// 在 RateLimiterFilter 中使用
private static final String LUA_SCRIPT = """
    local key = KEYS[1]
    local limit = tonumber(ARGV[1])
    local window = tonumber(ARGV[2])
    local now = redis.call('TIME')[1]
    redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
    local count = redis.call('ZCARD', key)
    if count >= limit then
        return 0
    end
    redis.call('ZADD', key, now, now .. '-' .. math.random())
    redis.call('EXPIRE', key, window + 1)
    return 1
    """;

// 执行:
// Long result = redisTemplate.execute(
//     new DefaultRedisScript<>(LUA_SCRIPT, Long.class),
//     List.of("ratelimit:ip:" + ip),
//     "100", "1"
// );
// result == 0 → 限流, 返回 429
```

---

## 八、第 5 步：联调测试

### 8.1 启动顺序

```bash
# 1. 基础设施
mysql  (确保已启动，执行过 link.sql)
redis-server
nacos/bin/startup.sh -m standalone

# 2. 微服务（三个终端）
# Project:
cd project && mvn spring-boot:run

# Admin:
cd admin && mvn spring-boot:run

# Gateway:
cd gateway && mvn spring-boot:run
```

### 8.2 全链路测试

```bash
# 1. 创建短链接（Admin）
curl -X POST http://localhost:8080/api/v1/links \
  -H "Authorization: Bearer {token}" \
  -H "Content-Type: application/json" \
  -d '{"originUrl":"https://www.baidu.com","gid":"default"}'

# 预期返回: { "shortUrl": "http://s.xx.com/0003d7", ... }

# 2. 访问短链接（Project）
curl -v http://localhost:8080/s/0003d7

# 预期: HTTP 302, Location: https://www.baidu.com

# 3. 查看统计（Admin）
curl http://localhost:8080/api/v1/stats/0003d7?range=7d \
  -H "Authorization: Bearer {token}"

# 预期返回: { totalPv: 1, todayPv: 1, trend: [...], regionTop10: [...] }
```

---

## 九、开发 checklist

### 基础设施
- [ ] `link.sql` 执行成功，67 张物理表创建完成
- [ ] Nacos 启动，三个服务都注册成功
- [ ] Redis 连接正常
- [ ] ShardingSphere 分片路由正常（插入一条数据验证）

### Project 模块
- [ ] `GET /s/{shortUri}` → 302 跳转到原始 URL
- [ ] L1 Caffeine 本地缓存生效（第二次访问 < 1ms）
- [ ] L2 Redis 缓存生效
- [ ] 布隆过滤器拦截不存在的短码（直接 404，不查库）
- [ ] 过期链接返回过期提示页
- [ ] 停用链接返回停用提示页
- [ ] 每次访问后 Redis INCR(PV) + PFADD(UV) + PFADD(IP)
- [ ] 每次访问后 XADD 消息到 Redis Stream

### Admin 模块
- [ ] `POST /api/v1/auth/login` → 返回 JWT Token
- [ ] `POST /api/v1/links` → 创建短链接
- [ ] `GET /api/v1/links` → 分页列表 + 筛选
- [ ] `PUT /api/v1/links/{id}` → 修改短链接
- [ ] `DELETE /api/v1/links/{id}` → 软删除
- [ ] `GET /api/v1/stats/{shortUri}` → 统计数据
- [ ] Redis Stream 消费者正常消费，写入 t_access_log
- [ ] 定时任务每 5 分钟聚合统计

### Gateway 模块
- [ ] `/s/**` → 正确转发到 Project
- [ ] `/api/**` → 正确转发到 Admin
- [ ] JWT 鉴权过滤器（无 Token → 401）
- [ ] 限流过滤器（超限 → 429）
- [ ] CORS 正常

---

> **开始写代码吧，从 Project 模块的 LinkEntity 开始！**
