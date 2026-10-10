package com.app.service;

import com.app.entity.LinkEntity;

import java.time.Duration;

/**
 * 多级缓存服务 — L1 Caffeine → L2 Redis → 布隆过滤器
 * <p>
 * 缓存里放的是整个 LinkEntity 的 JSON，命中后直接反序列化返回，不再回表查库。
 * <ul>
 *   <li>L1（Caffeine）：JVM 堆内存，纳秒级；但重启丢失、容量有限、跨实例无法主动失效</li>
 *   <li>L2（Redis）：独立进程，毫秒级；多实例共享，可由 admin 在写操作后主动删除</li>
 *   <li>布隆过滤器：判断"短码一定不存在"，防缓存穿透</li>
 * </ul>
 */
public interface CacheService {

    // ========== L1 + L2 联合查询 ==========

    /** 读取缓存的短链实体，未命中返回 null */
    LinkEntity getLinkEntity(String shortUri);

    /** 写入缓存（同时写 L1 和 L2） */
    void cacheLinkEntity(String shortUri, LinkEntity entity, Duration ttl);

    // ========== 布隆过滤器 ==========

    boolean mightExist(String shortUri);

    void addToBloomFilter(String shortUri);

    // ========== 空值缓存（防缓存穿透） ==========

    boolean isNullCached(String shortUri);

    void cacheNullStatus(String shortUri, Duration ttl);
}
