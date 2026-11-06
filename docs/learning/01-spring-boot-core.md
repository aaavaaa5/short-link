# 01 Spring Boot 基础与 Bean 装配

## 1. 本章解决什么问题

看懂下面这些问题：

```text
main 方法如何启动项目？
@SpringBootApplication 做了什么？
@Configuration 和 @Bean 有什么关系？
方法没有继承接口，为什么能提供 Bean？
YAML 中的值如何进入 Java 字段？
@Autowired 按什么规则找对象？
为什么自动配置和手写配置不会冲突？
```

## 2. Maven 多模块

根工程在 `pom.xml`：

```xml
<modules>
    <module>common</module>
    <module>admin</module>
    <module>project</module>
    <module>gateway</module>
</modules>
```

子模块可以依赖共享模块：

```xml
<dependency>
    <groupId>com.app</groupId>
    <artifactId>common</artifactId>
    <version>${project.version}</version>
</dependency>
```

`dependencyManagement` 统一管理版本，子模块引用依赖时可以省略版本号。

## 3. 启动类如何启动

示例：

```java
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.app.mapper")
public class shortlinkApplication {
    public static void main(String[] args) {
        SpringApplication.run(shortlinkApplication.class, args);
    }
}
```

`@SpringBootApplication` 是组合注解，主要包含：

```text
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
```

含义：

```text
@ComponentScan       扫描当前包及子包中的 @Component/@Service/@RestController/@Configuration
@EnableAutoConfiguration 加载 Spring Boot 自动配置候选
@SpringBootConfiguration 标记这是配置类
```

启动流程可以简化为：

```text
创建 SpringApplication
  -> 准备 Environment（读取 YAML、环境变量、命令行参数）
  -> 创建 ApplicationContext
  -> 扫描 Bean
  -> 处理自动配置
  -> 创建单例 Bean
  -> 启动 Web 服务器
  -> 执行 ApplicationRunner/CommandLineRunner
```

## 4. @Component、@Service、@RestController

这些注解本质上都是把类注册为 Bean：

```java
@Service
public class LinkServiceImpl implements LinkService {
}
```

```java
@RestController
public class LinkController {
}
```

Spring 扫描到类以后：

```text
创建 BeanDefinition
  -> 实例化
  -> 依赖注入
  -> 初始化回调
  -> 放入单例池
```

字段注入示例：

```java
@Autowired
private LinkMapper linkMapper;
```

Spring 按类型查找 `LinkMapper`。

## 5. @Configuration 和 @Bean

示例：

```java
@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<String, String> shortLinkEntityCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .build();
    }
}
```

### 5.1 @Configuration 是什么

它告诉 Spring：

```text
这是一个配置类
请扫描其中的 @Bean 方法
```

### 5.2 @Bean 是什么

`@Bean` 标记的方法是一个工厂方法：

```java
@Bean
public Cache<String, String> shortLinkEntityCache() {
    return ...;
}
```

Spring 会：

```text
1. 记录方法名为 Bean 名称
2. 记录返回类型为 Bean 类型
3. 创建对象时通过反射调用方法
4. 把返回值放入容器
```

它不需要实现接口。
Java 本身就是通过方法返回对象，Spring 只是把这个方法纳入 Bean 生命周期。

### 5.3 方法参数如何注入

```java
@Bean
public StringRedisTemplate stringRedisTemplate(
        RedisConnectionFactory connectionFactory) {
}
```

Spring 创建这个 Bean 前：

```text
发现参数类型是 RedisConnectionFactory
  -> 在容器中查找匹配 Bean
  -> 将对象作为参数传入
  -> 调用方法
```

### 5.4 @Configuration 为什么会被代理

默认 `proxyBeanMethods = true`。
Spring 会用 CGLIB 增强配置类，保证多次调用同一个 `@Bean` 方法仍返回同一个单例。

示例：

```java
@Configuration
public class A {
    @Bean
    public B b() {
        return new B();
    }

    @Bean
    public C c() {
        return new C(b());
    }
}
```

`c()` 中调用 `b()` 时，代理会从容器中取已有单例，而不是每次 new。

## 6. 自动配置和手写配置

Spring Boot 自动配置不是“把 YAML 自动变对象”这么简单。

它的大致过程：

```text
starter 引入类
  -> AutoConfiguration.imports 登记自动配置类
  -> 条件注解判断是否生效
  -> 创建默认 Bean
```

常见条件：

```java
@ConditionalOnClass(RedisOperations.class)
@ConditionalOnMissingBean(RedisConnectionFactory.class)
@ConditionalOnProperty(name = "spring.data.redis.host")
```

