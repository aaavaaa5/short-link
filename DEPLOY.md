# 短链接服务 — 部署指南

> 版本：v1.0 | 最后更新：2026-06-09

---

## 一、环境要求

| 组件 | 版本要求 | 说明 |
|------|---------|------|
| JDK | 17+ | 编译和运行 |
| Maven | 3.6+ | 后端构建 |
| Node.js | 18+ | 前端构建 |
| MySQL | 8.0+ | 主数据库，需创建 2 个逻辑库 |
| Redis | 6.0+ | 缓存 + 限流 + 消息队列（需 Stream 支持） |
| Nacos | 2.x | 服务注册与配置中心 |

**服务器最低配置**：2 核 4G（开发环境）/ 4 核 8G（生产环境）

---

## 二、中间件安装与配置

### 2.1 MySQL

```bash
# 1. 安装 MySQL 8.0（略）

# 2. 创建数据库
mysql -u root -p < resources/database/link.sql
```

执行 `resources/database/link.sql` 会自动创建：
- `shortlink_ds0`：分片库 0，包含 `t_link_0~7`、`t_access_log_0~7`、`t_access_stats_0~7`
- `shortlink_ds1`：分片库 1，同上结构

**验证**：
```sql
-- 检查分片表是否全部创建
SELECT TABLE_NAME FROM information_schema.TABLES
WHERE TABLE_SCHEMA IN ('shortlink_ds0', 'shortlink_ds1')
ORDER BY TABLE_SCHEMA, TABLE_NAME;
-- 应看到 48 张分片表
```

### 2.2 Redis

```bash
# 1. 安装 Redis 6.0+（略）

# 2. 确认 Stream 支持
redis-cli
> XADD test:stream * msg hello
> XREAD STREAMS test:stream 0
# 能正常读写即表示支持 Stream

# 3. 建议配置（redis.conf）
maxmemory 512mb
maxmemory-policy allkeys-lru
```

**无需密码**（开发环境）。生产环境请设置 `requirepass` 并在应用配置中添加密码。

### 2.3 Nacos

```bash
# 1. 下载 Nacos 2.x
wget https://github.com/alibaba/nacos/releases/download/2.2.3/nacos-server-2.2.3.zip

# 2. 解压并启动（单机模式）
unzip nacos-server-2.2.3.zip
cd nacos/bin
# Windows
startup.cmd -m standalone
# Linux/Mac
sh startup.sh -m standalone

# 3. 创建命名空间
# 访问 http://localhost:8848/nacos
# 默认账号密码：nacos/nacos
# 命名空间管理 → 新建命名空间：
#   命名空间名: shortlink
#   命名空间ID: shortlink
```

---

## 三、后端部署

### 3.1 拉取代码

```bash
git clone <your-repo-url>
cd shortlink
```

### 3.2 检查配置

部署前确认以下配置项与实际环境一致：

**admin 模块** (`admin/src/main/resources/application.yml`)：
```yaml
# MySQL 连接（两个分片库）
spring.shardingsphere.datasource.ds0.jdbc-url: jdbc:mysql://localhost:3306/shortlink_ds0
spring.shardingsphere.datasource.ds0.username: root
spring.shardingsphere.datasource.ds0.password: 123456

spring.shardingsphere.datasource.ds1.jdbc-url: jdbc:mysql://localhost:3306/shortlink_ds1
spring.shardingsphere.datasource.ds1.username: root
spring.shardingsphere.datasource.ds1.password: 123456

# Redis
spring.data.redis.host: localhost
spring.data.redis.port: 6379

# Nacos
spring.cloud.nacos.discovery.server-addr: 127.0.0.1:8848

# 短链接域名前缀（指向 Gateway）
shortlink.domain: http://localhost:9000/s

# JWT 密钥（★ 生产环境必须修改！）
shortlink.jwt.secret: shortlink-admin-secret-key-min-256-bits!!
```

**project 模块** (`project/src/main/resources/application.yml`)：
```yaml
# 同上 MySQL / Redis / Nacos 配置

# 布隆过滤器
shortlink.bloom-filter.size: 33554432     # Bitmap 大小
shortlink.bloom-filter.hash-count: 3       # 哈希函数数量
```

