package com.app.mapper;

import com.app.entity.AccessLogEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 访问日志 Mapper
 * <p>
 * 对应表 t_access_log，数据量大（每日百万级），仅做写入和简单查询。
 * 复杂统计查询走 t_access_stats 聚合表。
 * <p>
 * 注意：t_access_log 按 link_gid 分片，所有查询必须带 link_gid 参数
 * 以便 ShardingSphere 精确定位到单个分片，避免全分片广播。
 */
@Mapper
public interface AccessLogMapper extends BaseMapper<AccessLogEntity> {

    /**
     * 按国家分组统计（指定短链接）
     */
    @Select("SELECT a.country AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a " +
            "WHERE a.link_id = #{linkId} AND a.link_gid = #{linkGid} " +
            "GROUP BY a.country ORDER BY cnt DESC LIMIT 10")
    List<NameCountRow> countByCountry(@Param("linkId") Long linkId, @Param("linkGid") String linkGid);

    /**
     * 按省份分组统计（指定短链接）
     */
    @Select("SELECT a.province AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a " +
            "WHERE a.link_id = #{linkId} AND a.link_gid = #{linkGid} " +
            "GROUP BY a.province ORDER BY cnt DESC LIMIT 10")
    List<NameCountRow> countByProvince(@Param("linkId") Long linkId, @Param("linkGid") String linkGid);

    /**
     * 按城市分组统计（指定短链接）
     */
    @Select("SELECT a.city AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a " +
            "WHERE a.link_id = #{linkId} AND a.link_gid = #{linkGid} " +
            "GROUP BY a.city ORDER BY cnt DESC LIMIT 15")
    List<NameCountRow> countByCity(@Param("linkId") Long linkId, @Param("linkGid") String linkGid);

    /**
     * 按浏览器分组统计（指定短链接）
     */
    @Select("SELECT a.browser AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a " +
            "WHERE a.link_id = #{linkId} AND a.link_gid = #{linkGid} " +
            "GROUP BY a.browser ORDER BY cnt DESC LIMIT 10")
    List<NameCountRow> countByBrowser(@Param("linkId") Long linkId, @Param("linkGid") String linkGid);

    /**
     * 按操作系统分组统计（指定短链接）
     */
    @Select("SELECT a.os AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a " +
            "WHERE a.link_id = #{linkId} AND a.link_gid = #{linkGid} " +
            "GROUP BY a.os ORDER BY cnt DESC LIMIT 10")
    List<NameCountRow> countByOs(@Param("linkId") Long linkId, @Param("linkGid") String linkGid);

    /**
     * 按设备类型分组统计（指定短链接）
     */
    @Select("SELECT a.device_type AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a " +
            "WHERE a.link_id = #{linkId} AND a.link_gid = #{linkGid} " +
            "GROUP BY a.device_type ORDER BY cnt DESC")
    List<NameCountRow> countByDevice(@Param("linkId") Long linkId, @Param("linkGid") String linkGid);

    // ==================== 多链接汇总统计（仪表盘） ====================

    /**
     * 按国家分组统计（多个短链接汇总）
     */
    @Select("<script>" +
            "SELECT a.country AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a WHERE a.link_id IN " +
            "<foreach collection='linkIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND a.link_gid = #{linkGid} " +
            "GROUP BY a.country ORDER BY cnt DESC LIMIT 10" +
            "</script>")
    List<NameCountRow> countByCountryBatch(@Param("linkIds") List<Long> linkIds, @Param("linkGid") String linkGid);

    /**
     * 按省份分组统计（多个短链接汇总）
     */
    @Select("<script>" +
            "SELECT a.province AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a WHERE a.link_id IN " +
            "<foreach collection='linkIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND a.link_gid = #{linkGid} " +
            "GROUP BY a.province ORDER BY cnt DESC LIMIT 10" +
            "</script>")
    List<NameCountRow> countByProvinceBatch(@Param("linkIds") List<Long> linkIds, @Param("linkGid") String linkGid);

    /**
     * 按城市分组统计（多个短链接汇总）
     */
    @Select("<script>" +
            "SELECT a.city AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a WHERE a.link_id IN " +
            "<foreach collection='linkIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND a.link_gid = #{linkGid} " +
            "GROUP BY a.city ORDER BY cnt DESC LIMIT 15" +
            "</script>")
    List<NameCountRow> countByCityBatch(@Param("linkIds") List<Long> linkIds, @Param("linkGid") String linkGid);

    /**
     * 按浏览器分组统计（多个短链接汇总）
     */
    @Select("<script>" +
            "SELECT a.browser AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a WHERE a.link_id IN " +
            "<foreach collection='linkIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND a.link_gid = #{linkGid} " +
            "GROUP BY a.browser ORDER BY cnt DESC LIMIT 10" +
            "</script>")
    List<NameCountRow> countByBrowserBatch(@Param("linkIds") List<Long> linkIds, @Param("linkGid") String linkGid);

    /**
     * 按操作系统分组统计（多个短链接汇总）
     */
    @Select("<script>" +
            "SELECT a.os AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a WHERE a.link_id IN " +
            "<foreach collection='linkIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND a.link_gid = #{linkGid} " +
            "GROUP BY a.os ORDER BY cnt DESC LIMIT 10" +
            "</script>")
    List<NameCountRow> countByOsBatch(@Param("linkIds") List<Long> linkIds, @Param("linkGid") String linkGid);

    /**
     * 按设备类型分组统计（多个短链接汇总）
     */
    @Select("<script>" +
            "SELECT a.device_type AS name, COUNT(*) AS cnt " +
            "FROM t_access_log a WHERE a.link_id IN " +
            "<foreach collection='linkIds' item='id' open='(' separator=',' close=')'>#{id}</foreach> " +
            "AND a.link_gid = #{linkGid} " +
            "GROUP BY a.device_type ORDER BY cnt DESC" +
            "</script>")
    List<NameCountRow> countByDeviceBatch(@Param("linkIds") List<Long> linkIds, @Param("linkGid") String linkGid);

    // ==================== 查询结果映射行 ====================

    /**
     * MyBatis 不会自动映射非实体查询结果，用内部类接收 GROUP BY 结果。
     * 字段名与 SQL 别名（name / cnt）一致。
     * <p>
     * 注意：必须是具体类（不能是接口），MyBatis 需要通过无参构造函数实例化。
     */
    @Data
    static class NameCountRow {
        private String name;
        private Long cnt;
    }
}
