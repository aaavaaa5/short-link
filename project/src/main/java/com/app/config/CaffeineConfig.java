package com.app.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;


@Configuration
public class CaffeineConfig {


    /**
     * L1 本地缓存，存放短链实体的 JSON
     * <p>
     * TTL 只给 60 秒：L1 在每个实例各存一份，admin 修改/删除短链时只能删 Redis（L2），
     * 通知不到 project 进程内的 L1。把 TTL 设短，可把"停用/删除"的生效延迟控制在 1 分钟内。
     */
    @Bean
    public Cache<String, String> shortLinkEntityCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofSeconds(60))
                .recordStats()
                .build();
    }
}
