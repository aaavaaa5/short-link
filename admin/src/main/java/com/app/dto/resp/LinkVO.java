package com.app.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链接 VO（返回给前端展示）
 */
@Data
public class LinkVO {

    /** 短链接 ID */
    private Long id;

    /** 短码 */
    private String shortUri;

    /** 完整短链接地址（域名 + 短码） */
    private String fullShortUrl;

    /** 原始 URL */
    private String originUrl;

    /** 分组标识 */
    private String gid;

    /** 标题 */
    private String title;

    /** 描述 */
    private String describe;

    /** 重定向类型：0-302 临时，1-301 永久 */
    private Integer redirectType;

    /** 有效期类型：0-永久有效，1-自定义过期时间 */
    private Integer expireType;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 累计 PV */
    private Long totalPv;

    /** 累计 UV */
    private Long totalUv;

    /** 累计 IP 数 */
    private Long totalIpCount;

    /** 今日 PV */
    private Integer todayPv;

    /** 今日 UV */
    private Integer todayUv;

    /** 今日 IP 数 */
    private Integer todayIpCount;

    /** 启用状态：0-停用，1-启用 */
    private Integer enableStatus;

    /** 创建类型：0-API，1-管理后台，2-批量导入 */
    private Integer createdType;

    /** 创建人用户 ID */
    private Long createUserId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
