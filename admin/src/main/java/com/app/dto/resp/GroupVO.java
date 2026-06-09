package com.app.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分组 VO（返回给前端展示）
 */
@Data
public class GroupVO {

    /** 分组 ID */
    private Long id;

    /** 分组标识 */
    private String gid;

    /** 分组名称 */
    private String name;

    /** 分组描述 */
    private String description;

    /** 排序字段 */
    private Integer sortOrder;

    /** 分组下的短链接数量 */
    private Integer linkCount;

    /** 创建时间 */
    private LocalDateTime createTime;
}
