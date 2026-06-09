package com.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@TableName("t_access_log")
public class AccessLogEntity {

    /** 主键 ID，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 短链接 ID，关联 t_link.id */
    private Long linkId;

    /** 短链接分组标识，冗余字段便于按分组查询 */
    private String linkGid;

    /** 短码 */
    private String shortUri;

    /** 访客标识（IP + UserAgent 的哈希值），用于 UV 去重 */
    private String visitorId;

    /** 访问者 IP 地址 */
    private String visitorIp;

    /** 原始 User-Agent 字符串 */
    private String userAgent;

    /** 浏览器名称（UserAgentUtil 解析结果） */
    private String browser;

    /** 操作系统名称（UserAgentUtil 解析结果） */
    private String os;

    /** 设备类型：PC / Mobile / Tablet（UserAgentUtil 解析结果） */
    private String device;

    /** 国家（GeoIpUtil 解析结果） */
    private String country;

    /** 省份（GeoIpUtil 解析结果） */
    private String province;

    /** 城市（GeoIpUtil 解析结果） */
    private String city;

    /** 来源页面 URL（Referer） */
    private String referer;

    /** 访问时间（用户点击短链接的时刻） */
    private LocalDateTime accessTime;

    /** 记录创建时间（消费者写入时间） */
    private LocalDateTime createTime;
}
