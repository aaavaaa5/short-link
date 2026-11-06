# 12 定时任务

## 1. 本章解决什么问题

理解项目中的定时任务：

```text
AccessEventConsumer  每秒消费 Stream
StatsAggregationTask 每天 01:00 聚合统计
MidnightResetTask    每天 00:00 清零今日字段
```

以及多实例下为什么必须加锁。

## 2. 开启定时任务

启动类：

```java
@SpringBootApplication
@EnableScheduling
public class userManageApplication {
}
```

没有 `@EnableScheduling`，`@Scheduled` 不会执行。

## 3. 三种常见写法

```java
@Scheduled(fixedDelay = 1000)
public void fixedDelayTask() {
}
```

```text
上一次结束后等待 1 秒
```

```java
@Scheduled(fixedRate = 1000)
public void fixedRateTask() {
}
```

```text
按固定频率触发
```

```java
@Scheduled(cron = "0 0 1 * * ?")
public void cronTask() {
}
```

```text
每天 01:00 执行
```

## 4. Cron 基础

Spring 六位 Cron：

```text
秒 分 时 日 月 周
```

示例：

```text
0 0 1 * * ?       每天 01:00
0 */5 * * * ?     每 5 分钟
0 0 0 * * ?       每天 00:00
0 0 9 ? * MON-FRI 工作日 09:00
```

`?` 常用于“日”和“周”互斥的位置。

## 5. Stream 消费任务

```java
@Scheduled(fixedDelay = 1000)
public void consume() {
    consumePending();
    consumeNew();
}
```

为什么用 `fixedDelay`：

```text
上一轮可能处理很多消息
结束后再等 1 秒，避免任务堆积
```

如果一轮超过 1 秒，不会同时启动第二轮。

## 6. 统计聚合任务

```java
@Scheduled(cron = "0 0 1 * * ?")
public void aggregate() {
    RLock lock = redissonClient.getLock(
            "shortlink:lock:stats-aggregation");

    if (lock.tryLock(0, 1800, TimeUnit.SECONDS)) {
        try {
            doAggregate();
        } finally {
            lock.unlock();
        }
    }
}
```

任务：

```text
读取昨日 Redis 计数
写 t_access_stats
累加 t_link 总量
```

## 7. 为什么定时任务需要分布式锁

如果部署三个 admin 实例：

```text
admin1 01:00 执行
admin2 01:00 执行
admin3 01:00 执行
```

没有锁时：

```text
累计值可能被重复增加
重置任务重复执行
```

`@Scheduled` 只保证“每个实例执行”，不保证“集群只执行一次”。

## 8. 零点重置任务

```java
@Scheduled(cron = "0 0 0 * * ?")
public void reset() {
    // 将 t_link.today_* 清零
}
```

目前查询今日数据更多依赖 Redis。
数据库字段更多是快照或备用数据。

## 9. 单线程调度问题

Spring 默认调度线程池通常很小。
一个长时间任务可能影响其他 `@Scheduled` 任务。

配置示例：

```java
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler =
                new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.initialize();
        return scheduler;
    }
}
```

对于本项目当前任务量，默认配置可工作。
生产环境应评估任务耗时和线程隔离。

## 10. 幂等

定时任务也可能重复执行：

```text
锁过期
人工重跑
实例重启
调度抖动
```

聚合任务使用增量：

```text
本次值 - 已写入值
```

避免重复累加。

## 11. 更复杂的调度

如果任务很多，可以考虑：

```text
Quartz
XXL-JOB
ElasticJob
Kubernetes CronJob
云厂商调度服务
```

它们提供：

```text
任务管理界面
失败重试
分片调度
执行日志
告警
```

## 12. 本章自检

1. `fixedDelay` 和 `fixedRate` 有什么区别？
2. 为什么 `@Scheduled` 不会自动保证集群只执行一次？
3. Cron 六位分别是什么？
4. 为什么 Stream 消费使用 `fixedDelay`？
5. 统计任务如何避免重复累计？
6. 长任务为什么会阻塞其他定时任务？
7. 什么情况下应该换成 XXL-JOB 或 Quartz？
