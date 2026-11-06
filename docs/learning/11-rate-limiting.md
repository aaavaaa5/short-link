# 11 三层限流与 Sentinel

## 1. 本章解决什么问题

理解三种不同的限流为什么同时存在：

```text
Gateway IP 令牌桶
admin 用户固定窗口
Sentinel 接口 QPS
```

以及它们分别在代码里怎么写。

## 2. 三层限流的位置

```text
浏览器
  -> Gateway IP 限流
  -> admin JWT
  -> admin 用户限流
  -> Controller Sentinel
  -> Service
```

保护对象：

```text
IP：防单一来源刷入口
用户：防单个账号高频调用
接口：防关键写操作整体过载
```

## 3. 第一层：Gateway 令牌桶

依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

Gateway 使用 Redis 存储令牌桶状态。

路由配置：

```yaml
- name: RequestRateLimiter
  args:
    redis-rate-limiter.replenishRate: 100
    redis-rate-limiter.burstCapacity: 200
    redis-rate-limiter.requestedTokens: 1
    key-resolver: "#{@ipKeyResolver}"
```

参数：

```text
replenishRate：每秒补充多少令牌
burstCapacity：桶容量
requestedTokens：每个请求消耗多少令牌
key-resolver：按什么维度限流
```

## 4. KeyResolver

```java
@Bean
public KeyResolver ipKeyResolver() {
    return exchange -> {
        String ip = exchange.getRequest()
                .getRemoteAddress()
                .getAddress()
                .getHostAddress();
        return Mono.just(ip);
    };
}
```

`KeyResolver` 的返回值就是限流 Key。

按 IP：

```text
1.1.1.1
2.2.2.2
```

也可以按：

```text
用户 ID
API Key
IP + URI
租户 ID
```

## 5. 令牌桶算法

```text
桶容量 = 200
每秒补充 = 100
请求消耗 = 1
```

过程：

```text
根据时间差补充令牌
令牌数不超过容量
有令牌则扣减并放行
没有令牌则拒绝
```

特点：

```text
允许短时突发
控制长期平均速率
```

适合跳转接口这种可能突然有活动流量的场景。

## 6. 为什么用 Redis

多个 Gateway 实例必须共享限流状态。

否则：

```text
每个实例都有各自 200 个令牌
3 个实例实际可以放出 600 个
```

Redis + Lua 可以原子执行：

```text
读取令牌和时间
计算补充
扣减或拒绝
写回状态
```

## 7. 第二层：用户固定窗口

拦截器：

```java
@Component
public class UserFlowRiskCntlFilter
        implements HandlerInterceptor {
}
```

注册：

```java
registry.addInterceptor(loginInterceptor)
        .addPathPatterns("/api/**");

registry.addInterceptor(userFlowRiskCntlFilter)
        .addPathPatterns("/api/**");
```

登录拦截器要先执行，才能从 `UserContext` 取得 userId。

## 8. 固定窗口 Key

```java
String key = String.format(
        "shortlink:ratelimit:user:%d:%d",
        userId,
        System.currentTimeMillis() / 1000);
```

示例：

```text
shortlink:ratelimit:user:5:1790000000
```

当前秒相同就使用同一个 Key。

## 9. 计数

```java
Long count = redisTemplate.opsForValue()
        .increment(key);
if (count != null && count == 1) {
    redisTemplate.expire(key, Duration.ofSeconds(2));
}
```

底层：

```text
INCR key
EXPIRE key 2
```

超过阈值：

```java
response.setStatus(429);
return false;
```

## 10. 固定窗口的缺点

假设限制 20 次/秒。

在 12:00:00.999 请求 20 次，在 12:00:01.000 再请求 20 次：

```text
两个窗口都合法
短时间实际通过了 40 次
```

这就是窗口边界突发。

改进：

```text
滑动窗口
令牌桶
漏桶
```

## 11. 第三层：Sentinel

依赖：

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

启用注解切面：

```java
@Bean
public SentinelResourceAspect sentinelResourceAspect() {
    return new SentinelResourceAspect();
}
```

规则：

```java
FlowRule rule = new FlowRule();
rule.setResource("create_short-link");
rule.setGrade(1);
rule.setCount(1.0);
FlowRuleManager.loadRules(List.of(rule));
```

## 12. @SentinelResource

```java
@SentinelResource(
    value = "create_short-link",
    blockHandler = "createBlockHandler",
    fallback = "createFallback")
@PostMapping
public Result<LinkVO> create(
        @Valid @RequestBody LinkCreateReqDTO req) {
    return linkService.create(req);
}
```

区别：

```text
blockHandler：限流、熔断等 BlockException
fallback：业务异常或降级
```

处理限流：

```java
public Result<LinkVO> createBlockHandler(
        LinkCreateReqDTO req,
        BlockException ex) {
    return Result.fail(429, "请求过于频繁");
}
```

## 13. Sentinel 资源是什么

资源是一个被保护的名字：

```text
create_short-link
get_link_stats
redirect
```

规则绑定到资源名，不是绑定到 Java 方法名本身。

可以用注解：

```java
@SentinelResource("resource-name")
```

也可以手动：

```java
SphU.entry("resource-name");
```

## 14. 三种算法对比

| 算法 | 特点 |
|---|---|
| 固定窗口 | 简单，有边界突发 |
| 滑动窗口 | 更平滑，状态更多 |
| 令牌桶 | 允许突发，控制平均速率 |
| 漏桶 | 输出速率稳定 |

## 15. 同类限流方案

```text
Spring Cloud Gateway RequestRateLimiter
Sentinel
Resilience4j RateLimiter
Bucket4j
Nginx limit_req
Kong rate-limiting
APISIX limit-req
```

选择取决于：

```text
限流位置
是否需要集群共享
是否需要控制台
是否需要熔断降级
```

## 16. 本章自检

1. `replenishRate` 和 `burstCapacity` 分别是什么？
2. 为什么 Gateway 限流状态要放 Redis？
3. 用户固定窗口的 Key 怎么做？
4. 固定窗口为什么会有边界突发？
5. `blockHandler` 和 `fallback` 有什么区别？
6. 为什么登录拦截器必须先于用户限流拦截器？
7. 三层限流分别防什么？
