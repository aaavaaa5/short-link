# UA 与离线 GeoIP 完整实现

## 0. 实现目标

```text
User-Agent -> 浏览器 / 操作系统 / 设备类型
IP -> 国家 / 省份 / 城市
结果写入访问日志表
```

---

## 1. Maven 依赖

```xml
<dependency>
    <groupId>org.lionsoul</groupId>
    <artifactId>ip2region</artifactId>
    <version>2.7.0</version>
</dependency>
```

HTTP 请求头解析依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

---

## 2. 放置 xdb 文件

```text
src/main/resources/ip2region/ip2region_v4.xdb
```

打包进 JAR 后仍然可以读取 classpath 资源。

---

## 3. UserAgentUtil 完整实现

```java
@Component
public class UserAgentUtil {

    private static final Pattern EDGE =
            Pattern.compile("Edg/(\\d+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern CHROME =
            Pattern.compile("Chrome/(\\d+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern FIREFOX =
            Pattern.compile("Firefox/(\\d+)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFARI =
            Pattern.compile("Version/(\\d+).*Safari",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern IOS =
            Pattern.compile("iPhone OS",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern ANDROID =
            Pattern.compile("Android",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern WINDOWS =
            Pattern.compile("Windows NT",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MAC =
            Pattern.compile("Mac OS X",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern LINUX =
            Pattern.compile("Linux",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLET =
            Pattern.compile(
                    "iPad|Tablet|Android(?!.*Mobile)",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern MOBILE =
            Pattern.compile(
                    "Mobile|iPhone|iPod|Android.*Mobile",
                    Pattern.CASE_INSENSITIVE);

    public String parseBrowser(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Unknown";
        }
        if (EDGE.matcher(ua).find()) return "Edge";
        if (CHROME.matcher(ua).find()) return "Chrome";
        if (FIREFOX.matcher(ua).find()) return "Firefox";
        if (SAFARI.matcher(ua).find()) return "Safari";
        return "Other";
    }

    public String parseOs(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Unknown";
        }
        if (IOS.matcher(ua).find()) return "iOS";
        if (ANDROID.matcher(ua).find()) return "Android";
        if (WINDOWS.matcher(ua).find()) return "Windows";
        if (MAC.matcher(ua).find()) return "macOS";
        if (LINUX.matcher(ua).find()) return "Linux";
        return "Other";
    }

    public String parseDevice(String ua) {
        if (ua == null || ua.isBlank()) {
            return "Unknown";
        }
        if (TABLET.matcher(ua).find()) return "Tablet";
        if (MOBILE.matcher(ua).find()) return "Mobile";
        return "PC";
    }
}
```

为什么先判断 Edge：

```text
Edge 的 User-Agent 中通常也包含 Chrome
```

为什么先判断 Tablet：

```text
部分平板 UA 也包含 Mobile
```

---

## 4. GeoIpUtil 完整实现

```java
@Component
public class GeoIpUtil {

    private volatile Searcher searcher;

    @PostConstruct
    public void init() throws IOException {
        ClassPathResource resource =
                new ClassPathResource(
                        "ip2region/ip2region_v4.xdb");

        if (!resource.exists()) {
            return;
        }

        Path temp = Files.createTempFile(
                "ip2region-", ".xdb");
        temp.toFile().deleteOnExit();

        try (InputStream in =
                     resource.getInputStream()) {
            Files.copy(
                    in,
                    temp,
                    StandardCopyOption.REPLACE_EXISTING);
        }

        searcher = Searcher.newWithFileOnly(
                temp.toString());
    }

    public String parseCountry(String ip) {
        String[] parts = query(ip);
        return parts.length > 0 ? parts[0] : "未知";
    }

    public String parseProvince(String ip) {
        String[] parts = query(ip);
        return parts.length > 2 ? parts[2] : "未知";
    }

    public String parseCity(String ip) {
        String[] parts = query(ip);
        return parts.length > 3 ? parts[3] : "未知";
    }

    private String[] query(String ip) {
        if (ip == null || ip.isBlank()) {
            return new String[0];
        }

        if (isPrivate(ip)) {
            return new String[]{"内网"};
        }

        if (searcher == null) {
            return new String[0];
        }

        try {
            String result = searcher.search(ip);
            return result == null
                    ? new String[0]
                    : result.split("\\|", -1);
        } catch (Exception e) {
            return new String[0];
        }
    }

    private boolean isPrivate(String ip) {
        return ip.startsWith("10.")
                || ip.startsWith("127.")
                || ip.startsWith("192.168.")
                || ip.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }
}
```

---

## 5. 为什么复制到临时文件

JAR 内资源不是普通磁盘文件。

```text
classpath resource
  -> 输入流
  -> 临时文件
  -> Searcher 从文件加载
```

`@PostConstruct`：

```text
Bean 创建并注入完成后执行一次
```

---

## 6. 在消费者中使用

```java
@Component
public class AccessLogParser {

    private final UserAgentUtil uaUtil;
    private final GeoIpUtil geoUtil;

    public AccessLogEntity parse(
            String ua,
            String ip) {
        AccessLogEntity entity =
                new AccessLogEntity();

        entity.setUserAgent(ua);
        entity.setVisitorIp(ip);
        entity.setBrowser(uaUtil.parseBrowser(ua));
        entity.setOs(uaUtil.parseOs(ua));
        entity.setDevice(uaUtil.parseDevice(ua));

        entity.setCountry(geoUtil.parseCountry(ip));
        entity.setProvince(geoUtil.parseProvince(ip));
        entity.setCity(geoUtil.parseCity(ip));
        return entity;
    }
}
```

---

## 7. 加 Caffeine 缓存

```java
Cache<String, String> browserCache =
        Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(
                        24, TimeUnit.HOURS)
                .build();
```

使用：

```java
String browser = browserCache.get(
        ua,
        uaUtil::parseBrowser);
```

好处：

```text
相同 UA 正则只执行一次
相同 IP 地区只查一次
```

---

## 8. 验证

使用真实 UA 字符串：

```java
String ua = "Mozilla/5.0 ... Edg/120.0";
System.out.println(uaUtil.parseBrowser(ua));
```

输出：

```text
Edge
```

测试 IP：

```java
System.out.println(geoUtil.parseCountry("8.8.8.8"));
System.out.println(geoUtil.parseProvince("8.8.8.8"));
System.out.println(geoUtil.parseCity("8.8.8.8"));
```

本机测试：

```text
127.0.0.1 -> 内网
```

---

## 9. 从零实现顺序

```text
1. 加 ip2region 依赖
2. 放 xdb 文件
3. 写 @PostConstruct 初始化
4. 实现国家/省/市查询
5. 写 UA 正则
6. 先解析浏览器
7. 再解析 OS
8. 再解析设备
9. 接入 Stream 消费者
10. 加 Caffeine 缓存
```

---

## 10. 本篇自检

```text
[ ] 能写 UA 正则
[ ] 能解释 Edge 与 Chrome 顺序
[ ] 能初始化 ip2region Searcher
[ ] 能解析 xdb 返回字段
[ ] 能处理内网 IP
[ ] 能在消费者中使用解析结果
[ ] 能解释解析缓存作用
```
