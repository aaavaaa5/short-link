package com.app.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@TableName("t_group")
public class GroupEntity {

    /** 主键 ID，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全局唯一分组标识（分库分表键），使用雪花算法或短字符串生成 */
    private String gid;

    /** 分组名称 */
    private String name;

    /** 分组描述 */
    private String description;

    /** 所属用户 ID */
    private Long userId;

    /** 排序字段，数值越小越靠前 */
    private Integer sortOrder;

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
