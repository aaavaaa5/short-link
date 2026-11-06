package com.app.mq;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.app.entity.AccessLogEntity;
import com.app.mapper.AccessLogMapper;
import com.app.util.GeoIpUtil;
import com.app.util.UserAgentUtil;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    @Value("${spring.application.name:shortlink-admin}")
    private String applicationName = "shortlink-admin";

    @Value("${shortlink.access-event.consumer-name:}")
    private String configuredConsumerName;

    @Value("${shortlink.access-event.zone-id:Asia/Shanghai}")
    private String configuredZoneId = "Asia/Shanghai";

    private static final String STREAM_KEY = "shortlink:access:stream";
    private static final String GROUP = "admin-consumer-group";
    private static final int BATCH_SIZE = 100;
    private static final int MAX_BATCHES_PER_RUN = 20;
    private static final Duration PENDING_MIN_IDLE = Duration.ofSeconds(30);

    private final Cache<String, UserAgentParts> userAgentCache = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(24, TimeUnit.HOURS)
            .build();
    private final Cache<String, GeoLocation> geoLocationCache = Caffeine.newBuilder()
            .maximumSize(20_000)
            .expireAfterAccess(24, TimeUnit.HOURS)
            .build();

    private String consumerName;
    private ZoneId accessZoneId = ZoneId.of("Asia/Shanghai");
    private volatile boolean groupReady;

    // ==================== 定时消费 ====================

    @PostConstruct
    public void initialize() {
        prepareRuntimeState();
        try {
            ensureGroupReady();
        } catch (Exception e) {
            log.warn("Redis Stream 消费者组初始化失败，将在下一次消费时重试", e);
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void consume() {
        try {
            prepareRuntimeState();
            ensureGroupReady();
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
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                    .read(Consumer.from(GROUP, consumerName),
                            StreamReadOptions.empty().count(BATCH_SIZE),
                            StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

            if (records == null || records.isEmpty()) {
                return;
            }

            for (MapRecord<String, Object, Object> record : records) {
                if (processRecord(record)) {
                    acknowledge(record.getId());
                }
            }

            if (records.size() < BATCH_SIZE) {
                return;
            }
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
                if (msg.getElapsedTimeSinceLastDelivery().compareTo(PENDING_MIN_IDLE) < 0) {
                    continue;
                }

                List<MapRecord<String, Object, Object>> claimed = redisTemplate.opsForStream()
                        .claim(STREAM_KEY, GROUP, consumerName, PENDING_MIN_IDLE, msg.getId());
                if (claimed == null || claimed.isEmpty()) {
                    cleanOrphanedPending(msg.getId());
                    continue;
                }

                for (MapRecord<String, Object, Object> record : claimed) {
                    if (processRecord(record)) {
                        acknowledge(record.getId());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("消费 Pending 访问事件失败", e);
        }
    }

    // ==================== 记录处理 ====================

    private boolean processRecord(MapRecord<String, Object, Object> record) {
        try {
            Map<Object, Object> map = record.getValue();
            String userAgent = getStr(map, "user_agent");
            String visitorIp = getStr(map, "visitor_ip");

            AccessLogEntity entity = new AccessLogEntity();
            entity.setMessageId(record.getId().getValue());
            entity.setLinkId(parseLong(map, "link_id"));
            entity.setLinkGid(getStr(map, "link_gid"));
            entity.setShortUri(getStr(map, "short_uri"));
            entity.setVisitorId(getStr(map, "visitor_id"));
            entity.setVisitorIp(visitorIp);
            entity.setReferer(getStr(map, "referer"));

            // UA 解析 → 浏览器 / OS / 设备
            entity.setUserAgent(userAgent);
            UserAgentParts userAgentParts = userAgentCache.get(
                    userAgent != null ? userAgent : "",
                    key -> new UserAgentParts(
                            userAgentUtil.parseBrowser(key),
                            userAgentUtil.parseOs(key),
                            userAgentUtil.parseDevice(key)));
            entity.setBrowser(userAgentParts.browser());
            entity.setOs(userAgentParts.os());
            entity.setDevice(userAgentParts.device());

            // GeoIP 解析 → 国家 / 省份 / 城市
            GeoLocation geoLocation = geoLocationCache.get(
                    visitorIp != null ? visitorIp : "",
                    key -> new GeoLocation(
                            geoIpUtil.parseCountry(key),
                            geoIpUtil.parseProvince(key),
                            geoIpUtil.parseCity(key)));
            entity.setCountry(geoLocation.country());
            entity.setProvince(geoLocation.province());
            entity.setCity(geoLocation.city());

            // 时间
            String ts = getStr(map, "access_time");
            entity.setAccessTime(parseAccessTime(ts, record.getId()));

            accessLogMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            log.debug("访问事件已写入，跳过重复消息: recordId={}", record.getId());
            return true;
        } catch (Exception e) {
            log.error("处理访问事件失败: recordId={}", record.getId(), e);
            return false;
        }
    }

    // ==================== 私有工具方法 ====================

    private void prepareRuntimeState() {
        if (consumerName == null) {
            consumerName = resolveConsumerName();
        }
        try {
            accessZoneId = ZoneId.of(configuredZoneId);
        } catch (Exception e) {
            log.warn("无效的访问日志时区配置: {}，回退到 Asia/Shanghai", configuredZoneId);
            configuredZoneId = "Asia/Shanghai";
            accessZoneId = ZoneId.of("Asia/Shanghai");
        }
    }

    private synchronized void ensureGroupReady() {
        if (groupReady) {
            return;
        }
        try {
            byte[] key = redisTemplate.getStringSerializer().serialize(STREAM_KEY);
            redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.streamCommands().xGroupCreate(
                            key, GROUP, ReadOffset.from("0"), true));
            groupReady = true;
            log.info("Redis Stream 消费者组已创建: stream={}, group={}", STREAM_KEY, GROUP);
        } catch (Exception e) {
            if (isGroupAlreadyExists(e)) {
                groupReady = true;
                log.debug("Redis Stream 消费者组已存在: stream={}, group={}", STREAM_KEY, GROUP);
                return;
            }
            throw e;
        }
    }

    private boolean isGroupAlreadyExists(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private String resolveConsumerName() {
        if (configuredConsumerName != null && !configuredConsumerName.isBlank()) {
            return configuredConsumerName.trim();
        }
        String host = "unknown-host";
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            log.debug("获取主机名失败，消费者名使用 unknown-host", e);
        }
        String safeHost = host.replaceAll("[^a-zA-Z0-9.-]", "-");
        return String.format("%s-%s-%d-%s",
                applicationName, safeHost, ProcessHandle.current().pid(),
                UUID.randomUUID().toString().substring(0, 8));
    }

    private LocalDateTime parseAccessTime(String timestamp, RecordId recordId) {
        if (timestamp == null || timestamp.isBlank()) {
            return LocalDateTime.now(accessZoneId);
        }
        try {
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong(timestamp)), accessZoneId);
        } catch (NumberFormatException e) {
            log.warn("访问事件 access_time 非法，使用当前时间: recordId={}, value={}",
                    recordId, timestamp);
            return LocalDateTime.now(accessZoneId);
        }
    }

    private void acknowledge(RecordId recordId) {
        Long acknowledged = redisTemplate.opsForStream()
                .acknowledge(STREAM_KEY, GROUP, recordId.getValue());
        if (acknowledged == null || acknowledged == 0) {
            log.warn("Redis Stream ACK 未生效: recordId={}", recordId);
        }
    }

    private void cleanOrphanedPending(RecordId recordId) {
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                .range(STREAM_KEY, Range.closed(recordId.getValue(), recordId.getValue()));
        if (records == null || records.isEmpty()) {
            acknowledge(recordId);
            log.warn("已清理 Stream 中不存在的 Pending 消息: recordId={}", recordId);
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

    private record UserAgentParts(String browser, String os, String device) {
    }

    private record GeoLocation(String country, String province, String city) {
    }
}
