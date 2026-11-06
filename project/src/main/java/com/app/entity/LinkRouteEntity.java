package com.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("t_link_route")
public class LinkRouteEntity {

    @TableId(value = "short_uri", type = IdType.INPUT)
    private String shortUri;

    private Long linkId;

    private String gid;
}