**gateway 模块** (`gateway/src/main/resources/application.yml`)：
```yaml
# Redis（限流器依赖）
spring.data.redis.host: localhost
spring.data.redis.port: 6379

# Nacos
spring.cloud.nacos.discovery.server-addr: 127.0.0.1:8848
```

### 3.3 编译打包

```bash
# 在项目根目录执行
mvn clean package -DskipTests

# 打包产物位置：
# gateway/target/gateway-1.0-SNAPSHOT.jar
# admin/target/admin-1.0-SNAPSHOT.jar
# project/target/project-1.0-SNAPSHOT.jar
```

### 3.4 启动服务（严格按此顺序）

```bash
# ① 先启动 Nacos（已完成）

# ② 启动 project 模块（跳转服务）
cd project
mvn spring-boot:run
# 或 java -jar target/project-1.0-SNAPSHOT.jar &
# 端口: 8083

# ③ 启动 admin 模块（管理后台）
cd ../admin
mvn spring-boot:run
# 或 java -jar target/admin-1.0-SNAPSHOT.jar &
# 端口: 8081

# ④ 最后启动 gateway（网关）
cd ../gateway
mvn spring-boot:run
# 或 java -jar target/gateway-1.0-SNAPSHOT.jar &
# 端口: 9000
```

**启动验证**：
```bash
# 检查 Nacos 注册
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=shortlink-project
# 应返回 project 实例信息

curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=shortlink-admin
# 应返回 admin 实例信息

# 检查网关路由
curl http://localhost:9000/api/auth/login -X POST \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# 应返回 JWT Token
```

### 3.5 生产环境启动（后台运行）

```bash
# 使用 nohup 或 systemd 管理
nohup java -jar -Xms512m -Xmx1024m \
  -Dspring.profiles.active=prod \
  project-1.0-SNAPSHOT.jar > project.log 2>&1 &

nohup java -jar -Xms512m -Xmx1024m \
  -Dspring.profiles.active=prod \
  admin-1.0-SNAPSHOT.jar > admin.log 2>&1 &

nohup java -jar -Xms256m -Xmx512m \
  -Dspring.profiles.active=prod \
  gateway-1.0-SNAPSHOT.jar > gateway.log 2>&1 &
```

---

## 四、前端部署

### 4.1 开发环境

```bash
cd shortlink-fornt

# 安装依赖
npm install

# 启动开发服务器（端口 3000，自动代理 /api 到 localhost:9000）
npm run dev
```

开发环境下的代理配置 (`vite.config.ts`)：
```typescript
server: {
  port: 3000,
  proxy: {
    '/api': {
      target: 'http://localhost:9000',  // Gateway 地址
      changeOrigin: true,
    },
  },
}
```

### 4.2 生产构建

```bash
cd shortlink-fornt

npm install
npm run build
# 产物在 dist/ 目录
```

### 4.3 Nginx 部署（生产环境）

