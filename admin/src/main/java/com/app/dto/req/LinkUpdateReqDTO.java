package com.app.dto.req;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 短链接更新请求 DTO
 * <p>
 * 除 id 外所有字段均为可选，只更新非 null 字段。
 */
@Data
public class LinkUpdateReqDTO {

    /** 短链接 ID（必填） */
    @NotNull(message = "短链接 ID 不能为空")
    private Long id;

    /** 原始 URL */
    @Size(max = 2048, message = "URL 长度不能超过 2048")
    private String originUrl;

    /** 分组标识 */
    private String gid;

    /** 短链接标题 */
    @Size(max = 100, message = "标题最长 100 位")
    private String title;

    /** 短链接描述 */
    @Size(max = 500, message = "描述最长 500 位")
    private String describe;

    /** 重定向类型：0-302 临时，1-301 永久 */
    private Integer redirectType;

    /** 有效期类型：0-永久有效，1-自定义过期时间 */
    private Integer expireType;

    /** 过期时间（expireType=1 时生效） */
    private LocalDateTime expireTime;

    /** 启用状态：0-停用，1-启用 */
    private Integer enableStatus;
}
