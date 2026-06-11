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
import com.app.util.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;


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
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录");
        }

        // 查询当前用户的所有短链接
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(LinkEntity::getCreateUserId, userId);
        List<LinkEntity> links = linkMapper.selectList(wrapper);

        long totalPv = 0, totalUv = 0, totalIpCount = 0;
        long todayPv = 0, todayUv = 0, todayIp = 0;
        String today = LocalDate.now().toString();

        for (LinkEntity link : links) {
            totalPv += link.getTotalPv() != null ? link.getTotalPv() : 0;
            totalUv += link.getTotalUv() != null ? link.getTotalUv() : 0;
            totalIpCount += link.getTotalIpCount() != null ? link.getTotalIpCount() : 0;
            // 今日实时数据：各链接独立求和
            todayPv += getRedisLong(String.format(CLICK_KEY_PATTERN, link.getId(), today, "pv"));
            todayUv += getRedisHll(String.format(CLICK_KEY_PATTERN, link.getId(), today, "uv"));
            todayIp += getRedisHll(String.format(CLICK_KEY_PATTERN, link.getId(), today, "ip"));
        }

        StatsVO vo = new StatsVO();
        // 总数 = DB 历史累计 + Redis 今日实时
        vo.setTotalPv(totalPv + todayPv);
        vo.setTotalUv(totalUv + todayUv);
        vo.setTotalIpCount(totalIpCount + todayIp);
        vo.setTodayPv(todayPv);
        vo.setTodayUv(todayUv);
        vo.setTodayIpCount(todayIp);
        return Result.success(vo);
    }

    // ==================== 趋势数据（按日期聚合当前用户所有短链接） ====================

    @Override
    public Result<StatsVO> getTrend(String range) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录");
        }

        int days = range.equals("30d") ? 30 : 7;
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days);

        // 1. 直接按创建者查询当前用户的所有短链接
        LambdaQueryWrapper<LinkEntity> linkWrapper = Wrappers.lambdaQuery();
        linkWrapper.eq(LinkEntity::getCreateUserId, userId);
        List<LinkEntity> links = linkMapper.selectList(linkWrapper);

        // 2. 没有链接则返回空
        if (links.isEmpty()) {
            StatsVO vo = new StatsVO();
            vo.setTotalPv(0L);
            vo.setTotalUv(0L);
            vo.setTotalIpCount(0L);
            vo.setTodayPv(0L);
            vo.setTodayUv(0L);
            vo.setTodayIpCount(0L);
            vo.setDailyStats(new ArrayList<>());
            return Result.success(vo);
        }

        List<Long> linkIds = new ArrayList<>();
        long totalPv = 0, totalUv = 0, totalIpCount = 0;
        for (LinkEntity link : links) {
            linkIds.add(link.getId());
            totalPv += link.getTotalPv() != null ? link.getTotalPv() : 0;
            totalUv += link.getTotalUv() != null ? link.getTotalUv() : 0;
            totalIpCount += link.getTotalIpCount() != null ? link.getTotalIpCount() : 0;
        }

        // 3. 查询每日统计
        LambdaQueryWrapper<AccessStatsEntity> statsWrapper = Wrappers.lambdaQuery();
        statsWrapper.in(AccessStatsEntity::getLinkId, linkIds);
        statsWrapper.between(AccessStatsEntity::getStatsDate, startDate, endDate);
        List<AccessStatsEntity> statsList = accessStatsMapper.selectList(statsWrapper);

        // 4. 按日期聚合
        Map<LocalDate, long[]> dateMap = new LinkedHashMap<>();
        for (int i = 0; i <= days; i++) {
            dateMap.put(startDate.plusDays(i), new long[]{0, 0, 0});
        }
        for (AccessStatsEntity stats : statsList) {
            long[] arr = dateMap.get(stats.getStatsDate());
            if (arr != null) {
                arr[0] += stats.getPv() != null ? stats.getPv() : 0;
                arr[1] += stats.getUv() != null ? stats.getUv() : 0;
                arr[2] += stats.getIpCount() != null ? stats.getIpCount() : 0;
            }
        }

        // 5. Redis 实时今日数据：各链接独立求和
        String today = LocalDate.now().toString();
        long todayPv = 0, todayUv = 0, todayIp = 0;
        for (Long linkId : linkIds) {
            todayPv += getRedisLong(String.format(CLICK_KEY_PATTERN, linkId, today, "pv"));
            todayUv += getRedisHll(String.format(CLICK_KEY_PATTERN, linkId, today, "uv"));
            todayIp += getRedisHll(String.format(CLICK_KEY_PATTERN, linkId, today, "ip"));
        }

        StatsVO vo = new StatsVO();
        vo.setTotalPv(totalPv);
        vo.setTotalUv(totalUv);
        vo.setTotalIpCount(totalIpCount);
        vo.setTodayPv(todayPv);
        vo.setTodayUv(todayUv);
        vo.setTodayIpCount(todayIp);

        List<DailyStat> dailyStats = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> entry : dateMap.entrySet()) {
            DailyStat stat = new DailyStat();
            stat.setDate(entry.getKey());
            stat.setPv(entry.getValue()[0]);
            stat.setUv(entry.getValue()[1]);
            stat.setIpCount(entry.getValue()[2]);
            dailyStats.add(stat);
        }

        // 用 Redis 实时数据覆盖今天的数据点（DB 聚合表今天的数据要等凌晨才更新）
        if (!dailyStats.isEmpty()) {
            DailyStat todayStat = dailyStats.get(dailyStats.size() - 1);
            if (todayStat.getDate().equals(LocalDate.now())) {
                todayStat.setPv(todayPv);
                todayStat.setUv(todayUv);
                todayStat.setIpCount(todayIp);
            }
        }

        vo.setDailyStats(dailyStats);

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