```nginx
server {
    listen 80;
    server_name your-domain.com;

    # 前端静态资源
    root /var/www/shortlink/dist;
    index index.html;

    # SPA 路由：所有非文件请求回退到 index.html
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API 请求反向代理到 Gateway
    location /api/ {
        proxy_pass http://127.0.0.1:9000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 短链接跳转反向代理到 Gateway
    location /s/ {
        proxy_pass http://127.0.0.1:9000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

```bash
# 部署前端文件
cp -r shortlink-fornt/dist/* /var/www/shortlink/dist/

# 重载 Nginx
nginx -s reload
```

---

## 五、快速部署检查清单

| 检查项 | 命令/方法 | 预期结果 |
|--------|----------|---------|
| MySQL 运行 | `mysql -u root -p -e "SELECT 1"` | 输出 `1` |
| 数据库存在 | `SHOW DATABASES LIKE 'shortlink_ds%';` | 显示 `shortlink_ds0`, `shortlink_ds1` |
| 分片表齐全 | `SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA IN ('shortlink_ds0','shortlink_ds1') AND TABLE_NAME LIKE 't_link_%';` | 16（2库×8表） |
| Redis 运行 | `redis-cli PING` | `PONG` |
| Redis Stream | `redis-cli XADD test:stream * msg hello` | 返回消息 ID |
| Nacos 运行 | `curl http://localhost:8848/nacos/v1/console/health/readiness` | `ok` |
| project 注册 | Nacos 控制台 → 服务列表 | `shortlink-project` 在线 |
| admin 注册 | Nacos 控制台 → 服务列表 | `shortlink-admin` 在线 |
| 注册接口 | `curl -X POST http://localhost:9000/api/auth/register -H "Content-Type: application/json" -d '{"username":"test","password":"123456","realName":"测试"}'` | `code:200` |
| 登录接口 | `curl -X POST http://localhost:9000/api/auth/login -H "Content-Type: application/json" -d '{"username":"test","password":"123456"}'` | 返回 Token |
| 前端页面 | 浏览器访问 `http://localhost` | 显示登录页 |

---

## 六、常见问题

### Q1: 启动报 `No suitable driver` / H2 错误

**原因**：ShardingSphere 5.x 默认 standalone 模式需要 H2 作为治理元数据存储。

**解决**：确保 `pom.xml` 中 `shardingsphere-jdbc-core` 依赖**未排除** H2：

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc-core</artifactId>
    <!-- 不要排除 h2 -->
</dependency>
```

### Q2: 短链接跳转返回 404

**排查步骤**：

```bash
# 1. 确认 project 模块已注册到 Nacos
curl http://localhost:8848/nacos/v1/ns/instance/list?serviceName=shortlink-project

# 2. 确认短链接存在于数据库
mysql -u root -p -e "SELECT id, short_uri, origin_url FROM shortlink_ds0.t_link_0 WHERE short_uri='你的短码' UNION SELECT id, short_uri, origin_url FROM shortlink_ds1.t_link_0 WHERE short_uri='你的短码';"

# 3. 确认 Redis 缓存和布隆过滤器正常
redis-cli KEYS "shortlink:*"

# 4. 确认 Gateway 路由匹配
# 访问 http://localhost:9000/s/你的短码
# Gateway 日志应显示路由到 shortlink-project
```

### Q3: 统计接口返回 500

**常见原因**：`t_access_stats` 表的 `stat_date` 列名与 MyBatis-Plus 自动映射不匹配。

确认 `AccessStatsEntity.java` 中有正确的注解：
```java
@TableField("stat_date")  // 必须显式指定，因为字段名是 statsDate
private LocalDate statsDate;
```

### Q4: 前端请求 API 404

**排查**：
```bash
# 确认 Gateway 正在监听 9000 端口
netstat -ano | grep 9000

# 确认 admin 模块正在运行
netstat -ano | grep 8081

# 前端开发模式下确认代理配置正确
# vite.config.ts → server.proxy['/api'].target = 'http://localhost:9000'
```

### Q5: Nacos 连接失败

**常见原因**：
- Nacos 未以 standalone 模式启动
- 命名空间 `shortlink` 未创建
- 防火墙阻止 8848 端口

**解决**：
```bash
# 确认 Nacos 运行
curl http://localhost:8848/nacos/

# 检查应用日志中的 Nacos 连接信息
tail -f admin.log | grep nacos
```

---

## 七、生产环境建议

### 7.1 安全加固

```yaml
# ★ 必须修改的配置
shortlink.jwt.secret: <生成一个 256 位以上的随机密钥>

# Redis 密码
spring.data.redis.password: <强密码>

# MySQL 密码
spring.shardingsphere.datasource.ds0.password: <强密码>
spring.shardingsphere.datasource.ds1.password: <强密码>
```

### 7.2 性能优化

```yaml
# Gateway 限流按需调整
redis-rate-limiter.replenishRate: 500   # 生产环境可适当调高
redis-rate-limiter.burstCapacity: 1000

# 布隆过滤器规模
shortlink.bloom-filter.size: 134217728  # 2^27 ≈ 16 MB，支持更大规模
```

### 7.3 高可用

| 组件 | 高可用方案 |
|------|-----------|
| Nacos | 集群模式（3 节点） |
| MySQL | 主从复制 + 读写分离 |
| Redis | 哨兵模式 / Cluster |
| 应用 | 多实例 + Nacos 负载均衡 |
| Gateway | 多实例 + Nginx 反向代理 |

### 7.4 监控建议

- Spring Boot Actuator + Prometheus + Grafana
- Redis 监控：RedisInsight / Prometheus Redis Exporter
- MySQL 监控：Percona Monitoring and Management
- 日志收集：ELK / Loki
