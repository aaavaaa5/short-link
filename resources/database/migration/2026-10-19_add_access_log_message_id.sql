-- Existing deployments: add the Redis Stream message ID used for idempotent consumption.
-- New installations already get this column and index from link.sql.

ALTER TABLE shortlink_ds0.t_access_log_0
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds0.t_access_log_1
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds0.t_access_log_2
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds0.t_access_log_3
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds0.t_access_log_4
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds0.t_access_log_5
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds0.t_access_log_6
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds0.t_access_log_7
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);

ALTER TABLE shortlink_ds1.t_access_log_0
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds1.t_access_log_1
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds1.t_access_log_2
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds1.t_access_log_3
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds1.t_access_log_4
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds1.t_access_log_5
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds1.t_access_log_6
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
ALTER TABLE shortlink_ds1.t_access_log_7
    ADD COLUMN message_id VARCHAR(64) NULL COMMENT 'Redis Stream消息ID（消费幂等）',
    ADD UNIQUE KEY uk_link_gid_message (link_gid, message_id);
