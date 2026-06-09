package com.app.mq;

import cn.hutool.core.util.StrUtil;
import com.app.entity.LinkEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Redis Stream 访问事件生产者
 * <p>
 * 跳转成功后将访问事件写入 Stream（{@value #STREAM_KEY}），
 * 由 Admin 模块消费者异步解析 UA/GeoIP 后写入 t_access_log。
 */
@Component
public class AccessEventProducer {

    private static final Logger log = LoggerFactory.getLogger(AccessEventProducer.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    public static final String STREAM_KEY = "shortlink:access:stream";
    private static final int MAX_LEN = 100_000;


    public void sendAccessEvent(LinkEntity entity, HttpServletRequest request) {
        try {
            Map<String, String> message = buildMessage(entity, request);
            redisTemplate.opsForStream().add(MapRecord.create(STREAM_KEY, message));
            redisTemplate.opsForStream().trim(STREAM_KEY, MAX_LEN, false);
            log.debug("访问事件已发送: shortUri={}", entity.getShortUri());
        } catch (Exception e) {
            log.error("发送访问事件失败: shortUri={}", entity.getShortUri(), e);
        }
    }

    private Map<String, String> buildMessage(LinkEntity entity, HttpServletRequest request) {
        Map<String, String> message = new HashMap<>();
        message.put("link_id", String.valueOf(entity.getId()));
        message.put("link_gid", entity.getGid() != null ? entity.getGid() : "");
        message.put("short_uri", entity.getShortUri());
        message.put("visitor_id", generateVisitorId(request));
        message.put("visitor_ip", getClientIp(request));
        message.put("user_agent", request.getHeader("User-Agent") != null
                ? request.getHeader("User-Agent") : "");
        message.put("referer", request.getHeader("Referer") != null
                ? request.getHeader("Referer") : "");
        message.put("access_time", String.valueOf(System.currentTimeMillis()));
        return message;
    }


    public String generateVisitorId(HttpServletRequest request) {
        String ip = getClientIp(request);
        String ua = request.getHeader("User-Agent") != null ? request.getHeader("User-Agent") : "";
        return UUID.nameUUIDFromBytes((ip + ua).getBytes()).toString().replace("-", "").substring(0, 16);
    }


    public String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (StrUtil.isNotBlank(xff)) {
            return xff.split(",")[0].trim();
        }
        String xri = request.getHeader("X-Real-IP");
        if (StrUtil.isNotBlank(xri)) {
            return xri.trim();
        }
        return request.getRemoteAddr();
    }
}
