# 09 短链生成与跳转

## 1. 本章解决什么问题

从 URL 输入到短链返回：

```text
https://example.com/a
  -> abc123
  -> http://localhost:9000/s/abc123
```

再到用户访问短链时跳回原地址。

## 2. 依赖

Giua 的 MurmurHash3：

```xml
<dependency>
    <groupId>com.google.guava</groupId>
    <artifactId>guava</artifactId>
</dependency>
```

Hutool 随机字符串：

```xml
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>
</dependency>
```

Web：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

## 3. 短码生成

核心代码：

```java
String seed = originUrl
        + System.nanoTime()
        + RandomUtil.randomString(8);

long hash = Math.abs(
        MURMUR3.hashString(seed, UTF_8).asInt());

return base62Encode(hash, 6);
```

步骤：

```text
1. 原始 URL 加时间和随机数
2. MurmurHash3 生成哈希
3. 转成数字
4. 用字符表编码成 6 位
```

为什么加入时间和随机数：

```text
同一 URL 多次创建可以生成不同短码
避免只对 URL 哈希产生固定结果
```

## 4. 字符表

代码中的字符表去掉了容易混淆的：

```text
0 O l I
```

实际长度是 58，不是严格 62。

编码思路：

```java
while (num > 0) {
    sb.append(chars[num % chars.length]);
    num /= chars.length;
}
```

类似十进制转其他进制。

## 5. 碰撞处理

哈希可能冲突，生成结果必须查重。

流程：

```text
生成 candidate
  -> 加锁
  -> 查数据库
  -> 存在则释放锁并重试
  -> 不存在则写 Bloom + insert
```

最多重试固定次数。

## 6. 创建接口

Controller：

```java
@PostMapping
public Result<LinkVO> create(
        @Valid @RequestBody LinkCreateReqDTO req) {
    return linkService.create(req);
}
```

`@RequestBody`：

```text
请求 JSON -> DTO 对象
```

`@Valid`：

```text
执行 DTO 上的校验注解
```

## 7. Service 创建流程

```text
读取当前用户 UserContext
设置 gid = u_用户ID
默认标题、跳转类型、过期类型
生成短码
Redisson 加锁
查重
写 Bloom
插入 t_link
构造完整短 URL
```

完整短 URL：

```java
domain + "/" + entity.getShortUri()
```

例如：

```text
http://localhost:9000/s/abc123
```

## 8. 为什么写全量实体缓存

跳转不仅要 URL，还要：

```text
delFlag
enableStatus
expireType
expireTime
redirectType
gid
```

如果只缓存 URL：

```text
命中后还要查数据库拿状态
缓存价值下降
```

所以缓存整个 `LinkEntity`。

## 9. 跳转 Controller

```java
@Controller
public class RedirectController {

    @GetMapping("/{shortUri}")
    public String redirect(
            @PathVariable String shortUri,
            HttpServletRequest request,
            HttpServletResponse response) {
    }
}
```

为什么用 `@Controller` 而不是 `@RestController`：

```text
需要返回视图名 error/404
需要返回 redirect: 字符串
不是直接返回 JSON
```

## 10. 状态判断顺序

```text
不存在 -> 404
已删除 -> 410
已停用 -> 403
已过期 -> 410
正常   -> 记录访问 + 跳转
```

过期判断：

```java
expireType == 1
&& expireTime != null
&& expireTime.isBefore(LocalDateTime.now())
```

## 11. 302 跳转

```java
return "redirect:" + entity.getOriginUrl();
```

Spring MVC 识别 `redirect:`：

```text
创建 RedirectView
设置 302
设置 Location
```

## 12. 301 跳转

```java
response.setStatus(301);
response.setHeader("Location", entity.getOriginUrl());
return null;
```

因为响应已经手动写入，所以返回 null。

301 和 302：

```text
301：永久跳转，浏览器可能长期缓存
302：临时跳转，更灵活
```

## 13. 跳转访问记录

```java
redirectService.recordAccess(entity, request);
```

内部：

```text
PV INCR
UV PFADD
IP PFADD
Stream XADD
```

记录发生在发送跳转响应之前。

## 14. 为什么跳转还能快

快路径：

```text
Caffeine 命中
  -> 跳过 Redis 和 MySQL
```

慢操作：

```text
UA 解析
GeoIP
日志入库
```

都移到 Stream 消费者。

## 15. 短链系统的相似问题

### 自定义域名

需要：

```text
域名解析
按域名区分数据
证书管理
```

### 短码防猜测

当前短码由哈希生成。
如果要防遍历，可：

```text
随机生成更大空间
加校验位
签名短码
风控和频率限制
```

### 多租户

需要：

```text
tenant_id
分片键设计
权限隔离
独立缓存命名空间
```

### 跳转安全

需要防止：

```text
开放重定向
恶意协议
钓鱼链接
```

应校验目标 URL 协议和黑名单。

## 16. 本章自检

1. 为什么短码哈希要加入时间戳和随机数？
2. 短码碰撞后如何重试？
3. 为什么缓存整个 LinkEntity？
4. Spring MVC 如何处理 `redirect:`？
5. 301 和 302 有什么区别？
6. 跳转时哪些操作同步执行，哪些异步执行？
7. 为什么短码还需要限流和风控？
