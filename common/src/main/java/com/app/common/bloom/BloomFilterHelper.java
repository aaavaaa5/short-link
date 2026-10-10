package com.app.common.bloom;

import com.google.common.hash.HashFunction;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;

/**
 * 基于 Redis Bitmap 的布隆过滤器
 * <p>
 * 放在 common 模块的原因：跳转服务（project）需要判断"短码一定不存在"，
 * 管理端（admin）需要在新短码落库时写入位图。两边操作的是同一个 Redis key，
 * 哈希算法与参数必须完全一致——一旦两份实现出现偏差，过滤器会静默失效
 * （admin 写的位置和 project 查的位置对不上），所以收拢到公共模块。
 * <p>
 * 特性：说"不存在"一定准确，说"存在"可能是误判（false positive）。
 */
public class BloomFilterHelper {

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

    /**
     * 判断元素是否可能存在。
     * 返回 false 表示"一定不存在"，调用方可以直接拒绝，不必回源数据库。
     */
    public boolean mightContain(String value) {
        int[] positions = hash(value);
        for (int pos : positions) {
            if (Boolean.FALSE.equals(redisTemplate.opsForValue().getBit(key, pos))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 把元素加入位图。多次重复写入是幂等的。
     */
    public void add(String value) {
        int[] positions = hash(value);
        for (int pos : positions) {
            redisTemplate.opsForValue().setBit(key, pos, true);
        }
    }

    /**
     * 双重哈希（double hashing）：只做两次哈希运算，就能模拟出 hashCount 个相互独立的位置
     * position_i = (h1 + i * h2) % size
     */
    private int[] hash(String value) {
        int h1 = hashFunction.hashString(value, StandardCharsets.UTF_8).asInt();
        int h2 = hashFunction.hashString(value + ":" + salt, StandardCharsets.UTF_8).asInt();
        int[] result = new int[hashCount];
        for (int i = 0; i < hashCount; i++) {
            // 用 long 运算：i * h2 与 h1 + i * h2 都可能溢出 int，
            // 而且 Math.abs(Integer.MIN_VALUE) 仍是负数，会把负 offset 传给 SETBIT
            long position = Math.abs((h1 + (long) i * h2) % size);
            result[i] = (int) position;
        }
        return result;
    }
}
