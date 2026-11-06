# 15 UA 解析与离线 GeoIP

## 1. 本章解决什么问题

访问日志需要补充两类信息：

```text
设备信息：浏览器、操作系统、PC/Mobile/Tablet
地区信息：国家、省份、城市
```

项目不使用外部在线 IP 服务，而是：

```text
User-Agent 正则解析
ip2region 离线 xdb 数据库
```

## 2. UA 是什么

浏览器请求头：

```http
User-Agent: Mozilla/5.0 ... Chrome/120 ...
```

它不是完全结构化数据，只能根据常见格式匹配：

```text
浏览器名称
操作系统
设备类型
```

## 3. UA 依赖

项目没有引入专门的 UA 解析库，而是使用 JDK 正则：

```java
import java.util.regex.Pattern;
```

解析类本质上是一个 Spring Bean：

```java
@Component
public class UserAgentUtil {
}
```

## 4. 浏览器正则

```java
private static final Pattern EDGE =
        Pattern.compile("Edg/(\\d+)",
                Pattern.CASE_INSENSITIVE);

private static final Pattern CHROME =
        Pattern.compile("Chrome/(\\d+)",
                Pattern.CASE_INSENSITIVE);
```

解释：

```text
Edg/(\d+)  匹配 Edge 和版本号
Chrome/... 匹配 Chrome
CASE_INSENSITIVE 忽略大小写
```

判断顺序很重要：

```text
Edge UA 中也包含 Chrome
所以必须先判断 Edge，再判断 Chrome
```

代码：

```java
if (EDGE.matcher(userAgent).find()) return "Edge";
if (CHROME.matcher(userAgent).find()) return "Chrome";
```

## 5. 操作系统正则

```java
IOS       iPhone OS (版本)
ANDROID   Android (版本)
WINDOWS   Windows NT (版本)
MAC       Mac OS X (版本)
LINUX     Linux
```

匹配：

```java
if (IOS.matcher(userAgent).find()) return "iOS";
if (ANDROID.matcher(userAgent).find()) return "Android";
```

## 6. 设备类型

```java
private static final Pattern MOBILE = Pattern.compile(
        "Mobile|Android.*Mobile|iPhone|iPod|...");

private static final Pattern TABLET = Pattern.compile(
        "iPad|Android(?!.*Mobile)|Tablet");
```

判断顺序：

```text
先 Tablet
再 Mobile
否则 PC
```

原因：

```text
部分平板 UA 同时包含 Mobile
```

## 7. UA 解析的边界

正则解析是规则驱动：

```text
不会自动认识所有新设备
规则有限
格式变化可能识别错误
```

生产改进：

```text
使用 ua-parser
使用 Yauaa
定期更新规则
把解析结果缓存
```

## 8. ip2region 依赖

```xml
<dependency>
    <groupId>org.lionsoul</groupId>
    <artifactId>ip2region</artifactId>
    <version>2.7.0</version>
</dependency>
```

使用离线 `xdb` 文件：

```text
admin/src/main/resources/ip2region/ip2region_v4.xdb
```

## 9. 为什么用离线库

优点：

```text
无外部 HTTP 请求
不受第三方服务限流
无网络延迟
隐私数据不发送给第三方
```

缺点：

```text
数据库需要更新
打包体积增加
精度取决于数据源
```

## 10. 初始化

```java
@Component
public class GeoIpUtil {

    private volatile Searcher searcher;

    @PostConstruct
    public void init() {
        // 读取 xdb
        // 创建 Searcher
    }
}
```

`@PostConstruct`：

```text
Spring 创建 Bean 并完成依赖注入后调用
```

适合做一次性的资源初始化。

## 11. 为什么复制到临时文件

JAR 中的资源不能像普通文件一样随意 mmap。

代码：

```java
Path tempFile = Files.createTempFile(
        "ip2region_", ".xdb");

try (InputStream in = resource.getInputStream()) {
    Files.copy(in, tempFile, REPLACE_EXISTING);
}

Searcher.newWithFileOnly(tempFile.toString());
```

步骤：

```text
从 classpath 读取 xdb
  -> 写到临时文件
  -> Searcher 从文件加载
```

临时文件设置了：

```java
tempFile.toFile().deleteOnExit();
```

JVM 退出时尝试删除。

## 12. 查询格式

ip2region 返回类似：

```text
中国|0|广东省|深圳市|电信
```

代码：

```java
String[] parts = result.split("\\|", -1);
```

对应：

```text
parts[0] 国家
parts[1] 区域/保留位
parts[2] 省份
parts[3] 城市
parts[4] ISP
```

## 13. 内网 IP

内网地址不查库：

```text
127.*
10.*
192.168.*
172.16.* ~ 172.31.*
```

返回：

```text
内网
```

否则读取失败返回：

```text
未知
```

## 14. 在 Stream 消费者里使用

消费者拿到：

```text
user_agent
visitor_ip
```

解析：

```java
UserAgentParts ua =
        userAgentCache.get(userAgent, key ->
                new UserAgentParts(
                        userAgentUtil.parseBrowser(key),
                        userAgentUtil.parseOs(key),
                        userAgentUtil.parseDevice(key)));

GeoLocation geo =
        geoLocationCache.get(visitorIp, key ->
                new GeoLocation(
                        geoIpUtil.parseCountry(key),
                        geoIpUtil.parseProvince(key),
                        geoIpUtil.parseCity(key)));
```

结果写入：

```text
browser
os
device_type
country
province
city
```

## 15. 为什么再加 Caffeine

同一批访问中：

```text
很多 Chrome/Windows
很多相同 IP
```

重复执行正则和 ip2region 查询没有必要。

缓存：

```java
Cache<String, UserAgentParts> userAgentCache;
Cache<String, GeoLocation> geoLocationCache;
```

作用：

```text
相同 UA 只解析一次
相同 IP 只解析一次
```

这是消费者内部缓存，不是短链缓存。

## 16. 多条消息的优化

当前代码对每条消息：

```text
构造 AccessLogEntity
解析 UA
解析 GeoIP
插入数据库
```

一批 100 条时，数据库仍是逐条 insert。

可优化为：

```text
批量 insert
批量 SQL
异步分区写入
```

但要注意：

```text
事务边界
失败消息的 ACK
重复消息幂等
```

## 17. 同类方案

| 能力 | 当前方案 | 替代方案 |
|---|---|---|
| UA 解析 | 正则 | ua-parser、Yauaa |
| IP 地区 | ip2region xdb | MaxMind GeoLite2、在线 API |
| 重复解析缓存 | Caffeine | Redis、Guava Cache |
| 日志写入 | 单条 insert | 批量 insert、Kafka + OLAP |

## 18. 本章自检

1. 为什么 Edge 要在 Chrome 前判断？
2. 为什么 Tablet 要在 Mobile 前判断？
3. `@PostConstruct` 适合做什么？
4. JAR 中的 xdb 为什么复制到临时文件？
5. ip2region 返回字段如何映射到国家/省/市？
6. 内网 IP 为什么要单独处理？
7. 为什么 UA 和 GeoIP 结果还要加本地缓存？
