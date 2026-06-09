package com.app.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;


@Data
@TableName("t_access_stats")
public class AccessStatsEntity {

    /** 主键 ID，数据库自增 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 短链接 ID，关联 t_link.id */
    private Long linkId;

    /** 短链接分组标识，冗余字段便于按分组查询 */
    private String linkGid;

    /** 统计日期 */
    private LocalDate statsDate;

    /** 当日 PV（页面浏览量） */
    private Long pv;

    /** 当日 UV（独立访客数，基于 HyperLogLog 估算） */
    private Long uv;

    /** 当日 IP 数（独立 IP 数，基于 HyperLogLog 估算） */
    private Long ipCount;

    /** 记录创建时间 */
    private LocalDateTime createTime;

    /** 记录更新时间（同一天多次跑聚合任务时会更新） */
    private LocalDateTime updateTime;
}
