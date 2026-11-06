# 04 Redis Bitmap 布隆过滤器

## 1. 本章解决什么问题

理解代码怎么用 Redis Bitmap 判断：

```text
某个短码一定不存在
或可能存在
```

以及为什么创建短链时要写布隆过滤器。

## 2. 为什么需要它

攻击者可以随机请求：

```text
/s/aaaaaa
/s/bbbbbb
/s/cccccc
```

这些短码不存在。

如果没有布隆过滤器：

```text
L1 未命中
L2 未命中
每次查 MySQL
```

加入后：

```text
布隆过滤器说“不存在”
  -> 直接返回 404
```

## 3. 依赖和模块位置

布隆过滤器放在 `common` 模块，因为：

```text
admin 要写入
project 要读取
哈希算法和 Redis Key 必须一致
```

依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
</dependency>
```

Guava 用于 MurmurHash3。

## 4. 配置

管理端和跳转端必须完全一致：

```yaml
shortlink:
  bloom-filter:
    size: 33554432
    hash-count: 3
    salt: default
    key: shortlink:bf:short_uri
```

含义：

```text
size：Bitmap 位数，33554432 bit = 4 MiB
hash-count：每个元素映射 3 个位置
salt：第二个哈希的附加字符串
key：Redis Bitmap Key
```

如果两边参数不一致，会出现：

```text
admin 写入的位置
project 查不到
```

后果是已有短链也可能被判定不存在。

## 5. 配置类和 @Value

```java
@Configuration
public class BloomFilterConfig {

    @Value("${shortlink.bloom-filter.size:33554432}")
    private int size;

    @Value("${shortlink.bloom-filter.hash-count:3}")
    private int hashCount;

    @Value("${shortlink.bloom-filter.salt:default}")
    private String salt;

    @Value("${shortlink.bloom-filter.key:shortlink:bf:short_uri}")
    private String bloomFilterKey;
}
```

Bean：

```java
@Bean
public BloomFilterHelper bloomFilterHelper(
        StringRedisTemplate redisTemplate) {
    return new BloomFilterHelper(
            redisTemplate,
            size,
            hashCount,
            salt,
            bloomFilterKey,
            Hashing.murmur3_32(0));
}
```

`Hashing.murmur3_32(0)` 使用固定种子，保证 admin 和 project 计算结果相同。

## 6. 双哈希

```java
int h1 = hash(value);
int h2 = hash(value + ":" + salt);

for (int i = 0; i < hashCount; i++) {
    long position = Math.abs(
            (h1 + (long) i * h2) % size);
}
```

公式：

```text
position_i = (h1 + i * h2) % size
```

`hashCount=3` 时：

```text
position0 = h1 % size
position1 = (h1 + h2) % size
position2 = (h1 + 2*h2) % size
```

这就是 double hashing。
它只需要计算两次哈希，就能模拟多个位置。

## 7. 写入 Bitmap

```java
public void add(String value) {
    int[] positions = hash(value);
    for (int pos : positions) {
        redisTemplate.opsForValue().setBit(key, pos, true);
    }
}
```

底层：

```text
SETBIT shortlink:bf:short_uri 123456 1
SETBIT shortlink:bf:short_uri 789012 1
SETBIT shortlink:bf:short_uri 345678 1
```

## 8. 查询 Bitmap

```java
public boolean mightContain(String value) {
    int[] positions = hash(value);
    for (int pos : positions) {
        if (Boolean.FALSE.equals(
                redisTemplate.opsForValue()
                        .getBit(key, pos))) {
            return false;
        }
    }
    return true;
}
```

底层：

```text
GETBIT shortlink:bf:short_uri 123456
GETBIT shortlink:bf:short_uri 789012
GETBIT shortlink:bf:short_uri 345678
```

规则：

```text
任意一位是 0 -> 一定不存在
三位都是 1   -> 可能存在
```

## 9. 误判为什么可以接受

布隆过滤器存在假阳性：

```text
元素不存在，但所有位置被别人置成了 1
```

此时查询会继续进入数据库。

这只会多做一次查询，不会返回错误数据。

假阴性则不可接受：

```text
元素明明存在，却判断不存在
```

项目通过以下方式避免：

```text
创建短链时写 Bitmap
读取命中并回填时写 Bitmap
已有历史数据启动时回填
```

## 10. 创建短链时写入

核心顺序：

```java
bloomFilterHelper.add(candidate);
linkMapper.insert(entity);
```

为什么先写 Bitmap、再插数据库？

因为如果数据库有、Bitmap 没有：

```text
短链接永久被判不存在
```

如果 Bitmap 有、数据库没有：

```text
最多形成一次假阳性
以后查库返回空
```

这就是顺序的取舍。

## 11. 历史数据回填

`BloomBackfillRunner` 实现 `ApplicationRunner`：

```java
@Override
public void run(ApplicationArguments args) {
    // 查询所有短链
    // 逐个写入布隆过滤器
}
```

`ApplicationRunner` 在容器启动完成后执行。

代码用 Redis Key：

```text
shortlink:bf:backfilled
```

做一次性标记：

```java
setIfAbsent(flag, "1")
```

只有第一次抢到标记的实例执行回填。

## 12. 删除问题

布隆过滤器通常不支持删除。

短链删除后：

```text
Bitmap 位不会清零
```

所以删除的短码：

```text
可能仍通过布隆过滤器
继续查缓存/数据库
最后返回 404 或 410
```

这是可接受的。

如果业务需要频繁删除，可以考虑：

```text
可计数布隆过滤器
RedisBloom 模块
定期重建 Bitmap
```

## 13. 同类方案

| 方案 | 特点 |
|---|---|
| Redis Bitmap 手写布隆 | 依赖少，可控制哈希和参数 |
| RedisBloom 模块 | 原生 `BF.ADD/BF.EXISTS`，运维需安装模块 |
| Guava BloomFilter | 进程内，多个实例各自一份 |
| 数据库唯一索引 | 能精准确认，但需要查库 |

本项目使用第一类，适合学习和已有 Redis 的环境。

## 14. 本章自检

1. 布隆过滤器为什么只能证明“不存在”？
2. 为什么 admin 和 project 的 size/salt/hash-count 必须一致？
3. double hashing 的计算公式是什么？
4. `SETBIT` 和 `GETBIT` 分别做什么？
5. 为什么先写 Bitmap 再写数据库？
6. 历史数据为什么必须回填？
7. 短链删除后为什么可以不清 Bitmap？
