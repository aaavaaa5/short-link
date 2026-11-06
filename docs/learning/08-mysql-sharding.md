# 08 MySQL 与 ShardingSphere 分片

## 1. 本章解决什么问题

理解这个项目中“业务代码明明只写 `t_link`，为什么实际落到 `ds1.t_link_5`”。

核心结论：

```text
业务代码写逻辑表
ShardingSphere 拦截 JDBC SQL
根据分片键改写 SQL
路由到真实库表
```

## 2. 依赖

```xml
<dependency>
    <groupId>org.apache.shardingsphere</groupId>
    <artifactId>shardingsphere-jdbc-core</artifactId>
</dependency>

<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
</dependency>

<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-boot-starter</artifactId>
</dependency>
```

## 3. 数据库结构

```text
shortlink_ds0
  ├── t_link_0 ~ t_link_7
  ├── t_access_log_0 ~ t_access_log_7
  └── t_access_stats_0 ~ t_access_stats_7

shortlink_ds1
  ├── t_link_0 ~ t_link_7
  ├── t_access_log_0 ~ t_access_log_7
  └── t_access_stats_0 ~ t_access_stats_7
```

逻辑分片数：

```text
2 个库 × 8 张表 = 16 个物理分片
```

## 4. YAML 与 Java 配置

项目 YAML 中有 ShardingSphere 配置：

```yaml
spring:
  shardingsphere:
    datasource:
      names: ds0, ds1
    rules:
      sharding:
        t_link:
          actual-data-nodes:
            ds$->{0..1}.t_link_$->{0..7}
```

但当前项目还手写了 `ShardingSphereConfig`。
实际起作用的是手写 `DataSource` Bean：

```java
@Bean
@Primary
public DataSource dataSource() throws SQLException {
}
```

为什么会手写：

```text
Spring Boot 3.x 与部分 ShardingSphere 自动配置组合不稳定
手写 DataSource 更明确
方便直接阅读规则
```

## 5. 创建两个真实数据源

```java
Map<String, DataSource> dataSourceMap = new HashMap<>();
dataSourceMap.put("ds0",
        createHikari(ds0Url, ds0Username, ds0Password));
dataSourceMap.put("ds1",
        createHikari(ds1Url, ds1Username, ds1Password));
```

每个真实库用一个 HikariCP 数据源。

## 6. 逻辑表和真实节点

```java
ShardingTableRuleConfiguration linkTable =
        new ShardingTableRuleConfiguration(
                "t_link",
                "ds$->{0..1}.t_link_$->{0..7}");
```

含义：

```text
逻辑表名：t_link
实际节点：ds0.t_link_0 ... ds1.t_link_7
```

业务代码不需要知道真实表名。

## 7. 分库和分表策略

```java
linkTable.setDatabaseShardingStrategy(
        new StandardShardingStrategyConfiguration(
                "gid", "db-hash"));

linkTable.setTableShardingStrategy(
        new StandardShardingStrategyConfiguration(
                "gid", "tbl-mod"));
```

含义：

```text
分片列：gid
库算法：db-hash
表算法：tbl-mod
```

算法：

```java
Properties dbHashProps = new Properties();
dbHashProps.setProperty("sharding-count", "2");

Properties tblModProps = new Properties();
tblModProps.setProperty("sharding-count", "8");
```

算法类型：

```text
HASH_MOD
```

概念上：

```text
dbIndex = hash(gid) % 2
tableIndex = hash(gid) % 8
```

## 8. gid 从哪里来

创建短链：

```java
entity.setGid("u_" + UserContext.getUserId());
```

例如用户 ID 是 5：

```text
gid = u_5
```

以后这个用户创建的短链都带同一 gid，落点稳定。

## 9. Mapper 为什么不用手写路由

实体：

```java
@TableName("t_link")
public class LinkEntity {
}
```

Mapper：

```java
@Mapper
public interface LinkMapper
        extends BaseMapper<LinkEntity> {
}
```

插入：

```java
linkMapper.insert(entity);
```

MyBatis-Plus 生成逻辑 SQL：

```sql
INSERT INTO t_link (...) VALUES (...)
```

ShardingSphere 读取参数中的 `gid`，改写为：

```sql
INSERT INTO t_link_5 (...) VALUES (...)
```

并在 `dsX` 上执行。

## 10. 访问日志和统计表

同样注册：

```text
t_access_log  分片键 link_gid
t_access_stats 分片键 link_gid
```

消费者写入日志时带入：

```text
link_gid
message_id
```

