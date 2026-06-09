package com.app.service.Impl;

import com.app.config.BloomFilterConfig.BloomFilterHelper;
import com.app.service.CacheService;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;


@Service
public class CacheServiceImpl implements CacheService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("shortLinkLocalCache")
    private Cache<String, String> localCache;

    @Autowired
    private BloomFilterHelper bloomFilterHelper;

    // ==================== Redis Key ====================

    private static final String CACHE_KEY_PREFIX = "shortlink:";

    // ==================== L1 + L2 联合查询 ====================

    @Override
    public String getOriginUrl(String shortUri) {
        // 1. L1 Caffeine 本地缓存（纳秒级，无网络开销）
        String url = localCache.getIfPresent(shortUri);
        if (url != null) {
            return url;
        }

        // 2. L2 Redis 分布式缓存（毫秒级，多实例共享）
        url = redisTemplate.opsForValue().get(CACHE_KEY_PREFIX + shortUri);
        if (url != null) {
            // 回写 L1，下次命中更快
            localCache.put(shortUri, url);
            return url;
        }

        return null;
    }

    @Override
    public void cacheOriginUrl(String shortUri, String originUrl, Duration ttl) {
        // 同时写入 L1 和 L2
        localCache.put(shortUri, originUrl);
        redisTemplate.opsForValue().set(CACHE_KEY_PREFIX + shortUri, originUrl, ttl);
    }

    // ==================== 布隆过滤器（委托给 BloomFilterHelper） ====================

    @Override
    public boolean mightExist(String shortUri) {
        return bloomFilterHelper.mightContain(shortUri);
    }

    @Override
    public void addToBloomFilter(String shortUri) {
        bloomFilterHelper.add(shortUri);
    }
}
