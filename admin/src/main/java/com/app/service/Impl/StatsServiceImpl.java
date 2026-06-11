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
        LocalDate startDate = endDate.minusDays(days - 1);  // 今天往前 (days-1) 天 = 共 days 天

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

        // 3. 查询每日统计（DB 聚合表，只到昨天为止）
        LambdaQueryWrapper<AccessStatsEntity> statsWrapper = Wrappers.lambdaQuery();
        statsWrapper.in(AccessStatsEntity::getLinkId, linkIds);
        statsWrapper.between(AccessStatsEntity::getStatsDate, startDate, endDate);
        List<AccessStatsEntity> statsList = accessStatsMapper.selectList(statsWrapper);

        // 4. 初始化日期 Map（所有日期默认 0），按日期填 DB 数据
        Map<LocalDate, long[]> dateMap = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
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

        // 5. 用 Redis 补全最近 3 天中 DB 缺失的日期（聚合任务可能因项目未 7×24 运行而漏执行）
        LocalDate redisCutoff = LocalDate.now().minusDays(2);
        for (Map.Entry<LocalDate, long[]> entry : dateMap.entrySet()) {
            LocalDate date = entry.getKey();
            if (date.isBefore(redisCutoff)) {
                continue;  // 超过 3 天，Redis 计数器已过期
            }
            long[] arr = entry.getValue();
            // DB 已有数据 → 跳过（DB 比 Redis 更可靠，聚合时做过幂等处理）
            if (arr[0] > 0 || arr[1] > 0 || arr[2] > 0) {
                continue;
            }
            // DB 无数据 → Redis 兜底（各链接独立求和）
            String dateStr = date.toString();
            for (Long linkId : linkIds) {
                arr[0] += getRedisLong(String.format(CLICK_KEY_PATTERN, linkId, dateStr, "pv"));
                arr[1] += getRedisHll(String.format(CLICK_KEY_PATTERN, linkId, dateStr, "uv"));
                arr[2] += getRedisHll(String.format(CLICK_KEY_PATTERN, linkId, dateStr, "ip"));
            }
        }

        // 6. 计算今日 Redis 实时数据（用于 VO 的 todayXxx 字段）
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

        // 7. dateMap → dailyStats 列表
        List<DailyStat> dailyStats = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> entry : dateMap.entrySet()) {
            DailyStat stat = new DailyStat();
            stat.setDate(entry.getKey());
            stat.setPv(entry.getValue()[0]);
            stat.setUv(entry.getValue()[1]);
            stat.setIpCount(entry.getValue()[2]);
            dailyStats.add(stat);
        }

        // 8. 安全兜底：确保今天的数据点一定用 Redis（即使上面某一步 DB 误写入了今天的数据）
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

    /**
     * 查询每日统计明细（单链接）
     * <p>
     * 数据来源（按优先级）：
     * <ol>
     *   <li>t_access_stats 聚合表（凌晨 01:00 定时写入，最可靠）</li>
     *   <li>Redis 实时计数器（DB 还没聚合时的兜底，TTL=3天）</li>
     *   <li>填充 0（既没 DB 也没 Redis 的日期）</li>
     * </ol>
     * <p>
     * 为什么需要 Redis 兜底？开发调试时项目不是 7×24 运行，
     * 凌晨 01:00 聚合任务可能没执行，但 Redis 里还保留着最近 3 天的数据。
     */
    private List<DailyStat> queryDailyStats(Long linkId, int days) {
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);  // 今天往前 (days-1) 天 = 共 days 天
        LocalDate today = LocalDate.now();
        // Redis TTL 是 3 天，超过 3 天的数据肯定已过期，不用查
        LocalDate redisCutoff = today.minusDays(2);  // 今天、昨天、前天共 3 天

        // 1. 初始化日期 Map，所有日期默认 0
        Map<LocalDate, long[]> dateMap = new LinkedHashMap<>();
        for (int i = 0; i < days; i++) {
            dateMap.put(startDate.plusDays(i), new long[]{0, 0, 0});
        }

        // 2. 从 DB 查询已有聚合数据
        LambdaQueryWrapper<AccessStatsEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(AccessStatsEntity::getLinkId, linkId);
        wrapper.between(AccessStatsEntity::getStatsDate, startDate, endDate);
        wrapper.orderByAsc(AccessStatsEntity::getStatsDate);

        List<AccessStatsEntity> entities = accessStatsMapper.selectList(wrapper);
        for (AccessStatsEntity entity : entities) {
            long[] arr = dateMap.get(entity.getStatsDate());
            if (arr != null) {
                arr[0] = entity.getPv() != null ? entity.getPv() : 0;
                arr[1] = entity.getUv() != null ? entity.getUv() : 0;
                arr[2] = entity.getIpCount() != null ? entity.getIpCount() : 0;
            }
        }

        // 3. 对于最近 3 天，DB 没有数据的日期 → Redis 兜底
        for (Map.Entry<LocalDate, long[]> entry : dateMap.entrySet()) {
            LocalDate date = entry.getKey();
            if (date.isBefore(redisCutoff)) {
                continue;  // 超过 3 天，Redis 已过期，不查
            }
            long[] arr = entry.getValue();
            // DB 有数据 → 跳过（DB 优先）
            if (arr[0] > 0 || arr[1] > 0 || arr[2] > 0) {
                continue;
            }
            // DB 无数据 → Redis 兜底
            String dateStr = date.toString();
            arr[0] = getRedisLong(String.format(CLICK_KEY_PATTERN, linkId, dateStr, "pv"));
            arr[1] = getRedisHll(String.format(CLICK_KEY_PATTERN, linkId, dateStr, "uv"));
            arr[2] = getRedisHll(String.format(CLICK_KEY_PATTERN, linkId, dateStr, "ip"));
        }

        // 4. 转为有序列表返回
        List<DailyStat> result = new ArrayList<>();
        for (Map.Entry<LocalDate, long[]> entry : dateMap.entrySet()) {
            DailyStat stat = new DailyStat();
            stat.setDate(entry.getKey());
            stat.setPv(entry.getValue()[0]);
            stat.setUv(entry.getValue()[1]);
            stat.setIpCount(entry.getValue()[2]);
            result.add(stat);
        }
        return result;
    }
}