用户手写 Bean 后，默认 Bean 会退让：

```text
自动配置：用户没有定义时才创建
手写配置：用户明确定义
```

所以 `RedisConfig` 中的 `redisConnectionFactory` 和 `stringRedisTemplate` 不会和 Boot 默认配置重复。

## 7. @Value

示例：

```java
@Value("${spring.data.redis.host:localhost}")
private String host;
```

拆解：

```text
${属性名:默认值}
```

Spring 通过 `PropertySourcesPlaceholderConfigurer` 从 Environment 中解析占位符。

来源可以是：

```text
application.yml
application.properties
环境变量
命令行参数
Nacos 配置中心
```

类型转换由 Spring 负责：

```java
@Value("${spring.data.redis.port:6379}")
private int port;
```

字符串 `"6379"` 会转换为 `int`。

如果一组配置很多，可用：

```java
@ConfigurationProperties(prefix = "spring.data.redis")
public class RedisProperties {
    private String host;
    private int port;
}
```

它比大量 `@Value` 更适合结构化配置。

## 8. @Autowired 和 @Qualifier

```java
@Autowired
private StringRedisTemplate redisTemplate;
```

注入规则：

```text
按类型查找
  -> 0 个：失败
  -> 1 个：注入
  -> 多个：结合 @Qualifier、@Primary、Bean 名称
```

多个同类型 Bean：

```java
@Autowired
@Qualifier("shortLinkEntityCache")
private Cache<String, String> localCache;
```

`@Qualifier` 指定 Bean 名称。

生产代码更推荐构造器注入：

```java
public CacheServiceImpl(
        StringRedisTemplate redisTemplate,
        @Qualifier("shortLinkEntityCache")
        Cache<String, String> localCache) {
    this.redisTemplate = redisTemplate;
    this.localCache = localCache;
}
```

好处：

```text
依赖清晰
对象创建后不可变
单元测试不需要启动 Spring
```

## 9. Bean 生命周期

简化流程：

```text
实例化
  -> 属性注入
  -> Aware 回调
  -> BeanPostProcessor 前置
  -> 初始化方法
  -> BeanPostProcessor 后置
  -> 使用
  -> 销毁
```

示例：

```java
@Bean(destroyMethod = "shutdown")
public RedissonClient redissonClient() {
}
```

上下文关闭时，Spring 调用 `redissonClient.shutdown()`。

`InitializingBean`：

```java
public interface InitializingBean {
    void afterPropertiesSet();
}
```

`RedisTemplate` 实现了它，所以可以显式调用：

```java
template.afterPropertiesSet();
```

这个方法用于检查必要属性和初始化，不是立即连接 Redis。

## 10. 本项目常见注解

| 注解 | 作用 |
|---|---|
| `@Service` | 业务层 Bean |
| `@RestController` | JSON API Controller |
| `@Controller` | 页面 Controller |
| `@RequestMapping` | URL 前缀 |
| `@GetMapping` / `@PostMapping` | HTTP 方法映射 |
| `@PathVariable` | URL 路径变量 |
| `@RequestBody` | JSON 请求体转对象 |
| `@Valid` | 参数校验 |
| `@Configuration` | 配置类 |
| `@Bean` | 工厂方法 |
| `@Value` | 注入单个配置值 |
| `@Autowired` | 依赖注入 |
| `@Qualifier` | 指定 Bean 名称 |
| `@Scheduled` | 定时任务 |
| `@SentinelResource` | Sentinel 资源保护 |

## 11. 如何追踪一个请求

以 `POST /api/link` 为例：

```text
Gateway Path=/api/** 匹配
  -> admin LoginInterceptor
  -> UserFlowRiskCntlFilter
  -> LinkController.create
  -> Sentinel 拦截
  -> LinkServiceImpl.create
  -> LinkMapper.insert
```

在 IDE 中可以从 Controller 方法逐层进入。
遇到接口时使用 `Ctrl+Alt+B` 或 “Go to Implementation” 找实现类。

## 12. 本章自检

能独立解释以下问题：

1. `@SpringBootApplication` 包含哪些能力？
2. 一个普通类为什么加 `@Service` 就能被注入？
3. `@Bean` 方法和普通方法有什么区别？
4. Spring 怎么知道 `@Bean` 返回什么类型？
5. `@ConditionalOnMissingBean` 为什么重要？
6. `@Value` 如何把 YAML 中的字符串转成 `int`？
7. `@Autowired` 和 `@Qualifier` 如何共同决定注入哪个 Bean？
8. 构造器注入为什么更方便测试？
