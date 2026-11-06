package com.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短码路由索引：short_uri -> gid + link_id。
 */
@Data
@TableName("t_link_route")
public class LinkRouteEntity {

    @TableId(value = "short_uri", type = IdType.INPUT)
    private String shortUri;

    private Long linkId;

    private String gid;

    private LocalDateTime createTime;
}
