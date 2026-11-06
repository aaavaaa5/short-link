# Spring Boot Bean 装配完整实现

## 0. 实现目标

从零写一个项目，并理解：

```text
main 方法怎样启动
Controller/Service 怎样成为 Bean
@Configuration + @Bean 怎样创建对象
@Value 怎样读取 YAML
@Autowired 怎样注入
自动配置为什么会让位给手写 Bean
```

---

## 1. Maven 依赖

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.0.7</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## 2. 启动类

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(
                DemoApplication.class, args);
    }
}
```

`@SpringBootApplication` 包含：

```text
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan
```

---

## 3. 配置

```yaml
demo:
  name: shortlink
  max-size: 10
```

对应：

```text
demo.name = shortlink
demo.max-size = 10
```

---

## 4. 普通 Bean

```java
package com.example.demo;

import org.springframework.stereotype.Service;

@Service
public class DemoService {

    public String hello(String name) {
        return "hello " + name;
    }
}
```

执行过程：

```text
@ComponentScan 扫描到 @Service
  -> 注册 BeanDefinition
  -> 创建 DemoService
  -> 放入容器
```

---

## 5. Controller

```java
package com.example.demo;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo")
public class DemoController {

    private final DemoService demoService;

    public DemoController(DemoService demoService) {
        this.demoService = demoService;
    }

    @GetMapping("/hello")
    public String hello(
            @RequestParam String name) {
        return demoService.hello(name);
    }
}
```

构造器注入：

```text
Spring 创建 DemoController
  -> 发现构造器需要 DemoService
  -> 从容器取出 DemoService
  -> 调用构造器
```

---

## 6. 自己创建 Bean

```java
package com.example.demo;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DemoConfig {

    @Bean
    public DemoClient demoClient() {
        return new DemoClient("http://localhost");
    }
}
```

`DemoClient`：

```java
public class DemoClient {

    private final String baseUrl;

    public DemoClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }
}
```

Bean 名称：

```text
demoClient
```

Bean 类型：

```text
DemoClient
```

业务使用：

```java
@Service
public class ClientService {

    private final DemoClient demoClient;

    public ClientService(DemoClient demoClient) {
        this.demoClient = demoClient;
    }
}
```

---

## 7. @Value

```java
@Service
public class NameService {

    @Value("${demo.name:default}")
    private String name;

    @Value("${demo.max-size:100}")
    private int maxSize;

    public String info() {
        return name + ":" + maxSize;
    }
}
```

Spring 执行：

```text
读取 demo.name
  -> 类型转换
  -> 反射设置字段
```

更结构化的写法：

```java
@ConfigurationProperties(prefix = "demo")
@Component
public class DemoProperties {
    private String name;
    private int maxSize;
}
```

---

## 8. 自动配置为什么退让

很多 Spring Boot 自动配置都有：

```java
@ConditionalOnMissingBean
```

含义：

```text
用户没有自己创建这个 Bean
才创建默认 Bean
```

所以手写 `@Bean` 不是和自动配置抢对象，而是覆盖默认行为。

---

## 9. @Configuration 代理

```java
@Configuration
public class DemoConfig {

    @Bean
    public A a() {
        return new A();
    }

    @Bean
    public B b() {
        return new B(a());
    }
}
```

`b()` 中调用 `a()`：

```text
不是第二次 new A()
而是由 CGLIB 代理从容器返回同一个 A Bean
```

---

## 10. 验证

启动项目：

```bash
mvn spring-boot:run
```

请求：

```bash
curl 'http://localhost:8080/demo/hello?name=tom'
```

返回：

```text
hello tom
```

---

## 11. 从零写新 Bean 的顺序

```text
1. 新建普通类
2. 判断是不是自己管理的组件
3. 普通业务类加 @Service/@Component
4. 第三方对象用 @Configuration + @Bean
5. 用构造器或 @Autowired 注入
6. 读取配置用 @Value/@ConfigurationProperties
7. 启动后访问接口验证
```

---

## 12. 本篇自检

```text
[ ] 能解释 @SpringBootApplication 三个能力
[ ] 能写 @Service 和 @Configuration/@Bean
[ ] 能解释 Bean 名称和类型
[ ] 能用构造器注入
[ ] 能解释 @Value 的默认值
[ ] 能解释 @ConditionalOnMissingBean
```
