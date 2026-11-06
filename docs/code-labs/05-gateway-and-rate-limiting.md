# Gateway、Nacos 与三层限流完整实现

## 0. 实现目标

```text
浏览器只访问 gateway:9000
/api/** 转发到 admin:8081
/s/**   转发到 project:8083
Gateway 按 IP 令牌桶限流
admin 按用户固定窗口限流
创建接口再经过 Sentinel
```

---

## 1. Gateway Maven 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>
        spring-cloud-starter-gateway
    </artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>
        spring-cloud-starter-loadbalancer
    </artifactId>
</dependency>

<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>
        spring-cloud-starter-alibaba-nacos-discovery
    </artifactId>
</dependency>
```

Redis 限流依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>
        spring-boot-starter-data-redis
    </artifactId>
</dependency>
```

实际项目最好确认 Redis 响应式依赖与当前 Spring Cloud Gateway 版本匹配。

---

## 2. admin 和 project 注册 Nacos

admin：

```yaml
spring:
  application:
    name: shortlink-admin
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: shortlink
```

project：

```yaml
spring:
  application:
    name: shortlink-project
  cloud:
    nacos:
      discovery:
        server-addr: 127.0.0.1:8848
        namespace: shortlink
```

启动类：

```java
@SpringBootApplication
@EnableDiscoveryClient
public class ProjectApplication {
    public static void main(String[] args) {
        SpringApplication.run(
                ProjectApplication.class, args);
    }
}
```

---

## 3. Gateway 完整 YAML

```yaml
server:
  port: 9000

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
          filters:
            - StripPrefix=1
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200
                redis-rate-limiter.requestedTokens: 1
                key-resolver: "#{@ipKeyResolver}"

        - id: shortlink-admin
          uri: lb://shortlink-admin
          predicates:
            - Path=/api/**
          filters:
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 20
                redis-rate-limiter.burstCapacity: 50
                redis-rate-limiter.requestedTokens: 1
                key-resolver: "#{@ipKeyResolver}"

  data:
    redis:
      host: localhost
      port: 6379
```

---

## 4. 路由字段逐个解释

```yaml
id: shortlink-project
```

路由名称。

```yaml
uri: lb://shortlink-project
```

```text
lb -> LoadBalancer
shortlink-project -> Nacos 服务名
```

```yaml
predicates:
  - Path=/s/**
```

只有 `/s/` 开头的请求匹配。

```yaml
- StripPrefix=1
```

`/s/abc123` 转发为 `/abc123`。

```yaml
redis-rate-limiter.replenishRate: 100
```

每秒补充 100 个令牌。

```yaml
redis-rate-limiter.burstCapacity: 200
```

桶最大容量 200。

```yaml
key-resolver: "#{@ipKeyResolver}"
```

引用 Spring 容器中名为 `ipKeyResolver` 的 Bean。

---

## 5. IP KeyResolver

```java
@Configuration
public class GatewayConfig {

    @Bean
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            var address = exchange.getRequest()
                    .getRemoteAddress();

            if (address == null) {
                return Mono.just("unknown");
            }

            String ip = address.getAddress()
                    .getHostAddress();
            return Mono.just(ip);
        };
    }
}
```

`KeyResolver` 决定限流的维度：

```text
返回 IP    -> 每个 IP 一个桶
返回 userId -> 每个用户一个桶
```

---

## 6. CORS 完整配置

```java
@Bean
public CorsWebFilter corsWebFilter() {
    CorsConfiguration config =
            new CorsConfiguration();
    config.setAllowedOriginPatterns(
            List.of("*"));
    config.setAllowedMethods(
            List.of("GET", "POST", "PUT",
                    "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return new CorsWebFilter(source);
}
```

执行时机：

```text
浏览器跨域请求
  -> Gateway 拦截
  -> 返回或补充 CORS 响应头
```

生产环境不要无脑允许所有 Origin。

---

## 7. Gateway 全局日志过滤器

```java
@Component
public class GlobalLogFilter
        implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        String method = exchange.getRequest()
                .getMethod().name();
        String path = exchange.getRequest()
                .getURI().getPath();

        return chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    int status = exchange.getResponse()
                            .getStatusCode() == null
                            ? 0
                            : exchange.getResponse()
                                    .getStatusCode()
                                    .value();

                    System.out.printf(
                            "%s %s -> %d (%dms)%n",
                            method,
                            path,
                            status,
                            System.currentTimeMillis() - start);
                }));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
```

`chain.filter(exchange)`：

```text
继续执行后续过滤器
最后转发到目标服务
```

