package com.app.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链接创建请求 DTO
 */
@Data
public class LinkCreateReqDTO {

    /** 原始 URL（目标跳转地址） */
    @NotBlank(message = "原始 URL 不能为空")
    @Size(max = 2048, message = "URL 长度不能超过 2048")
    private String originUrl;

    /** 分组标识 */
    @NotBlank(message = "分组标识不能为空")
    private String gid;

    /** 短链接标题 */
    @Size(max = 100, message = "标题最长 100 位")
    private String title;

    /** 短链接描述 */
    @Size(max = 500, message = "描述最长 500 位")
    private String describe;

    /** 重定向类型：0-302 临时（默认），1-301 永久 */
    private Integer redirectType;

    /** 有效期类型：0-永久有效（默认），1-自定义过期时间 */
    private Integer expireType;

    /** 过期时间（expireType=1 时必填） */
    private LocalDateTime expireTime;

    /** 创建类型：0-API，1-管理后台，2-批量导入 */
    private Integer createdType;
}