所以既能路由，也能用唯一索引幂等。

## 11. 不带分片键会怎样

跳转查询：

```java
wrapper.eq(LinkEntity::getShortUri, shortUri);
linkMapper.selectList(wrapper);
```

SQL 没有 `gid`：

```text
ShardingSphere 无法确定单个分片
  -> 广播到 16 个分片
  -> 合并结果
```

访问量低时能工作。
高频路径依靠缓存避免每次广播。

## 12. 为什么开启 sql-show

```java
Properties props = new Properties();
props.setProperty("sql-show", "true");
```

日志可以看到：

```text
Logic SQL
Actual SQL
路由的数据源和表
```

调试分片问题时非常有用。

## 13. 分片设计取舍

优点：

```text
单表数据量下降
多库分担写入
同一用户数据尽量集中
```

代价：

```text
跨分片查询复杂
不带分片键的 SQL 会广播
跨分片分页和聚合困难
自增 ID 可能重复
分布式事务复杂
```

## 14. 自增 ID 的重要注意

MySQL 表 `AUTO_INCREMENT` 是单表行为。

16 个物理表各自增长：

```text
ds0.t_link_0 可能生成 id=1
ds1.t_link_3 也可能生成 id=1
```

因此分片表通常应使用：

```text
Snowflake ID
号段 ID
UUID/ULID
包含分片信息的复合键
```

当前项目不少接口仍按 `id` 查询。
面试或生产改造时要意识到这个限制。

## 15. 分页问题

跨分片分页不能只对每个分片 `LIMIT offset,size` 后直接拼起来。

简单广播分页代码可能：

```text
每个分片各取一页
合并后排序
```

复杂场景要用 ShardingSphere 的归并能力，或通过带分片键的条件缩小查询范围。

## 16. 同类方案

| 方案 | 特点 |
|---|---|
| 单库单表 | 最简单，数据量大后瓶颈明显 |
| 分库分表 | 扩展写能力和单表容量，复杂度高 |
| 读写分离 | 扩展读能力，不解决单表过大 |
| 分区表 | 单库内管理大表 |
| NewSQL/分布式数据库 | 对应用更透明，但仍需设计键 |

分库分表不是默认选择。
只有数据量、写入量或单表容量达到瓶颈时才值得引入。

## 17. MyBatis-Plus 在这里怎么用

### 实体映射

```java
@TableName("t_link")
public class LinkEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    @TableLogic
    private Integer delFlag;
}
```

含义：

```text
@TableName   Java 类对应逻辑表 t_link
@TableId     主键字段和生成策略
@TableLogic  逻辑删除字段
```

### BaseMapper

```java
@Mapper
public interface LinkMapper
        extends BaseMapper<LinkEntity> {
}
```

提供常用方法：

```text
insert
deleteById
updateById
selectById
selectList
selectPage
```

业务代码仍然操作逻辑表。
真实库表路由由 ShardingSphere 完成。

### LambdaQueryWrapper

```java
LambdaQueryWrapper<LinkEntity> wrapper =
        Wrappers.lambdaQuery();
wrapper.eq(LinkEntity::getShortUri, shortUri);
wrapper.eq(LinkEntity::getEnableStatus, 1);
```

优点：

```text
字段名通过方法引用表达
减少手写字符串字段名
编译阶段能发现大部分字段错误
```

### 分页插件

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

没有分页插件时，`selectPage` 可能退化成查询全部记录。

分片场景还要特别注意跨分片分页归并。

### 自动填充时间

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

实体字段：

```java
@TableField(fill = FieldFill.INSERT)
private LocalDateTime createTime;
```

插入时会自动填充时间，避免每个 Service 手动设置。

### 分页近似 total

当前项目为了避开跨分片 count，关闭自动 count，并采用近似 total。

这意味着：

```text
总条数可能不是严格精确值
分页主要用于管理端展示
如果需要精确总数，应设计合适的分片键或独立计数方案
```

## 18. 本章自检

1. 逻辑表和物理表有什么区别？
2. `HASH_MOD(gid, 2)` 和 `HASH_MOD(gid, 8)` 分别决定什么？
3. 为什么 `short_uri` 查询会广播？
4. 为什么访问日志用 `link_gid` 作为分片键？
5. ShardingSphere 是否要求业务代码自己写路由算法？
6. 分片后自增 ID 为什么可能重复？
7. 跨分片分页为什么困难？
8. MyBatis-Plus 的 `@TableName` 和 ShardingSphere 的逻辑表是什么关系？
9. 分页插件为什么必须注册？
