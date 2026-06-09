package com.app.mapper;

import com.app.entity.OperationLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper
 * <p>
 * 对应表 t_operation_log，记录所有管理后台写操作。
 * 仅做写入，查询走日志系统或手动排查。
 */
@Mapper
public interface OperationLogMapper extends BaseMapper<OperationLogEntity> {
}
