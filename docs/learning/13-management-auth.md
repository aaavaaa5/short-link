# 13 JWT、拦截器与用户上下文

## 1. 本章解决什么问题

理解管理端如何从匿名请求变成“知道当前用户是谁”：

```text
登录
  -> 生成 JWT
  -> 浏览器携带 Authorization
  -> 拦截器校验
  -> 写入 UserContext
  -> Service 读取 userId
```

## 2. 依赖

JWT：

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
</dependency>

<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <scope>runtime</scope>
</dependency>

<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <scope>runtime</scope>
</dependency>
```

密码哈希使用 Hutool 提供的 BCrypt：

```xml
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>
</dependency>
```

## 3. 注册和登录

注册：

```java
user.setPassword(
        BCrypt.hashpw(req.getPassword(), BCrypt.gensalt()));
```

数据库只保存哈希，不保存明文密码。

登录：

```java
if (!BCrypt.checkpw(req.getPassword(), user.getPassword())) {
    return Result.fail(401, "用户名或密码错误");
}
```

登录成功生成 JWT：

```java
String token = jwtUtil.generateToken(
        user.getId(), user.getUsername());
```

## 4. JWT 结构

```text
Header.Payload.Signature
```

项目 Payload 包含：

```text
sub      用户 ID
username 用户名
iss      签发者
iat      签发时间
exp      过期时间
```

JWT 不是加密数据。
任何人都能解码 Payload，所以不要放密码等敏感信息。
签名用于防篡改。

## 5. 生成 Token

```java
return Jwts.builder()
        .setClaims(claims)
        .setSubject(String.valueOf(userId))
        .setIssuer("shortlink-admin")
        .setIssuedAt(now)
        .setExpiration(expiration)
        .signWith(getKey())
        .compact();
```

解析：

```java
Jwts.parserBuilder()
        .setSigningKey(getKey())
        .build()
        .parseClaimsJws(token)
        .getBody();
```

## 6. 浏览器如何携带

请求头：

```http
Authorization: Bearer eyJhbGciOi...
```

拦截器去掉 `Bearer ` 前缀后再解析。

## 7. LoginInterceptor

```java
@Component
public class LoginInterceptor
        implements HandlerInterceptor {

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) {
        // 校验 token
        // 写入 UserContext
        return true;
    }
}
```

返回 false：

```text
请求终止
```

返回 true：

```text
继续执行下一个拦截器和 Controller
```

## 8. ThreadLocal

`UserContext` 使用：

```java
ThreadLocal<Map<String, Object>>
```

原因：

```text
每个请求线程独立保存当前用户
避免多个请求共享 userId
```

写入：

```java
UserContext.set(userId, username, realName);
```

读取：

```java
Long userId = UserContext.getUserId();
```

清理：

```java
UserContext.clear();
```

## 9. 为什么必须清理

Web 服务器使用线程池：

```text
请求 A 使用线程 T
请求 A 结束
线程 T 回到线程池
请求 B 可能继续使用线程 T
```

如果不清空：

```text
请求 B 可能读到请求 A 的 userId
```

所以放在 `afterCompletion`：

```java
@Override
public void afterCompletion(...) {
    UserContext.clear();
}
```

## 10. 拦截器注册顺序

```java
registry.addInterceptor(loginInterceptor)
        .addPathPatterns("/api/**");

registry.addInterceptor(userFlowRiskCntlFilter)
        .addPathPatterns("/api/**");
```

注册顺序：

```text
1. 登录拦截
2. 用户限流
```

用户限流需要 `UserContext.getUserId()`，所以必须先登录。

Spring 的 `order` 也可以显式指定，但当前通过注册顺序表达意图。

## 11. 白名单

登录拦截器允许：

```text
/api/auth/register
/api/auth/login
/swagger-ui
/v3/api-docs
```

其余 `/api/**` 必须带 Token。

## 12. 新增一个需要登录的接口

Controller：

```java
@GetMapping("/api/demo/me")
public Result<String> me() {
    return Result.success(
            UserContext.getUsername());
}
```

只要保持在 `/api/**` 下，就会经过：

```text
JWT 拦截器
用户限流
```

Service 中读取：

```java
Long userId = UserContext.getUserId();
```

## 13. 常见安全问题

### 密钥写死在 YAML

当前是教学配置。
生产应使用环境变量、密钥管理系统。

### 角色权限

项目只做登录认证，尚未完整接入角色校验。

若要加：

```text
@PreAuthorize
Spring Security
自定义权限拦截器
```

### Token 撤销

JWT 默认无状态。
如果要求立即踢下线，需要：

```text
Redis 黑名单
tokenVersion
短期 Access Token + Refresh Token
```

### Header 伪造

如果部署在代理后面，客户端 IP 和 forwarded headers 需要由可信代理统一处理。

## 14. 本章自检

1. 为什么数据库不能保存明文密码？
2. JWT 为什么不能放敏感信息？
3. `preHandle` 返回 true/false 分别代表什么？
4. 为什么 UserContext 要使用 ThreadLocal？
5. 为什么请求结束必须 clear？
6. 为什么用户限流拦截器要排在登录拦截器后面？
7. 什么情况下 JWT 需要配合 Redis 黑名单？
