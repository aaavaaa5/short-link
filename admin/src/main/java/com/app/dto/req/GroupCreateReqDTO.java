package com.app.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 分组创建 / 更新请求 DTO
 * <p>
 * 传 id 时为更新，不传时为新建。
 */
@Data
public class GroupCreateReqDTO {

    /** 分组 ID（更新时传） */
    private Long id;

    /** 分组名称 */
    @NotBlank(message = "分组名称不能为空")
    @Size(min = 1, max = 50, message = "分组名称长度 1-50 位")
    private String name;

    /** 分组描述 */
    @Size(max = 200, message = "描述最长 200 位")
    private String description;

    /** 排序字段，数值越小越靠前 */
    private Integer sortOrder;
}
