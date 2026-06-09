package com.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;


@Data
@TableName("t_operation_log")
public class OperationLogEntity {

    /** 主键 ID，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人用户 ID */
    private Long userId;

    /** 操作人用户名（冗余，避免查询用户表） */
    private String username;

    /** 操作类型：CREATE / UPDATE / DELETE / EXPORT */
    private String operationType;

    /** 操作模块：LINK / GROUP / USER */
    private String module;

    /** 操作描述（人类可读，如"创建短链接 abc123"） */
    private String description;

    /** 请求方法：GET / POST / PUT / DELETE */
    private String requestMethod;

    /** 请求 URL（API 路径，如 /api/link/create） */
    private String requestUrl;

    /** 请求参数（JSON 序列化，敏感字段脱敏处理） */
    private String requestParams;

    /** 操作人 IP 地址 */
    private String ip;

    /** 接口执行耗时（毫秒） */
    private Long duration;

    /** 操作结果：0-失败，1-成功 */
    private Integer status;

    /** 错误信息（操作失败时记录异常堆栈摘要） */
    private String errorMsg;

    /** 操作时间 */
    private LocalDateTime createTime;
}
