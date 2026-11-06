# 10 Gateway、Nacos 与微服务

## 1. 本章解决什么问题

理解项目为什么有三个可启动服务，以及浏览器为什么只访问 9000 端口。

本章覆盖：

```text
网关依赖
Nacos 服务注册
Gateway 路由
LoadBalancer
StripPrefix
CORS
全局过滤器
```

## 2. 模块拆分

```text
gateway  统一入口
admin    管理端
project  跳转端
```

拆分目标：

```text
跳转端读流量大，可以单独扩容
管理端写接口和统计任务不影响跳转
网关集中处理路由、跨域、限流
```

## 3. Gateway 依赖

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-loadbalancer</artifactId>
</dependency>

<dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>
        spring-cloud-starter-alibaba-nacos-discovery
    </artifactId>
</dependency>
```

作用：

```text
gateway          提供路由能力
loadbalancer     解析 lb:// 并选择实例
nacos-discovery  从 Nacos 读取实例列表
```

Gateway 基于 WebFlux，不是传统 Servlet MVC。
因此过滤器返回 `Mono<Void>`，而不是普通 `void`。

## 4. 服务注册

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
```

启动类：

```java
@EnableDiscoveryClient
```

服务启动后向 Nacos 注册：

```text
服务名 + IP + 端口 + 元数据
```

## 5. 路由配置

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: shortlink-project
          uri: lb://shortlink-project
          predicates:
            - Path=/s/**
          filters:
            - StripPrefix=1
```

### id

```text
路由唯一名称
```

### uri

```text
lb://shortlink-project
```

不是普通 HTTP URL。

```text
lb -> LoadBalancer
shortlink-project -> Nacos 中的服务名
```

## 6. Predicate

```yaml
predicates:
  - Path=/s/**
```

表示：

```text
只有 /s/ 开头的请求才匹配
```

另一个路由：

```yaml
predicates:
  - Path=/api/**
```

所以：

```text
/api/link -> admin
/s/abc123 -> project
```

常见 Predicate：

```text
Path
Method
Header
Query
Host
```

## 7. StripPrefix

请求：

```text
GET /s/abc123
```

`StripPrefix=1`：

```text
去掉路径第一段 /s
```

转发后：

```text
GET /abc123
```

project 的 Controller：

```java
@GetMapping("/{shortUri}")
```

正好接收。

## 8. 全局日志过滤器

```java
@Component
public class GlobalLogFilter
        implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(
            ServerWebExchange exchange,
            GatewayFilterChain chain) {
        long start = System.currentTimeMillis();
        return chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    // 记录路径、状态码、耗时
                }));
    }
}
```

关键：

```text
chain.filter(exchange)
```

表示继续执行后续过滤器和路由。

`then(...)`：

```text
等响应完成后再执行日志逻辑
```

`getOrder()`：

```text
控制多个 GlobalFilter 的执行顺序
```

## 9. CORS

浏览器跨域请求前会发送：

```text
OPTIONS
```

Gateway 配置：

```java
CorsConfiguration config = new CorsConfiguration();
config.setAllowedOriginPatterns(List.of("*"));
config.setAllowedMethods(
        List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
config.setAllowedHeaders(List.of("*"));
config.setAllowCredentials(true);
```

`CorsWebFilter` 把配置注册到响应式 Web 环境。

生产环境不应该无脑允许所有 Origin，尤其是同时允许凭证。

## 10. 服务发现和负载均衡

有两个 project 实例：

```text
project-1 192.168.1.10:8083
project-2 192.168.1.11:8083
```

Nacos 中：

```text
shortlink-project -> [project-1, project-2]
```

Gateway 收到：

```text
lb://shortlink-project
```

LoadBalancer：

```text
取得实例列表
  -> 按策略选择一个
  -> 将 lb:// 替换成 http://选中的实例
```

## 11. Nacos Config 和 Discovery 的区别

Discovery：

```text
服务注册和发现
```

Config：

```text
集中管理配置
```

本项目路由写在本地 YAML。
即使引入了 Nacos Config，也不代表路由已经动态下发。

这是面试和架构说明中需要区分的地方。

## 12. 网关适合放什么

适合：

```text
统一入口
路由
跨域
认证前置校验
IP 限流
请求日志
灰度路由
```

不适合：

```text
复杂业务逻辑
每请求大量数据库操作
重计算
```

## 13. 同类方案

| 方案 | 特点 |
|---|---|
| Spring Cloud Gateway | Java/Spring 体系，谓词和过滤器丰富 |
| Nginx | 成熟反向代理，配置式路由 |
| Kong | API 网关，插件生态 |
| APISIX | 高性能，动态配置 |
| Service Mesh | 把流量治理下沉到 Sidecar |

## 14. 本章自检

1. `lb://` 是什么意思？
2. Nacos Discovery 和 Config 有什么区别？
3. Predicate 和 Filter 分别做什么？
4. `StripPrefix=1` 对路径做了什么？
5. Gateway 为什么返回 `Mono<Void>`？
6. 服务实例变化后，路由为什么不用改代码？
7. 哪些逻辑适合放在网关？
