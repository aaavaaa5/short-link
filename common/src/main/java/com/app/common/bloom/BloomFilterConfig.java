package com.app.common.bloom;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 布隆过滤器装配
 * <p>
 * 参数从配置读取。admin 与 project 的 application.yml 必须配置完全相同的
 * size / hash-count / salt / key，否则两个服务会操作不同的位图。
 */
@Configuration
public class BloomFilterConfig {

    /** Bitmap 大小: 2^25 = 33,554,432 bits ≈ 4 MB */
    @Value("${shortlink.bloom-filter.size:33554432}")
    private int size;

    /** 哈希函数数量（越多误判率越低，但 Redis 操作次数越多） */
    @Value("${shortlink.bloom-filter.hash-count:3}")
    private int hashCount;

    /** 哈希加盐值，防止单一 hash 冲突 */
    @Value("${shortlink.bloom-filter.salt:default}")
    private String salt;

    /** Redis 中布隆过滤器的 Key */
    @Value("${shortlink.bloom-filter.key:shortlink:bf:short_uri}")
    private String bloomFilterKey;

    /** 使用固定种子 0 的 murmur3_32，保证多实例计算结果一致 */
    private static final HashFunction MURMUR3 = Hashing.murmur3_32(0);

    @Bean
    public BloomFilterHelper bloomFilterHelper(StringRedisTemplate redisTemplate) {
        return new BloomFilterHelper(redisTemplate, size, hashCount, salt, bloomFilterKey, MURMUR3);
    }
}
