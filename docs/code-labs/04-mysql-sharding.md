# ShardingSphere 分库分表完整实现

## 0. 实现目标

业务代码只操作：

```text
t_link
```

框架自动路由到：

```text
ds0.t_link_0 ~ t_link_7
ds1.t_link_0 ~ t_link_7
```

本篇给出完整依赖、DataSource 配置和业务代码。

---

## 1. Maven 依赖

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>
        shardingsphere-jdbc-core
    </artifactId>
    <version>5.3.2</version>
</dependency>

<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>
        mybatis-plus-boot-starter
    </artifactId>
</dependency>

<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
</dependency>
```

---

## 2. YAML 连接参数

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0,ds1
      ds0:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name:
          com.mysql.cj.jdbc.Driver
        jdbc-url:
          jdbc:mysql://localhost:3306/shortlink_ds0
        username: root
        password: 123456
      ds1:
        type: com.zaxxer.hikari.HikariDataSource
        driver-class-name:
          com.mysql.cj.jdbc.Driver
        jdbc-url:
          jdbc:mysql://localhost:3306/shortlink_ds1
        username: root
        password: 123456
    props:
      sql-show: true
```

统一排除 Boot 默认 DataSource：

```yaml
spring:
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

---

## 3. 完整 DataSource 配置

```java
package com.app.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.config.algorithm.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Configuration
public class ShardingConfig {

    @Value("${spring.shardingsphere.datasource.ds0.jdbc-url}")
    private String ds0Url;

    @Value("${spring.shardingsphere.datasource.ds0.username}")
    private String ds0Username;

    @Value("${spring.shardingsphere.datasource.ds0.password}")
    private String ds0Password;

    @Value("${spring.shardingsphere.datasource.ds1.jdbc-url}")
    private String ds1Url;

    @Value("${spring.shardingsphere.datasource.ds1.username}")
    private String ds1Username;

    @Value("${spring.shardingsphere.datasource.ds1.password}")
    private String ds1Password;

    @Bean
    @Primary
    public DataSource dataSource() throws SQLException {
        Map<String, DataSource> datasourceMap =
                new HashMap<>();
        datasourceMap.put("ds0", createHikari(
                ds0Url, ds0Username, ds0Password));
        datasourceMap.put("ds1", createHikari(
                ds1Url, ds1Username, ds1Password));

        ShardingRuleConfiguration rules =
                new ShardingRuleConfiguration();

        ShardingTableRuleConfiguration link =
                new ShardingTableRuleConfiguration(
                        "t_link",
                        "ds$->{0..1}.t_link_$->{0..7}");

        link.setDatabaseShardingStrategy(
                new StandardShardingStrategyConfiguration(
                        "gid", "db-hash"));
        link.setTableShardingStrategy(
                new StandardShardingStrategyConfiguration(
                        "gid", "table-hash"));

        rules.getTables().add(link);

        Properties dbProps = new Properties();
        dbProps.setProperty("sharding-count", "2");
        rules.getShardingAlgorithms().put(
                "db-hash",
                new AlgorithmConfiguration(
                        "HASH_MOD", dbProps));

        Properties tableProps = new Properties();
        tableProps.setProperty("sharding-count", "8");
        rules.getShardingAlgorithms().put(
                "table-hash",
                new AlgorithmConfiguration(
                        "HASH_MOD", tableProps));

        Properties props = new Properties();
        props.setProperty("sql-show", "true");

        return ShardingSphereDataSourceFactory
                .createDataSource(
                        datasourceMap,
                        java.util.Collections.singleton(rules),
                        props);
    }

    private HikariDataSource createHikari(
            String url,
            String username,
            String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName(
                "com.mysql.cj.jdbc.Driver");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        return ds;
    }
}
```

---

## 4. 每个配置对象作用

### 4.1 HikariDataSource

```java
HikariDataSource ds = new HikariDataSource();
ds.setJdbcUrl(url);
```

连接一个真实 MySQL 库。

### 4.2 datasourceMap

```java
Map<String, DataSource> datasourceMap;
```

逻辑名称：

```text
ds0
ds1
```

之后真实节点表达式使用这些名字。

### 4.3 actual-data-nodes

```java
"ds$->{0..1}.t_link_$->{0..7}"
```

展开为：

```text
ds0.t_link_0
...
ds0.t_link_7
ds1.t_link_0
...
ds1.t_link_7
```

### 4.4 分片键

```java
new StandardShardingStrategyConfiguration(
        "gid", "db-hash")
```

表示从 SQL 参数或实体字段中找到 `gid`。

### 4.5 数据库分片

```java
dbProps.setProperty("sharding-count", "2");
```

概念：

```text
databaseIndex = hash(gid) % 2
```

### 4.6 表分片

```java
tableProps.setProperty("sharding-count", "8");
```

概念：

```text
tableIndex = hash(gid) % 8
```

---

## 5. 实体代码

```java
@Data
@TableName("t_link")
public class LinkEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String shortUri;
    private String originUrl;
    private String gid;
    private Long createUserId;
    private Integer enableStatus;

    @TableLogic
    private Integer delFlag;
}
```

`@TableName("t_link")` 是逻辑表。

业务代码不写 `t_link_5`。

---

## 6. Mapper 代码

```java
@Mapper
public interface LinkMapper
        extends BaseMapper<LinkEntity> {
}
```

不需要写：

```text
根据 gid 选择数据库
根据 gid 选择表
```

ShardingSphere 在 JDBC 层处理。

---

## 7. 创建短链代码

```java
@Service
public class LinkCreateService {

