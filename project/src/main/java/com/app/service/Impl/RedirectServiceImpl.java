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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;


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

    private static final Logger log = LoggerFactory.getLogger(RedirectServiceImpl.class);

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
            log.debug("缓存命中: shortUri={}", shortUri);
            // 缓存命中但仍需 DB 获取完整实体（enableStatus / delFlag / expireTime）
            return queryFromDb(shortUri);
        }

        // 布隆过滤器判否 → 仍走 DB 兜底（防止误判导致新短链接永远 404）
        if (!cacheService.mightExist(shortUri)) {
            log.debug("布隆过滤器判否，走 DB 兜底: shortUri={}", shortUri);
        }

        // MySQL 兜底 + 回写缓存
        log.debug("缓存未命中，查 DB: shortUri={}", shortUri);
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

        // UV: 单链接内去重（visitorId = IP + UA 的 hash，同一人访问同一链接只算一次UV）
        String visitorId = accessEventProducer.generateVisitorId(request);
        redisTemplate.opsForHyperLogLog().add(uvKey, visitorId);
        redisTemplate.expire(uvKey, Duration.ofDays(3));

        // IP: 单链接内去重（同一IP访问同一链接只算一次）
        String clientIp = accessEventProducer.getClientIp(request);
        redisTemplate.opsForHyperLogLog().add(ipKey, clientIp);
        redisTemplate.expire(ipKey, Duration.ofDays(3));
    }

    // ==================== 私有方法 ====================

    private LinkEntity queryFromDb(String shortUri) {
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(LinkEntity::getShortUri, shortUri);
        // ShardingSphere 按 shortUri 查询无分片键 gid，会广播到所有分片
        // selectList 比 selectOne 在广播场景下更稳定（避免多结果异常）
        try {
            List<LinkEntity> results = linkMapper.selectList(wrapper);
            if (results != null && !results.isEmpty()) {
                if (results.size() > 1) {
                    log.warn("shortUri={} 在多个分片中查到 {} 条记录，取第一条", shortUri, results.size());
                }
                return results.get(0);
            }
            return null;
        } catch (Exception e) {
            log.error("查询短链接失败: shortUri={}", shortUri, e);
            return null;
        }
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
