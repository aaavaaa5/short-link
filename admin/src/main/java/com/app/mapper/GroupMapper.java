package com.app.mapper;

import com.app.entity.GroupEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 分组 Mapper
 * <p>
 * 对应表 t_group，提供分组 CRUD。
 */
@Mapper
public interface GroupMapper extends BaseMapper<GroupEntity> {
}
