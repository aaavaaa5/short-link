package com.app.mapper;

import com.app.entity.AccessStatsEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问统计 Mapper
 * <p>
 * 对应表 t_access_stats，按 (link_id, stats_date) 唯一。
 * 供统计图表查询使用。
 */
@Mapper
public interface AccessStatsMapper extends BaseMapper<AccessStatsEntity> {
}
