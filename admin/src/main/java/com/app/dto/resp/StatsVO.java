package com.app.dto.resp;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * 统计 VO（返回给前端图表展示）
 */
@Data
public class StatsVO {

    /** 短链接 ID */
    private Long linkId;

    /** 短码 */
    private String shortUri;

    /** 原始 URL */
    private String originUrl;

    /** 累计 PV */
    private Long totalPv;

    /** 累计 UV */
    private Long totalUv;

    /** 累计 IP 数 */
    private Long totalIpCount;

    /** 今日 PV */
    private Long todayPv;

    /** 今日 UV */
    private Long todayUv;

    /** 今日 IP 数 */
    private Long todayIpCount;

    /** 近 7 日 / 近 30 日每日统计明细（用于折线图） */
    private List<DailyStat> dailyStats;

    // ==================== 地区/浏览器分布 ====================

    /** 国家/地区分布（用于仪表盘和单链接统计） */
    private List<NameCount> countries;

    /** 省份分布 */
    private List<NameCount> provinces;

    /** 城市分布 */
    private List<NameCount> cities;

    /** 浏览器分布 */
    private List<NameCount> browsers;

    /** 操作系统分布 */
    private List<NameCount> osList;

    /** 设备类型分布 */
    private List<NameCount> devices;

    // ==================== 内部类 ====================

    /**
     * 单日统计明细项
     */
    @Data
    public static class DailyStat {

        /** 统计日期 */
        private LocalDate date;

        /** 当日 PV */
        private Long pv;

        /** 当日 UV */
        private Long uv;

        /** 当日 IP 数 */
        private Long ipCount;
    }

    /**
     * 名称-数量 统计项（用于地区、浏览器、OS、设备等分布）
     */
    @Data
    public static class NameCount {

        /** 名称（如 "中国"、"Chrome"、"Windows"） */
        private String name;

        /** 数量 */
        private Long count;
    }
}
