package com.app.mapper;

import com.app.entity.AccessLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 访问日志 Mapper
 * <p>
 * 对应表 t_access_log，数据量大（每日百万级），仅做写入和简单查询。
 * 复杂统计查询走 t_access_stats 聚合表。
 */
@Mapper
public interface AccessLogMapper extends BaseMapper<AccessLogEntity> {
}
