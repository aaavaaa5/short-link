package com.app.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@TableName("t_link")
public class LinkEntity {

    /** 主键 ID，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 短码（唯一索引），用于短链接解析 */
    private String shortUri;

    /** 原始 URL（目标跳转地址） */
    private String originUrl;

    /** 分组标识（分库分表键），关联 t_group.gid */
    private String gid;

    /** 短链接标题 */
    private String title;

    /** 短链接描述（describe 是 MySQL 保留字，需要反引号转义） */
    @TableField("`describe`")
    private String describe;

    /** 重定向类型：0-302 临时，1-301 永久 */
    private Integer redirectType;

    /** 有效期类型：0-永久有效，1-自定义过期时间 */
    private Integer expireType;

    /** 过期时间（expireType=1 时生效） */
    private LocalDateTime expireTime;

    /** 累计 PV */
    private Long totalPv;

    /** 累计 UV */
    private Long totalUv;

    /** 累计独立 IP 数 */
    private Long totalIpCount;

    /** 今日 PV（Redis 准实时同步） */
    private Integer todayPv;

    /** 今日 UV（Redis 准实时同步） */
    private Integer todayUv;

    /** 今日 IP 数（Redis 准实时同步） */
    private Integer todayIpCount;

    /** 启用状态：0-停用，1-启用 */
    private Integer enableStatus;

    /** 创建类型：0-API 创建，1-管理后台创建，2-批量导入 */
    private Integer createdType;

    /** 创建人用户 ID */
    private Long createUserId;

    /** 创建时间 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 删除标记：0-正常，1-已删除（逻辑删除） */
    @TableLogic
    private Integer delFlag;
}