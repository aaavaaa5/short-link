package com.app.service.Impl;

import cn.hutool.core.util.StrUtil;
import com.app.dto.resp.StatsVO;
import com.app.dto.resp.StatsVO.DailyStat;
import com.app.entity.AccessStatsEntity;
import com.app.entity.LinkEntity;
import com.app.entity.Result;
import com.app.mapper.AccessStatsMapper;
import com.app.mapper.LinkMapper;
import com.app.service.StatsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 统计服务实现
 * <p>
 * 数据来源：
 * <ul>
 *   <li>今日实时统计 → Redis 实时计数器（HyperLogLog 去重）</li>
 *   <li>历史每日统计 → t_access_stats 聚合表</li>
 *   <li>累计数据 → t_link 表 totalPv/totalUv 字段</li>
 * </ul>
 */
@Service
public class StatsServiceImpl implements StatsService {

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private AccessStatsMapper accessStatsMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private static final String CLICK_KEY_PATTERN = "shortlink:click:%d:%s:%s";

    // ==================== 单个短链接统计 ====================

    @Override
    public Result<StatsVO> getLinkStats(Long linkId, String range) {
        LinkEntity link = linkMapper.selectById(linkId);
        if (link == null) {
            return Result.fail(Result.NOT_FOUND, "短链接不存在");
        }
        if (StrUtil.isBlank(range)) {
            range = "today";
        }

        StatsVO vo = new StatsVO();
        vo.setLinkId(link.getId());
        vo.setShortUri(link.getShortUri());
        vo.setOriginUrl(link.getOriginUrl());
        vo.setTotalPv(link.getTotalPv() != null ? link.getTotalPv() : 0L);
        vo.setTotalUv(link.getTotalUv() != null ? link.getTotalUv() : 0L);
        vo.setTotalIpCount(link.getTotalIpCount() != null ? link.getTotalIpCount() : 0L);

        // 今日实时 → Redis
        String today = LocalDate.now().toString();
        vo.setTodayPv(getRedisLong(String.format(CLICK_KEY_PATTERN, linkId, today, "pv")));
        vo.setTodayUv(getRedisHll(String.format(CLICK_KEY_PATTERN, linkId, today, "uv")));
        vo.setTodayIpCount(getRedisHll(String.format(CLICK_KEY_PATTERN, linkId, today, "ip")));

        // 每日明细 → t_access_stats
        int days = range.equals("7d") ? 7 : range.equals("30d") ? 30 : 1;
        vo.setDailyStats(queryDailyStats(linkId, days));

        return Result.success(vo);
    }

    // ==================== 总量统计 ====================

    @Override
    public Result<StatsVO> getOverallStats() {
        StatsVO vo = new StatsVO();
        vo.setTotalPv(0L);
        vo.setTotalUv(0L);
        vo.setTotalIpCount(0L);

        // 查询所有未删除短链接的累计数据汇总（简化实现：遍历加总）
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.isNotNull(LinkEntity::getTotalPv);
        List<LinkEntity> links = linkMapper.selectList(wrapper);

        for (LinkEntity link : links) {
            vo.setTotalPv(vo.getTotalPv() + (link.getTotalPv() != null ? link.getTotalPv() : 0));
            vo.setTotalUv(vo.getTotalUv() + (link.getTotalUv() != null ? link.getTotalUv() : 0));
            vo.setTotalIpCount(vo.getTotalIpCount() + (link.getTotalIpCount() != null ? link.getTotalIpCount() : 0));
        }

        return Result.success(vo);
    }

    // ==================== 私有方法 ====================

    /** 从 Redis 读取 String 类型计数器 */
    private long getRedisLong(String key) {
        String val = redisTemplate.opsForValue().get(key);
        return val != null ? Long.parseLong(val) : 0L;
    }

    /** 从 Redis HyperLogLog 读取估计基数 */
    private long getRedisHll(String key) {
        Long size = redisTemplate.opsForHyperLogLog().size(key);
        return size != null ? size : 0L;
    }

    /** 查询每日统计明细 */
    private List<DailyStat> queryDailyStats(Long linkId, int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        LambdaQueryWrapper<AccessStatsEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AccessStatsEntity::getLinkId, linkId);
        wrapper.between(AccessStatsEntity::getStatsDate, startDate, endDate);
        wrapper.orderByAsc(AccessStatsEntity::getStatsDate);

        List<AccessStatsEntity> entities = accessStatsMapper.selectList(wrapper);
        List<DailyStat> result = new ArrayList<>();
        for (AccessStatsEntity entity : entities) {
            DailyStat stat = new DailyStat();
            stat.setDate(entity.getStatsDate());
            stat.setPv(entity.getPv());
            stat.setUv(entity.getUv());
            stat.setIpCount(entity.getIpCount());
            result.add(stat);
        }
        return result;
    }
}
