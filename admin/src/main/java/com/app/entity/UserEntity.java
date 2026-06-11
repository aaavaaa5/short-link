package com.app.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@TableName("t_user")
public class UserEntity {

    /** 主键 ID，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户名，唯一索引，用于登录 */
    private String username;

    /** 密码，BCrypt 加密后的密文 */
    private String password;

    /** 真实姓名 */
    private String realName;

    /** 邮箱 */
    private String email;

    /** 账号启用状态：0-停用，1-启用（DB 列名为 status） */
    @TableField("status")
    private Integer enableStatus;

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