    private final LinkMapper linkMapper;

    public LinkCreateService(LinkMapper linkMapper) {
        this.linkMapper = linkMapper;
    }

    public LinkEntity create(
            String originUrl,
            Long userId,
            String shortUri) {
        LinkEntity entity = new LinkEntity();
        entity.setOriginUrl(originUrl);
        entity.setShortUri(shortUri);
        entity.setGid("u_" + userId);
        entity.setCreateUserId(userId);
        entity.setEnableStatus(1);
        entity.setDelFlag(0);

        linkMapper.insert(entity);
        return entity;
    }
}
```

关键：

```java
entity.setGid("u_" + userId);
```

如果缺少 `gid`，ShardingSphere 无法确定分片。

---

## 8. 查询短链代码

```java
public LinkEntity findByShortUri(String shortUri) {
    LambdaQueryWrapper<LinkEntity> wrapper =
            Wrappers.lambdaQuery();
    wrapper.eq(LinkEntity::getShortUri, shortUri);

    List<LinkEntity> list =
            linkMapper.selectList(wrapper);

    return list.isEmpty() ? null : list.get(0);
}
```

问题：

```text
short_uri 不是分片键
SQL 会广播到 16 个分片
```

如果缓存未命中，这个查询成本较高。

---

## 9. 带分片键查询

```java
public List<LinkEntity> findByUser(Long userId) {
    LambdaQueryWrapper<LinkEntity> wrapper =
            Wrappers.lambdaQuery();
    wrapper.eq(
            LinkEntity::getGid, "u_" + userId);
    return linkMapper.selectList(wrapper);
}
```

可以直接定位到一个库和一个表。

---

## 10. 日志表分片

日志表也需要分片：

```java
ShardingTableRuleConfiguration log =
        new ShardingTableRuleConfiguration(
                "t_access_log",
                "ds$->{0..1}.t_access_log_$->{0..7}");

log.setDatabaseShardingStrategy(
        new StandardShardingStrategyConfiguration(
                "link_gid", "db-hash"));

log.setTableShardingStrategy(
        new StandardShardingStrategyConfiguration(
                "link_gid", "table-hash"));
```

消费者写入时必须提供：

```java
entity.setLinkGid(string(map, "link_gid"));
```

---

## 11. 数据库脚本

每个库建 8 张表：

```sql
CREATE TABLE t_link_0 (
    id BIGINT NOT NULL AUTO_INCREMENT,
    short_uri VARCHAR(32) NOT NULL,
    origin_url VARCHAR(2048) NOT NULL,
    gid VARCHAR(32) NOT NULL,
    create_user_id BIGINT NOT NULL,
    enable_status TINYINT NOT NULL DEFAULT 1,
    del_flag TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_uri (short_uri),
    KEY idx_gid (gid)
);

CREATE TABLE t_link_1 LIKE t_link_0;
CREATE TABLE t_link_2 LIKE t_link_0;
CREATE TABLE t_link_3 LIKE t_link_0;
CREATE TABLE t_link_4 LIKE t_link_0;
CREATE TABLE t_link_5 LIKE t_link_0;
CREATE TABLE t_link_6 LIKE t_link_0;
CREATE TABLE t_link_7 LIKE t_link_0;
```

`short_uri` 唯一索引只在单张物理表内生效。
全局短码唯一性还要靠生成策略、分布式锁或单独的全局路由。

---

## 12. sql-show 日志怎么看

插入：

```text
Logic SQL:
INSERT INTO t_link (...) VALUES (...)

Actual SQL:
ds1 ::
INSERT INTO t_link_5 (...) VALUES (...)
```

如果日志显示广播 16 条 SQL：

```text
说明 SQL 没带可识别分片键
```

---

## 13. MyBatis-Plus 配合

分页插件：

```java
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor() {
    MybatisPlusInterceptor interceptor =
            new MybatisPlusInterceptor();
    interceptor.addInnerInterceptor(
            new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
}
```

逻辑删除：

```java
@TableLogic
private Integer delFlag;
```

自动填充：

```java
@TableField(fill = FieldFill.INSERT)
private LocalDateTime createTime;
```

```java
@Component
public class MyMetaObjectHandler
        implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        strictInsertFill(
                metaObject,
                "createTime",
                LocalDateTime.class,
                LocalDateTime.now());
    }
}
```

---

## 14. 从零实现顺序

```text
1. 建 ds0、ds1
2. 每个库建 8 张同结构表
3. 引 shardingsphere-jdbc-core 和 MySQL 驱动
4. 排除默认 DataSource
5. 创建两个 HikariDataSource
6. 创建 ShardingSphere DataSource
7. 配置逻辑表、真实节点、分片键、算法
8. 开 sql-show
9. 用普通 MyBatis-Plus Mapper 插入
10. 查看实际 SQL 是否路由
11. 测试不带分片键的查询
```

---

## 15. 重要限制

```text
跨分片 join 成本高
不带分片键会广播
跨分片分页需要归并
自增 ID 在多个物理表之间可能重复
跨分片事务复杂
```

因此分库分表不能只看“数据量大”，还要看查询模式。

---

## 16. 本篇自检

```text
[ ] 能创建两个 HikariDataSource
[ ] 能看懂 actual-data-nodes
[ ] 能配置数据库和表两个分片算法
[ ] 能写出带 gid 的插入
[ ] 能解释 short_uri 查询为什么广播
[ ] 能用 sql-show 验证实际表
[ ] 知道分片后的自增 ID 问题
```
