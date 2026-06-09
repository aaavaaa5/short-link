package com.app.mapper;

import com.app.entity.LinkEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * 短链接 Mapper
 * <p>
 * 对应分库分表 t_link_0~7，ShardingSphere 自动路由。
 * 继承 MyBatis-Plus BaseMapper，自带 CRUD 方法。
 */
@Mapper
public interface LinkMapper extends BaseMapper<LinkEntity> {
}
