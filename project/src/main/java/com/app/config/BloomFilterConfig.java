package com.app.config;

import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;


@Configuration
public class BloomFilterConfig {

    // ==================== 布隆过滤器参数 ====================

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

    // ==================== Bean 定义 ====================

    @Bean
    public BloomFilterHelper bloomFilterHelper(StringRedisTemplate redisTemplate) {
        return new BloomFilterHelper(redisTemplate, size, hashCount, salt, bloomFilterKey, MURMUR3);
    }

    // ==================== Helper 类 ====================

    public static class BloomFilterHelper {

        private final StringRedisTemplate redisTemplate;
        private final int size;
        private final int hashCount;
        private final String salt;
        private final String key;
        private final HashFunction hashFunction;

        public BloomFilterHelper(StringRedisTemplate redisTemplate, int size,
                                  int hashCount, String salt, String key,
                                  HashFunction hashFunction) {
            this.redisTemplate = redisTemplate;
            this.size = size;
            this.hashCount = hashCount;
            this.salt = salt;
            this.key = key;
            this.hashFunction = hashFunction;
        }


        public boolean mightContain(String value) {
            int[] positions = hash(value);
            for (int pos : positions) {
                if (Boolean.FALSE.equals(redisTemplate.opsForValue().getBit(key, pos))) {
                    return false;
                }
            }
            return true;
        }

        public void add(String value) {
            int[] positions = hash(value);
            for (int pos : positions) {
                redisTemplate.opsForValue().setBit(key, pos, true);
            }
        }

        // ==================== 私有方法 ====================


        private int[] hash(String value) {
            int h1 = hashFunction.hashString(value, StandardCharsets.UTF_8).asInt();
            int h2 = hashFunction.hashString(value + ":" + salt, StandardCharsets.UTF_8).asInt();
            int[] result = new int[hashCount];
            for (int i = 0; i < hashCount; i++) {
                result[i] = Math.abs((h1 + i * h2) % size);
            }
            return result;
        }
    }
}
