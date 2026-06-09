package com.app.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.time.Duration;

/**
 * Redis 配置 — StringRedisTemplate + Redisson 客户端
 * <p>
 * 职责：
 * <ul>
 *   <li>配置 {@link StringRedisTemplate}：Key/Value 统一使用 String 序列化，
 *       Hash/HyperLogLog/Bit/Stream 操作均使用原生 Redis 命令，无需额外序列化</li>
 *   <li>配置 {@link RedissonClient}：提供分布式锁、布隆过滤器（Redisson 版）、
 *       限流器等高级分布式组件</li>
 *   <li>配置 Lettuce 连接池：控制最大连接数、超时等</li>
 * </ul>
 * <p>
 * 为什么同时用 StringRedisTemplate 和 Redisson？
 * <ul>
 *   <li>StringRedisTemplate：轻量，适合高频读写（Bit/HyperLogLog/Stream 操作）</li>
 *   <li>Redisson：封装了分布式锁（RLock）、RBitSet、RHyperLogLog 等对象映射，
 *       适合需要语义化 API 的场景，且提供看门狗自动续期</li>
 * </ul>
 */
@Configuration
public class RedisConfig {

    // ==================== Redis 连接参数 ====================

    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.password:}")
    private String password;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Value("${spring.data.redis.timeout:3000}")
    private long timeout;

    @Value("${spring.data.redis.lettuce.pool.max-active:8}")
    private int maxActive;

    @Value("${spring.data.redis.lettuce.pool.max-idle:8}")
    private int maxIdle;

    @Value("${spring.data.redis.lettuce.pool.min-idle:2}")
    private int minIdle;

    @Value("${spring.data.redis.lettuce.pool.max-wait:3000}")
    private long maxWait;

    // ==================== Lettuce 连接工厂 ====================

    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 单机配置
        RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration();
        standaloneConfig.setHostName(host);
        standaloneConfig.setPort(port);
        standaloneConfig.setDatabase(database);
        if (password != null && !password.isBlank()) {
            standaloneConfig.setPassword(password);
        }

        // Lettuce 连接池配置
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(timeout))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.setShareNativeConnection(false); // 允许异步操作共享连接
        return factory;
    }

    // ==================== StringRedisTemplate ====================

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);

        // Key 序列化：String（可读性好，便于排查问题）
        // Value 序列化：String（Bit/HyperLogLog/Stream 均直接操作字符串/字节）
        // Hash Key/Value：保持默认 String 序列化
        RedisSerializer<String> stringSerializer = RedisSerializer.string();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);

        template.afterPropertiesSet();
        return template;
    }

    // ==================== Redisson 客户端 ====================

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        // 单机模式
        String address = "redis://" + host + ":" + port;
        config.useSingleServer()
                .setAddress(address)
                .setDatabase(database)
                .setTimeout((int) timeout)
                .setConnectionPoolSize(maxActive)
                .setConnectionMinimumIdleSize(minIdle);

        if (password != null && !password.isBlank()) {
            config.useSingleServer().setPassword(password);
        }

        return Redisson.create(config);
    }
}
