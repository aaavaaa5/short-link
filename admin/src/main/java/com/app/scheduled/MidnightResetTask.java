package com.app.scheduled;

import com.app.entity.LinkEntity;
import com.app.mapper.LinkMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 午夜清零任务
 * <p>
 * 每日凌晨 0:00 执行，将 t_link.todayPv / todayUv / todayIpCount 清零。
 * <p>
 * 为什么需要清零？
 * 这些字段通过 Redis 准实时同步到数据库（用于列表排序），
 * 新的一天开始时需从零开始计数。
 * <p>
 * 分布式锁：多实例部署时，只有拿到锁的实例执行清零，避免重复操作。
 */
@Component
public class MidnightResetTask {

    private static final Logger log = LoggerFactory.getLogger(MidnightResetTask.class);

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private RedissonClient redissonClient;

    private static final String LOCK_KEY = "shortlink:lock:midnight-reset";

    /** 每日凌晨 0:00 执行 */
    @Scheduled(cron = "0 0 0 * * ?")
    public void reset() {
        RLock lock = redissonClient.getLock(LOCK_KEY);
        // waitTime=0：拿不到锁立即放弃（说明另一个实例已经在执行了）
        // leaseTime=30s：万一进程崩溃，30 秒后自动释放
        try {
            if (lock.tryLock(0, 30, TimeUnit.SECONDS)) {
                try {
                    log.info("开始清零今日统计数据...");

                    LambdaUpdateWrapper<LinkEntity> wrapper = Wrappers.lambdaUpdate();
                    wrapper.set(LinkEntity::getTodayPv, 0);
                    wrapper.set(LinkEntity::getTodayUv, 0);
                    wrapper.set(LinkEntity::getTodayIpCount, 0);
                    linkMapper.update(null, wrapper);

                    log.info("今日统计数据清零完成");
                } finally {
                    lock.unlock();
                }
            } else {
                log.info("清零任务已被其他实例执行，跳过");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("清零任务被中断");
        }
    }
}
