package com.app.scheduled;

import com.app.entity.AccessStatsEntity;
import com.app.entity.LinkEntity;
import com.app.mapper.AccessStatsMapper;
import com.app.mapper.LinkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 每日统计聚合任务
 * <p>
 * 每日凌晨 1:00 执行，将前一日 Redis 实时计数器汇总写入 t_access_stats。
 * <p>
 * 聚合来源：
 * <ul>
 *   <li>PV：Redis INCR 计数器 shortlink:click:{linkId}:{date}:pv</li>
 *   <li>UV：Redis HyperLogLog shortlink:click:{linkId}:{date}:uv</li>
 *   <li>IP：Redis HyperLogLog shortlink:click:{linkId}:{date}:ip</li>
 * </ul>
 * <p>
 * 同时更新 t_link.totalPv/totalUv/totalIpCount 累计字段。
 * <p>
 * 分布式锁：多实例部署时，只有拿到锁的实例执行聚合，避免 totalPv 重复累加。
 */
@Component
public class StatsAggregationTask {

    private static final Logger log = LoggerFactory.getLogger(StatsAggregationTask.class);

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private AccessStatsMapper accessStatsMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private static final String CLICK_KEY = "shortlink:click:%d:%s:%s";
    private static final String LOCK_KEY = "shortlink:lock:stats-aggregation";

    /** 每日凌晨 1:00 执行 */
    @Scheduled(cron = "0 0 1 * * ?")
    public void aggregate() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        // waitTime=0：拿不到锁立即放弃（说明另一个实例已经在执行了）
        // leaseTime=1800s（30分钟）：聚合任务可能耗时较长，给足安全超时时间
        try {
            if (lock.tryLock(0, 1800, TimeUnit.SECONDS)) {
                try {
                    doAggregate();
                } finally {
                    lock.unlock();
                }
            } else {
                log.info("聚合任务已被其他实例执行，跳过");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("聚合任务被中断");
        }
    }

    private void doAggregate() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("开始聚合 {} 的访问统计数据...", yesterday);

        // 查询所有未删除的短链接
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.isNotNull(LinkEntity::getId);
        List<LinkEntity> links = linkMapper.selectList(wrapper);

        int count = 0;
        for (LinkEntity link : links) {
            try {
                aggregateLink(link, yesterday);
                count++;
            } catch (Exception e) {
                log.error("聚合短链接 {} 统计失败", link.getId(), e);
            }
        }

        log.info("聚合完成，共处理 {} 条短链接", count);
    }

    // ==================== 私有方法 ====================

    private void aggregateLink(LinkEntity link, LocalDate date) {
        String dateStr = date.toString();

        // 1. 从 Redis 读取昨日数据
        long yesterdayPv = getRedisLong(String.format(CLICK_KEY, link.getId(), dateStr, "pv"));
        long yesterdayUv = getRedisHll(String.format(CLICK_KEY, link.getId(), dateStr, "uv"));
        long yesterdayIp = getRedisHll(String.format(CLICK_KEY, link.getId(), dateStr, "ip"));

        // 2. 写入 t_access_stats（存在则更新）
        LambdaQueryWrapper<AccessStatsEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AccessStatsEntity::getLinkId, link.getId());
        wrapper.eq(AccessStatsEntity::getStatsDate, date);
        AccessStatsEntity stats = accessStatsMapper.selectOne(wrapper);

        // 本次聚合前，该日期已计入累计值的数值；用于计算增量，保证任务重复执行不会重复累加
        long previousPv = (stats != null && stats.getPv() != null) ? stats.getPv() : 0L;
        long previousUv = (stats != null && stats.getUv() != null) ? stats.getUv() : 0L;
        long previousIp = (stats != null && stats.getIpCount() != null) ? stats.getIpCount() : 0L;

        if (stats == null) {
            stats = new AccessStatsEntity();
            stats.setLinkId(link.getId());
            stats.setLinkGid(link.getGid());
            stats.setStatsDate(date);
            stats.setPv(yesterdayPv);
            stats.setUv(yesterdayUv);
            stats.setIpCount(yesterdayIp);
            stats.setCreateTime(LocalDateTime.now());
            accessStatsMapper.insert(stats);
        } else {
            stats.setPv(yesterdayPv);
            stats.setUv(yesterdayUv);
            stats.setIpCount(yesterdayIp);
            stats.setUpdateTime(LocalDateTime.now());
            accessStatsMapper.updateById(stats);
        }

        // 3. 更新 t_link 累计数据（按增量累加：重复执行同一天时增量为 0，保证幂等）
        link.setTotalPv((link.getTotalPv() != null ? link.getTotalPv() : 0L) + (yesterdayPv - previousPv));
        link.setTotalUv((link.getTotalUv() != null ? link.getTotalUv() : 0L) + (yesterdayUv - previousUv));
        link.setTotalIpCount((link.getTotalIpCount() != null ? link.getTotalIpCount() : 0L) + (yesterdayIp - previousIp));
        linkMapper.updateById(link);
    }

    private long getRedisLong(String key) {
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0L;
    }

    private long getRedisHll(String key) {
        Long size = redisTemplate.opsForHyperLogLog().size(key);
        return size != null ? size : 0L;
    }
}
