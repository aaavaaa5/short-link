package com.app.service.Impl;

import cn.hutool.core.util.StrUtil;
import com.app.entity.LinkEntity;
import com.app.mapper.LinkMapper;
import com.app.mq.AccessEventProducer;
import com.app.service.CacheService;
import com.app.service.RedirectService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Service
public class RedirectServiceImpl implements RedirectService {

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private CacheService cacheService;

    @Autowired
    private AccessEventProducer accessEventProducer;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    // ==================== 短链接查询 ====================

    @Override
    public LinkEntity getLinkEntity(String shortUri) {
        if (StrUtil.isBlank(shortUri)) {
            return null;
        }

        // L1 Caffeine → L2 Redis（CacheService 内部已串联）
        String cachedUrl = cacheService.getOriginUrl(shortUri);
        if (cachedUrl != null) {
            // 缓存命中但仍需 DB 获取完整实体（enableStatus / delFlag / expireTime）
            // 优化方向：将完整实体序列化为 JSON 存 Redis
            return queryFromDb(shortUri);
        }

        // 布隆过滤器判否 → 直接返回，防 DB 穿透
        if (!cacheService.mightExist(shortUri)) {
            return null;
        }

        // MySQL 兜底 + 回写缓存
        LinkEntity entity = queryFromDb(shortUri);
        if (entity != null) {
            Duration ttl = calcCacheTtl(entity);
            cacheService.cacheOriginUrl(shortUri, entity.getOriginUrl(), ttl);
            cacheService.addToBloomFilter(shortUri);
        }
        return entity;
    }

    // ==================== 访问记录 ====================

    @Override
    public void recordAccess(LinkEntity entity, HttpServletRequest request) {
        if (entity == null || request == null) {
            return;
        }
        // 1. Redis 实时计数器（同步，Redis 纳秒级操作，不影响跳转性能）
        incrementCounters(entity, request);

        // 2. Redis Stream 消息（异步消费：UA/GeoIP 解析 → 写 t_access_log）
        accessEventProducer.sendAccessEvent(entity, request);
    }


    private void incrementCounters(LinkEntity entity, HttpServletRequest request) {
        String today = LocalDate.now().toString();
        String pvKey = String.format("shortlink:click:%d:%s:pv", entity.getId(), today);
        String uvKey = String.format("shortlink:click:%d:%s:uv", entity.getId(), today);
        String ipKey = String.format("shortlink:click:%d:%s:ip", entity.getId(), today);

        // PV: 原子自增
        redisTemplate.opsForValue().increment(pvKey);
        redisTemplate.expire(pvKey, Duration.ofDays(3));

        // UV: HyperLogLog 去重（visitorId = IP + UA 的 hash）
        String visitorId = accessEventProducer.generateVisitorId(request);
        redisTemplate.opsForHyperLogLog().add(uvKey, visitorId);
        redisTemplate.expire(uvKey, Duration.ofDays(3));

        // IP: HyperLogLog 去重
        String clientIp = accessEventProducer.getClientIp(request);
        redisTemplate.opsForHyperLogLog().add(ipKey, clientIp);
        redisTemplate.expire(ipKey, Duration.ofDays(3));
    }

    // ==================== 私有方法 ====================

    private LinkEntity queryFromDb(String shortUri) {
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(LinkEntity::getShortUri, shortUri);
        return linkMapper.selectOne(wrapper);
    }

    private Duration calcCacheTtl(LinkEntity entity) {
        if (entity.getExpireTime() != null) {
            LocalDateTime now = LocalDateTime.now();
            if (entity.getExpireTime().isAfter(now)) {
                long seconds = Duration.between(now, entity.getExpireTime()).getSeconds();
                long cacheSeconds = Math.min(seconds, CACHE_TTL.getSeconds());
                return Duration.ofSeconds(Math.max(cacheSeconds, 1));
            }
        }
        return CACHE_TTL;
    }
}
