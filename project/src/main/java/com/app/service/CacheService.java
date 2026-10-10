package com.app.service;

import java.time.Duration;

/**
 * 多级缓存服务 — L1 Caffeine → L2 Redis → 布隆过滤器
 * <p>
 * 为什么需要多级缓存？
 * <ul>
 *   <li>L1（Caffeine）：JVM 堆内存，纳秒级，但重启丢失、容量有限</li>
 *   <li>L2（Redis）：独立进程，毫秒级，多实例共享、持久化</li>
 *   <li>布隆过滤器：防缓存穿透，极小内存判断"一定不存在"</li>
 * </ul>
 */
public interface CacheService {

    // ========== L1 + L2 联合查询 ==========


    String getOriginUrl(String shortUri);


    void cacheOriginUrl(String shortUri, String originUrl, Duration ttl);

    // ========== 布隆过滤器 ==========

    boolean mightExist(String shortUri);

    void addToBloomFilter(String shortUri);

    // ========== 空值缓存（防缓存穿透） ==========

    boolean isNullCached(String shortUri);

    void cacheNullStatus(String shortUri, Duration ttl);
}
