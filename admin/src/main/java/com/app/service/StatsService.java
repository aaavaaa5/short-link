package com.app.service;

import com.app.dto.resp.StatsVO;
import com.app.entity.Result;

/**
 * 统计服务
 */
public interface StatsService {

    /**
     * 查询单个短链接的访问统计
     *
     * @param linkId 短链接 ID
     * @param range  时间范围：today / 7d / 30d
     * @return 统计 VO（含每日明细）
     */
    Result<StatsVO> getLinkStats(Long linkId, String range);

    /**
     * 查询总量统计（所有短链接汇总）
     *
     * @return 统计 VO
     */
    Result<StatsVO> getOverallStats();
}
