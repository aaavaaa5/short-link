package com.app.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("t_link")
public class LinkEntity {
    private Long id;
    private String shortUri;
    private String originUrl;
    private String gid;
    private String title;
    private String describe;
    private Integer redirectType;
    private Integer expireType;
    private LocalDateTime expireTime;
    private Long totalPv;
    private Long totalUv;
    private Long totalIpCount;
    private Integer todayPv;
    private Integer todayUv;
    private Integer todayIpCount;
    private Integer enableStatus;
    private Integer createdType;
    private Long createUserId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer delFlag;
}