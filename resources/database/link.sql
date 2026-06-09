-- ============================================================
-- 短链接服务 — 数据库初始化脚本
-- 版本：v3.0
-- 日期：2026-06-08
-- 数据库：MySQL 8.0+
-- 分片中间件：ShardingSphere-JDBC 5.3.2
-- ============================================================

-- -------------------------------------------------------
-- 创建逻辑数据库（实际环境中两个库可能在不同实例上）
-- -------------------------------------------------------
CREATE DATABASE IF NOT EXISTS shortlink_ds0 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS shortlink_ds1 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ============================================================
-- 一、分片表 — ds_0 库
-- ============================================================

USE shortlink_ds0;

-- -------------------------------------------------------
-- 1.1 短链接主表 t_link_0 ~ t_link_7（分片键: gid）
-- -------------------------------------------------------

CREATE TABLE t_link_0 (
    id              BIGINT        NOT NULL AUTO_INCREMENT                COMMENT '主键ID（雪花算法）',
    short_uri       VARCHAR(32)   NOT NULL                               COMMENT '短链接码，Base62编码6~8位',
    origin_url      VARCHAR(2048) NOT NULL                               COMMENT '原始长链接',
    gid             VARCHAR(32)   NOT NULL  DEFAULT 'default'           COMMENT '分组标识（分库分表键）',
    title           VARCHAR(512)  NULL                                   COMMENT '目标网页标题（Jsoup异步抓取）',
    `describe`      VARCHAR(1024) NULL                                   COMMENT '备注描述',
    redirect_type   TINYINT       NOT NULL  DEFAULT 302                 COMMENT '跳转方式：301-永久 302-临时',
    expire_type     TINYINT       NOT NULL  DEFAULT 0                   COMMENT '有效期类型：0-永久有效 1-指定日期失效',
    expire_time     DATETIME      NULL                                   COMMENT '失效时间',
    total_pv        BIGINT        NOT NULL  DEFAULT 0                   COMMENT '累计访问量(PV)',
    total_uv        BIGINT        NOT NULL  DEFAULT 0                   COMMENT '累计独立访客数(UV)',
    total_ip_count  BIGINT        NOT NULL  DEFAULT 0                   COMMENT '累计独立IP数',
    today_pv        INT           NOT NULL  DEFAULT 0                   COMMENT '今日PV（每日零点清零）',
    today_uv        INT           NOT NULL  DEFAULT 0                   COMMENT '今日UV（每日零点清零）',
    today_ip_count  INT           NOT NULL  DEFAULT 0                   COMMENT '今日独立IP数（每日零点清零）',
    enable_status   TINYINT       NOT NULL  DEFAULT 1                   COMMENT '启用状态：0-停用 1-启用',
    created_type    TINYINT       NOT NULL  DEFAULT 0                   COMMENT '创建方式：0-控制台 1-API',
    create_user_id  BIGINT        NOT NULL                               COMMENT '创建人ID',
    create_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    update_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag        TINYINT       NOT NULL  DEFAULT 0                   COMMENT '删除标记：0-正常 1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_short_uri (short_uri),
    KEY idx_gid (gid),
    KEY idx_create_user_id (create_user_id),
    KEY idx_enable_expire (enable_status, expire_time),
    KEY idx_del_flag (del_flag),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='短链接主表-分片0';

CREATE TABLE t_link_1 LIKE t_link_0;
CREATE TABLE t_link_2 LIKE t_link_0;
CREATE TABLE t_link_3 LIKE t_link_0;
CREATE TABLE t_link_4 LIKE t_link_0;
CREATE TABLE t_link_5 LIKE t_link_0;
CREATE TABLE t_link_6 LIKE t_link_0;
CREATE TABLE t_link_7 LIKE t_link_0;

-- -------------------------------------------------------
-- 1.2 访问日志表 t_access_log_0 ~ t_access_log_7（分片键: link_gid）
-- -------------------------------------------------------

CREATE TABLE t_access_log_0 (
    id              BIGINT        NOT NULL AUTO_INCREMENT                COMMENT '主键ID',
    link_id         BIGINT        NOT NULL                               COMMENT '短链接ID',
    link_gid        VARCHAR(32)   NOT NULL                               COMMENT '短链接gid（分片键）',
    short_uri       VARCHAR(32)   NOT NULL                               COMMENT '短链接码（冗余）',
    visitor_id      VARCHAR(64)   NOT NULL                               COMMENT '访客唯一标识（Cookie/指纹Hash，UV去重）',
    visitor_ip      VARCHAR(45)   NOT NULL                               COMMENT '访问者完整IP（独立IP统计+GeoIP解析）',
    referer         VARCHAR(2048) NULL                                   COMMENT 'Referer完整URL',
    referer_domain  VARCHAR(256)  NULL                                   COMMENT '来源域名',
    user_agent      VARCHAR(1024) NULL                                   COMMENT '原始User-Agent',
    os              VARCHAR(64)   NULL                                   COMMENT '操作系统',
    os_version      VARCHAR(32)   NULL                                   COMMENT '操作系统版本',
    browser         VARCHAR(64)   NULL                                   COMMENT '浏览器',
    browser_version VARCHAR(32)   NULL                                   COMMENT '浏览器版本',
    device_type     VARCHAR(16)   NULL                                   COMMENT '设备类型：PC/Mobile/Tablet',
    country         VARCHAR(64)   NULL                                   COMMENT '国家（GeoIP解析）',
    province        VARCHAR(64)   NULL                                   COMMENT '省份',
    city            VARCHAR(64)   NULL                                   COMMENT '城市',
    access_time     DATETIME(3)   NOT NULL                               COMMENT '访问时间（毫秒精度）',
    PRIMARY KEY (id),
    KEY idx_link_id_time (link_id, access_time),
    KEY idx_link_gid_time (link_gid, access_time),
    KEY idx_access_time (access_time),
    KEY idx_visitor_ip (visitor_ip, access_time),
    KEY idx_country_province (country, province, access_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='访问日志表-分片0（按日分区，90天清理）';

CREATE TABLE t_access_log_1 LIKE t_access_log_0;
CREATE TABLE t_access_log_2 LIKE t_access_log_0;
CREATE TABLE t_access_log_3 LIKE t_access_log_0;
CREATE TABLE t_access_log_4 LIKE t_access_log_0;
CREATE TABLE t_access_log_5 LIKE t_access_log_0;
CREATE TABLE t_access_log_6 LIKE t_access_log_0;
CREATE TABLE t_access_log_7 LIKE t_access_log_0;

-- -------------------------------------------------------
-- 1.3 访问统计表 t_access_stats_0 ~ t_access_stats_7（分片键: link_gid）
-- -------------------------------------------------------

CREATE TABLE t_access_stats_0 (
    id              BIGINT        NOT NULL AUTO_INCREMENT                COMMENT '主键ID',
    link_id         BIGINT        NOT NULL                               COMMENT '短链接ID',
    link_gid        VARCHAR(32)   NOT NULL                               COMMENT '短链接gid（分片键）',
    stat_date       DATE          NOT NULL                               COMMENT '统计日期',
    pv              BIGINT        NOT NULL  DEFAULT 0                   COMMENT '当日PV',
    uv              BIGINT        NOT NULL  DEFAULT 0                   COMMENT '当日UV（去重访客数）',
    ip_count        BIGINT        NOT NULL  DEFAULT 0                   COMMENT '当日独立IP数',
    scan_pv         BIGINT        NOT NULL  DEFAULT 0                   COMMENT '扫码访问量（预留）',
    create_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    update_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_date (link_id, stat_date),
    KEY idx_link_gid_date (link_gid, stat_date),
    KEY idx_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='访问统计表-分片0（按日汇总）';

CREATE TABLE t_access_stats_1 LIKE t_access_stats_0;
CREATE TABLE t_access_stats_2 LIKE t_access_stats_0;
CREATE TABLE t_access_stats_3 LIKE t_access_stats_0;
CREATE TABLE t_access_stats_4 LIKE t_access_stats_0;
CREATE TABLE t_access_stats_5 LIKE t_access_stats_0;
CREATE TABLE t_access_stats_6 LIKE t_access_stats_0;
CREATE TABLE t_access_stats_7 LIKE t_access_stats_0;

-- -------------------------------------------------------
-- 1.4 地域统计表 t_access_region_stats_0 ~ t_access_region_stats_7（分片键: link_gid）
-- -------------------------------------------------------

CREATE TABLE t_access_region_stats_0 (
    id              BIGINT        NOT NULL AUTO_INCREMENT                COMMENT '主键ID',
    link_id         BIGINT        NOT NULL                               COMMENT '短链接ID',
    link_gid        VARCHAR(32)   NOT NULL                               COMMENT '短链接gid（分片键）',
    stat_date       DATE          NOT NULL                               COMMENT '统计日期',
    country         VARCHAR(64)   NOT NULL  DEFAULT '未知'               COMMENT '国家',
    province        VARCHAR(64)   NOT NULL  DEFAULT '未知'               COMMENT '省份',
    city            VARCHAR(64)   NOT NULL  DEFAULT '未知'               COMMENT '城市',
    pv              BIGINT        NOT NULL  DEFAULT 0                   COMMENT '该地区当日PV',
    create_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    update_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_link_date_region (link_id, stat_date, country, province, city),
    KEY idx_link_gid_date (link_gid, stat_date),
    KEY idx_country_pv (country, stat_date, pv)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='地域访问统计表-分片0（按日+地区汇总）';

CREATE TABLE t_access_region_stats_1 LIKE t_access_region_stats_0;
CREATE TABLE t_access_region_stats_2 LIKE t_access_region_stats_0;
CREATE TABLE t_access_region_stats_3 LIKE t_access_region_stats_0;
CREATE TABLE t_access_region_stats_4 LIKE t_access_region_stats_0;
CREATE TABLE t_access_region_stats_5 LIKE t_access_region_stats_0;
CREATE TABLE t_access_region_stats_6 LIKE t_access_region_stats_0;
CREATE TABLE t_access_region_stats_7 LIKE t_access_region_stats_0;

-- ============================================================
-- 二、分片表 — ds_1 库（结构与 ds_0 完全一致）
-- ============================================================

USE shortlink_ds1;

CREATE TABLE t_link_0                LIKE shortlink_ds0.t_link_0;
CREATE TABLE t_link_1                LIKE shortlink_ds0.t_link_0;
CREATE TABLE t_link_2                LIKE shortlink_ds0.t_link_0;
CREATE TABLE t_link_3                LIKE shortlink_ds0.t_link_0;
CREATE TABLE t_link_4                LIKE shortlink_ds0.t_link_0;
CREATE TABLE t_link_5                LIKE shortlink_ds0.t_link_0;
CREATE TABLE t_link_6                LIKE shortlink_ds0.t_link_0;
CREATE TABLE t_link_7                LIKE shortlink_ds0.t_link_0;

CREATE TABLE t_access_log_0          LIKE shortlink_ds0.t_access_log_0;
CREATE TABLE t_access_log_1          LIKE shortlink_ds0.t_access_log_0;
CREATE TABLE t_access_log_2          LIKE shortlink_ds0.t_access_log_0;
CREATE TABLE t_access_log_3          LIKE shortlink_ds0.t_access_log_0;
CREATE TABLE t_access_log_4          LIKE shortlink_ds0.t_access_log_0;
CREATE TABLE t_access_log_5          LIKE shortlink_ds0.t_access_log_0;
CREATE TABLE t_access_log_6          LIKE shortlink_ds0.t_access_log_0;
CREATE TABLE t_access_log_7          LIKE shortlink_ds0.t_access_log_0;

CREATE TABLE t_access_stats_0        LIKE shortlink_ds0.t_access_stats_0;
CREATE TABLE t_access_stats_1        LIKE shortlink_ds0.t_access_stats_0;
CREATE TABLE t_access_stats_2        LIKE shortlink_ds0.t_access_stats_0;
CREATE TABLE t_access_stats_3        LIKE shortlink_ds0.t_access_stats_0;
CREATE TABLE t_access_stats_4        LIKE shortlink_ds0.t_access_stats_0;
CREATE TABLE t_access_stats_5        LIKE shortlink_ds0.t_access_stats_0;
CREATE TABLE t_access_stats_6        LIKE shortlink_ds0.t_access_stats_0;
CREATE TABLE t_access_stats_7        LIKE shortlink_ds0.t_access_stats_0;

CREATE TABLE t_access_region_stats_0 LIKE shortlink_ds0.t_access_region_stats_0;
CREATE TABLE t_access_region_stats_1 LIKE shortlink_ds0.t_access_region_stats_0;
CREATE TABLE t_access_region_stats_2 LIKE shortlink_ds0.t_access_region_stats_0;
CREATE TABLE t_access_region_stats_3 LIKE shortlink_ds0.t_access_region_stats_0;
CREATE TABLE t_access_region_stats_4 LIKE shortlink_ds0.t_access_region_stats_0;
CREATE TABLE t_access_region_stats_5 LIKE shortlink_ds0.t_access_region_stats_0;
CREATE TABLE t_access_region_stats_6 LIKE shortlink_ds0.t_access_region_stats_0;
CREATE TABLE t_access_region_stats_7 LIKE shortlink_ds0.t_access_region_stats_0;

-- ============================================================
-- 三、全局表（不分片，放在 ds_0 库）
-- ============================================================

USE shortlink_ds0;

-- -------------------------------------------------------
-- 3.1 分组表
-- -------------------------------------------------------
CREATE TABLE t_group (
    id              BIGINT        NOT NULL AUTO_INCREMENT                COMMENT '主键ID',
    gid             VARCHAR(32)   NOT NULL                               COMMENT '分组唯一标识（t_link分片键的值来源）',
    name            VARCHAR(128)  NOT NULL                               COMMENT '分组名称',
    description     VARCHAR(512)  NULL                                   COMMENT '分组描述',
    sort_order      INT           NOT NULL  DEFAULT 0                   COMMENT '排序序号',
    create_user_id  BIGINT        NOT NULL                               COMMENT '创建人ID',
    create_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    update_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag        TINYINT       NOT NULL  DEFAULT 0                   COMMENT '删除标记：0-正常 1-删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_gid (gid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='短链接分组表（全局表）';

-- -------------------------------------------------------
-- 3.2 用户表
-- -------------------------------------------------------
CREATE TABLE t_user (
    id              BIGINT        NOT NULL AUTO_INCREMENT                COMMENT '主键ID',
    username        VARCHAR(64)   NOT NULL                               COMMENT '用户名（登录账号）',
    password        VARCHAR(256)  NOT NULL                               COMMENT '密码（BCrypt加密）',
    real_name       VARCHAR(64)   NULL                                   COMMENT '真实姓名',
    email           VARCHAR(128)  NULL                                   COMMENT '邮箱',
    role            VARCHAR(32)   NOT NULL  DEFAULT 'USER'              COMMENT '角色：ADMIN-管理员 USER-普通用户',
    status          TINYINT       NOT NULL  DEFAULT 1                   COMMENT '状态：0-禁用 1-正常',
    last_login_time DATETIME      NULL                                   COMMENT '最后登录时间',
    create_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP   COMMENT '创建时间',
    update_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag        TINYINT       NOT NULL  DEFAULT 0                   COMMENT '删除标记：0-正常 1-删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='系统用户表';

-- -------------------------------------------------------
-- 3.3 操作日志表
-- -------------------------------------------------------
CREATE TABLE t_operation_log (
    id              BIGINT        NOT NULL AUTO_INCREMENT                COMMENT '主键ID',
    user_id         BIGINT        NOT NULL                               COMMENT '操作人ID',
    username        VARCHAR(64)   NOT NULL                               COMMENT '操作人用户名（冗余）',
    operation_type  VARCHAR(32)   NOT NULL                               COMMENT '操作类型：CREATE/UPDATE/DELETE/PAUSE/ENABLE',
    target_type     VARCHAR(32)   NOT NULL                               COMMENT '目标类型：LINK/GROUP/USER',
    target_id       VARCHAR(64)   NOT NULL                               COMMENT '目标ID',
    target_name     VARCHAR(256)  NULL                                   COMMENT '目标名称（冗余）',
    detail          TEXT          NULL                                   COMMENT '操作详情（变更前后JSON）',
    request_ip      VARCHAR(64)   NULL                                   COMMENT '请求IP',
    create_time     DATETIME      NOT NULL  DEFAULT CURRENT_TIMESTAMP   COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_user_time (user_id, create_time),
    KEY idx_target (target_type, target_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='操作日志表（按月归档，保留12个月）';

-- ============================================================
-- 四、初始化数据
-- ============================================================

-- 默认分组（必须存在，作为短链接的默认归属）
INSERT INTO t_group (gid, name, description, sort_order, create_user_id) VALUES
('default', '默认分组', '系统默认分组，新建短链接的默认归属', 0, 0);

-- 管理员账号（用户名: admin，密码: admin123，BCrypt加密）
INSERT INTO t_user (username, password, real_name, role) VALUES
('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iAt6Z5Eh', '系统管理员', 'ADMIN');
