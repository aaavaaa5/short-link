package com.app.dto.req;

import lombok.Data;

/**
 * 短链接分页查询请求 DTO
 */
@Data
public class LinkPageReqDTO {

    /** 当前页码（从 1 开始） */
    private Integer page = 1;

    /** 每页条数 */
    private Integer size = 10;

    /** 分组标识（可选，筛选指定分组下的短链接） */
    private String gid;

    /** 短码（可选，模糊搜索） */
    private String shortUri;

    /** 原始 URL（可选，模糊搜索） */
    private String originUrl;

    /** 标题（可选，模糊搜索） */
    private String title;

    /** 启用状态（可选）：0-停用，1-启用 */
    private Integer enableStatus;

    /** 创建类型（可选）：0-API，1-管理后台，2-批量导入 */
    private Integer createdType;
}
