package com.app.scheduled;

import com.app.entity.LinkEntity;
import com.app.mapper.LinkMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 午夜清零任务
 * <p>
 * 每日凌晨 0:00 执行，将 t_link.todayPv / todayUv / todayIpCount 清零。
 * <p>
 * 为什么需要清零？
 * 这些字段通过 Redis 准实时同步到数据库（用于列表排序），
 * 新的一天开始时需从零开始计数。
 */
@Component
public class MidnightResetTask {

    private static final Logger log = LoggerFactory.getLogger(MidnightResetTask.class);

    @Autowired
    private LinkMapper linkMapper;

    /** 每日凌晨 0:00 执行 */
    @Scheduled(cron = "0 0 0 * * ?")
    public void reset() {
        log.info("开始清零今日统计数据...");

        LambdaUpdateWrapper<LinkEntity> wrapper = Wrappers.lambdaUpdate();
        wrapper.set(LinkEntity::getTodayPv, 0);
        wrapper.set(LinkEntity::getTodayUv, 0);
        wrapper.set(LinkEntity::getTodayIpCount, 0);
        linkMapper.update(null, wrapper);

        log.info("今日统计数据清零完成");
    }
}
