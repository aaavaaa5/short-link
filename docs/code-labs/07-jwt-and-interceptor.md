# JWT、拦截器与用户上下文完整实现

## 0. 实现目标

```text
注册时 BCrypt 保存密码
登录时校验密码并返回 JWT
后续请求通过 Authorization 携带 JWT
拦截器校验 JWT
当前用户写入 ThreadLocal
请求结束后清理
```

---

## 1. Maven 依赖

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>
        spring-boot-starter-validation
    </artifactId>
</dependency>

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

BCrypt：

```xml
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-all</artifactId>
</dependency>
```

---

## 2. 配置

```yaml
shortlink:
  jwt:
    secret: shortlink-admin-secret-key-min-256-bits!!
    expire-hours: 24
```

生产环境不要直接写在 YAML，应该使用环境变量或密钥系统。

---

## 3. JwtUtil 完整代码

```java
@Component
public class JwtUtil {

    @Value("${shortlink.jwt.secret}")
    private String secret;

    @Value("${shortlink.jwt.expire-hours:24}")
    private int expireHours;

    public String generateToken(
            Long userId,
            String username) {
        Date now = new Date();
        Date expiration = new Date(
                now.getTime()
                + expireHours * 3600_000L);

        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("username", username)
                .setIssuer("shortlink-admin")
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(key())
                .compact();
    }

    public Claims parse(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(key())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            return null;
        }
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8));
    }
}
```

---

## 4. JWT 中有什么

```text
sub       userId
username  用户名
iss       issuer
iat       签发时间
exp       过期时间
```

JWT 的 Payload 只是 Base64URL 编码，不是加密。
不要放密码、手机号等敏感信息。

---

## 5. 注册和登录

注册：

```java
public Result<Void> register(RegisterReq req) {
    UserEntity user = new UserEntity();
    user.setUsername(req.getUsername());
    user.setPassword(
            BCrypt.hashpw(
                    req.getPassword(),
                    BCrypt.gensalt()));
    userMapper.insert(user);
    return Result.success();
}
```

登录：

```java
public Result<LoginResp> login(LoginReq req) {
    UserEntity user = findByUsername(
            req.getUsername());

    if (user == null
            || !BCrypt.checkpw(
                    req.getPassword(),
                    user.getPassword())) {
        return Result.fail(
                401, "用户名或密码错误");
    }

    String token = jwtUtil.generateToken(
            user.getId(), user.getUsername());

    return Result.success(
            new LoginResp(token, user.getId()));
}
```

---

## 6. UserContext 完整代码

```java
public final class UserContext {

    private static final ThreadLocal<Map<String, Object>>
            CONTEXT = new ThreadLocal<>();

    public static void set(
            Long userId,
            String username) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", userId);
        map.put("username", username);
        CONTEXT.set(map);
    }

    public static Long getUserId() {
        Map<String, Object> map = CONTEXT.get();
        return map == null
                ? null
                : (Long) map.get("userId");
    }

    public static String getUsername() {
        Map<String, Object> map = CONTEXT.get();
        return map == null
                ? null
                : (String) map.get("username");
    }

    public static void clear() {
        CONTEXT.remove();
    }
}
```

为什么 ThreadLocal：

```text
每个 HTTP 请求一个线程
线程之间不能共享当前用户
```

---

## 7. LoginInterceptor 完整代码

```java
@Component
public class LoginInterceptor
        implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    public LoginInterceptor(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {

        String header =
                request.getHeader("Authorization");

        if (header == null
                || !header.startsWith("Bearer ")) {
            write401(response, "未登录");
            return false;
        }

        String token = header.substring(7);
        Claims claims = jwtUtil.parse(token);

        if (claims == null) {
            write401(response, "Token 无效");
            return false;
        }

        Long userId = Long.valueOf(
                claims.getSubject());
        String username = claims.get(
                "username", String.class);

        UserContext.set(userId, username);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception ex) {
        UserContext.clear();
    }

    private void write401(
            HttpServletResponse response,
            String message) throws IOException {
        response.setStatus(401);
        response.setContentType(
                "application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":401,\"message\":\""
                + message + "\"}");
    }
}
```

---

## 8. 注册拦截器

```java
@Configuration
public class WebConfig
        implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;

    public WebConfig(
            LoginInterceptor loginInterceptor) {
        this.loginInterceptor = loginInterceptor;
    }

    @Override
    public void addInterceptors(
            InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/swagger-ui/**",
                        "/v3/api-docs/**");
    }
}
```

---

## 9. 业务代码中读取用户

```java
@PostMapping("/api/link")
public Result<LinkVO> create(
        @RequestBody LinkCreateReq req) {
    Long userId = UserContext.getUserId();
    return linkService.create(req, userId);
}
```

Service：

```java
public LinkEntity create(
        LinkCreateReq req,
        Long userId) {
    LinkEntity link = new LinkEntity();
    link.setGid("u_" + userId);
    link.setCreateUserId(userId);
    return link;
}
```

---

## 10. 请求完整顺序

```text
POST /api/link
  -> Gateway
  -> LoginInterceptor.preHandle
       -> 读取 Bearer Token
       -> 验签和过期时间
       -> UserContext.set
  -> Controller
  -> Service 读取 UserContext
  -> 响应结束
  -> afterCompletion
       -> UserContext.clear
```

---

## 11. 验证

登录：

```bash
curl -X POST http://localhost:9000/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin123"}'
```

请求：

```bash
curl http://localhost:9000/api/link/1 \
  -H 'Authorization: Bearer <token>'
```

不带 Token 应返回 401。

---

## 12. 本篇自检

```text
[ ] 能生成 JWT
[ ] 能解析和校验 JWT
[ ] 能写 preHandle
[ ] 能写 afterCompletion 清理
[ ] 能解释 ThreadLocal 为什么需要 clear
[ ] 能新增需要登录的接口
```
