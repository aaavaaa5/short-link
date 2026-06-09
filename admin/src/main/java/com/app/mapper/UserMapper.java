package com.app.mapper;

import com.app.entity.UserEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper
 * <p>
 * 对应表 t_user，提供用户 CRUD。
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
