package com.app.mq;

import com.app.entity.AccessLogEntity;
import com.app.mapper.AccessLogMapper;
import com.app.util.GeoIpUtil;
import com.app.util.UserAgentUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 访问事件消费者
 * <p>
 * 消费 project 模块通过 {@code AccessEventProducer} 写入的访问事件，
 * 解析 UA（浏览器/OS/设备）、解析 GeoIP（国家/省份/城市），
 * 写入 t_access_log 持久化。
 */
@Component
public class AccessEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccessEventConsumer.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private AccessLogMapper accessLogMapper;

    @Autowired
    private UserAgentUtil userAgentUtil;

    @Autowired
    private GeoIpUtil geoIpUtil;

    private static final String STREAM_KEY = "shortlink:access:stream";
    private static final String GROUP = "admin-consumer-group";
    private static final String CONSUMER = "admin-consumer-1";
    private static final int BATCH_SIZE = 100;
    private static final ZoneId ZONE_ID = ZoneId.systemDefault();

    // ==================== 定时消费 ====================

    @Scheduled(fixedDelay = 1000)
    public void consume() {
        try {
            ensureGroup();
            consumePending();
            consumeNew();
        } catch (Exception e) {
            log.error("消费访问事件失败", e);
        }
    }

    // ==================== 消息处理 ====================

    /**
     * 消费新消息（从未被消费过的）
     */
    private void consumeNew() {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .read(Consumer.from(GROUP, CONSUMER),
                        StreamReadOptions.empty().count(BATCH_SIZE),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

        if (records == null || records.isEmpty()) {
            return;
        }

        for (MapRecord<String, Object, Object> record : records) {
            processRecord(record);
            redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP, record.getId().getValue());
        }
    }

    /**
     * 消费待处理消息（已被读取但未 ACK 的，如上次处理异常）
     */
    private void consumePending() {
        try {
            PendingMessages pending = redisTemplate.opsForStream()
                    .pending(STREAM_KEY, GROUP, Range.unbounded(), BATCH_SIZE);
            if (pending == null || pending.isEmpty()) {
                return;
            }
            for (PendingMessage msg : pending) {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .range(STREAM_KEY,
                                Range.closed(msg.getIdAsString(), msg.getIdAsString()));
                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        processRecord(record);
                        redisTemplate.opsForStream().acknowledge(STREAM_KEY, GROUP,
                                record.getId().getValue());
                    }
                }
            }
        } catch (Exception ignored) {
            // Pending 查询可能因消费者组刚创建而失败，忽略
        }
    }

    // ==================== 记录处理 ====================

    private void processRecord(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> map = record.getValue();
            String userAgent = getStr(map, "user_agent");
            String visitorIp = getStr(map, "visitor_ip");

            AccessLogEntity entity = new AccessLogEntity();
            entity.setLinkId(parseLong(map, "link_id"));
            entity.setLinkGid(getStr(map, "link_gid"));
            entity.setShortUri(getStr(map, "short_uri"));
            entity.setVisitorId(getStr(map, "visitor_id"));
            entity.setVisitorIp(visitorIp);
            entity.setReferer(getStr(map, "referer"));

            // UA 解析 → 浏览器 / OS / 设备
            entity.setUserAgent(userAgent);
            entity.setBrowser(userAgentUtil.parseBrowser(userAgent));
            entity.setOs(userAgentUtil.parseOs(userAgent));
            entity.setDevice(userAgentUtil.parseDevice(userAgent));

            // GeoIP 解析 → 国家 / 省份 / 城市
            entity.setCountry(geoIpUtil.parseCountry(visitorIp));
            entity.setProvince(geoIpUtil.parseProvince(visitorIp));
            entity.setCity(geoIpUtil.parseCity(visitorIp));

            // 时间
            String ts = getStr(map, "access_time");
            entity.setAccessTime(ts != null
                    ? LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(ts)), ZONE_ID)
                    : LocalDateTime.now());

            accessLogMapper.insert(entity);

        } catch (Exception e) {
            log.error("处理访问事件失败: recordId={}", record.getId(), e);
        }
    }

    // ==================== 私有工具方法 ====================

    private void ensureGroup() {
        try {
            redisTemplate.opsForStream().createGroup(STREAM_KEY, GROUP);
        } catch (Exception ignored) {
            // 消费者组已存在
        }
    }

    private String getStr(Map<Object, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private Long parseLong(Map<Object, Object> map, String key) {
        try {
            String val = getStr(map, key);
            return val != null ? Long.parseLong(val) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