`then(...)`：

```text
响应完成后记录日志
```

---

## 8. admin 用户级固定窗口限流

```java
@Component
public class UserRateLimitInterceptor
        implements HandlerInterceptor {

    private static final int LIMIT = 20;

    private final StringRedisTemplate redisTemplate;

    public UserRateLimitInterceptor(
            StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler) throws Exception {

        Long userId = UserContext.getUserId();
        String identity = userId == null
                ? request.getRemoteAddr()
                : String.valueOf(userId);

        long second =
                System.currentTimeMillis() / 1000;
        String key = "shortlink:ratelimit:user:"
                + identity + ":" + second;

        Long count = redisTemplate.opsForValue()
                .increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(
                    key, Duration.ofSeconds(2));
        }

        if (count != null && count > LIMIT) {
            response.setStatus(429);
            response.setContentType(
                    "application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":429,\"message\":\"请求过于频繁\"}");
            return false;
        }

        return true;
    }
}
```

底层：

```text
INCR shortlink:ratelimit:user:5:1720000000
EXPIRE key 2
```

---

## 9. 拦截器注册

```java
@Configuration
public class WebConfig
        implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final UserRateLimitInterceptor rateInterceptor;

    public WebConfig(
            LoginInterceptor loginInterceptor,
            UserRateLimitInterceptor rateInterceptor) {
        this.loginInterceptor = loginInterceptor;
        this.rateInterceptor = rateInterceptor;
    }

    @Override
    public void addInterceptors(
            InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register");

        registry.addInterceptor(rateInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/login",
                        "/api/auth/register");
    }
}
```

顺序：

```text
先登录
后限流
```

因为用户限流需要 `UserContext`。

---

## 10. Sentinel 依赖

```xml
<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>
        spring-cloud-starter-alibaba-sentinel
    </artifactId>
</dependency>

<dependency>
    <groupId>com.alibaba.csp</groupId>
    <artifactId>sentinel-annotation-aspectj</artifactId>
</dependency>
```

---

## 11. Sentinel 完整规则

```java
@Configuration
public class SentinelConfig {

    @Bean
    public SentinelResourceAspect sentinelResourceAspect() {
        return new SentinelResourceAspect();
    }

    @PostConstruct
    public void initRules() {
        FlowRule rule = new FlowRule();
        rule.setResource("create_short-link");
        rule.setGrade(1);
        rule.setCount(1.0);
        FlowRuleManager.loadRules(List.of(rule));
    }
}
```

解释：

```text
resource = create_short-link
grade = 1，QPS 模式
count = 1.0，每秒最多 1 次
```

---

## 12. Controller 使用 Sentinel

```java
@PostMapping
@SentinelResource(
    value = "create_short-link",
    blockHandler = "createBlockHandler",
    fallback = "createFallback")
public Result<LinkVO> create(
        @Valid @RequestBody LinkCreateReqDTO req) {
    return linkService.create(req);
}

public Result<LinkVO> createBlockHandler(
        LinkCreateReqDTO req,
        BlockException e) {
    return Result.fail(
            429, "创建请求过于频繁");
}

public Result<LinkVO> createFallback(
        LinkCreateReqDTO req,
        Throwable e) {
    return Result.fail(
            500, "服务暂时不可用");
}
```

执行：

```text
Controller 方法被 Sentinel AOP 代理
  -> 检查资源规则
  -> 超限进入 blockHandler
```

---

## 13. 三层限流执行顺序

```text
Gateway IP 令牌桶
  -> JWT 登录拦截
  -> 用户固定窗口
  -> Sentinel 接口 QPS
  -> Service
```

三层保护目标：

```text
IP       防止单一网络来源刷入口
用户     防止单个账号高频调用
接口     防止关键写接口整体过载
```

---

## 14. 从零实现顺序

```text
1. 接入 Nacos Discovery
2. 启动 admin/project 并确认注册成功
3. Gateway 配 lb:// 路由
4. 验证 /s 和 /api
5. 配 KeyResolver
6. 配 RequestRateLimiter
7. admin 中加入登录拦截器
8. 再加入用户级 Redis 限流
9. Sentinel 加规则和注解
10. 用并发请求验证 429
```

---

## 15. 本篇自检

```text
[ ] 能写 lb:// 路由
[ ] 能解释 Predicate 和 Filter
[ ] 能写 KeyResolver
[ ] 能解释令牌桶参数
[ ] 能写 Redis 固定窗口
[ ] 能解释拦截器顺序
[ ] 能写 SentinelResourceAspect
[ ] 能写 blockHandler
```
