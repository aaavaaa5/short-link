package com.app.service.Impl;

import com.alibaba.fastjson2.JSON;
import com.app.common.bloom.BloomFilterHelper;
import com.app.common.cache.CacheKeys;
import com.app.entity.LinkEntity;
import com.app.service.CacheService;
import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class CacheServiceImpl implements CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheServiceImpl.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    @Qualifier("shortLinkEntityCache")
    private Cache<String, String> localCache;

    @Autowired
    private BloomFilterHelper bloomFilterHelper;

    // ==================== L1 + L2 联合查询 ====================

    @Override
    public LinkEntity getLinkEntity(String shortUri) {
        // 1. L1 Caffeine 本地缓存（纳秒级，无网络开销）
        String json = localCache.getIfPresent(shortUri);
        if (json != null) {
            return parse(json, shortUri);
        }

        // 2. L2 Redis 分布式缓存（毫秒级，多实例共享）
        json = redisTemplate.opsForValue().get(CacheKeys.linkEntity(shortUri));
        if (json != null) {
            // 回写 L1，下次命中更快
            localCache.put(shortUri, json);
            return parse(json, shortUri);
        }

        return null;
    }

    @Override
    public void cacheLinkEntity(String shortUri, LinkEntity entity, Duration ttl) {
        String json = JSON.toJSONString(entity);
        // 同时写入 L1 和 L2
        localCache.put(shortUri, json);
        redisTemplate.opsForValue().set(CacheKeys.linkEntity(shortUri), json, ttl);
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

    // ==================== 空值缓存（防缓存穿透） ====================

    @Override
    public boolean isNullCached(String shortUri) {
        // 空值标记只放 L2（Redis），不进 L1：
        // Caffeine 的过期时间是缓存级别的（60 秒），而 Redis 的空值只留 1 分钟，
        // 放进 L1 会让"该短码不存在"这个结论比预期活得更久
        return Boolean.TRUE.equals(redisTemplate.hasKey(CacheKeys.nullMark(shortUri)));
    }

    @Override
    public void cacheNullStatus(String shortUri, Duration ttl) {
        redisTemplate.opsForValue().set(CacheKeys.nullMark(shortUri), "1", ttl);
    }

    // ==================== 私有方法 ====================

    /** 反序列化失败时按"未命中"处理，避免一条脏数据把跳转请求打挂 */
    private LinkEntity parse(String json, String shortUri) {
        try {
            return JSON.parseObject(json, LinkEntity.class);
        } catch (Exception e) {
            log.warn("缓存反序列化失败，按未命中处理: shortUri={}", shortUri, e);
            return null;
        }
    }
}
