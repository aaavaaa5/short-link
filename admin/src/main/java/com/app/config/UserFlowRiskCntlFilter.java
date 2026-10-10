package com.app.config;

import com.app.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * 用户级限流过滤器（三层限流中的第 2 层）
 * <p>
 * 基于 Redis 固定时间窗口计数器，对每个用户限制每秒请求次数。
 * <p>
 * 拦截所有 /api/** 请求（白名单除外），超过阈值返回 429 Too Many Requests。
 * <p>
 * 三层限流的分工：
 * <ul>
 *   <li>第 1 层 Gateway：按 IP 做令牌桶限流（防 DDoS / 暴力破解）</li>
 *   <li>第 2 层 本过滤器：按用户做固定窗口限流，20 次/秒（防单用户高频调用 / 刷接口）</li>
 *   <li>第 3 层 Sentinel：按接口做 QPS 限流，只拦创建短链接接口（保护最重的写接口）</li>
 * </ul>
 */
@Component
public class UserFlowRiskCntlFilter implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(UserFlowRiskCntlFilter.class);

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 每秒最大请求次数 */
    private static final int MAX_REQUESTS_PER_SECOND = 20;

    /** Redis Key 前缀 */
    private static final String RATE_LIMIT_KEY = "shortlink:ratelimit:user:%d:%d";

    /** 白名单路径（无需限流） */
    private static final String[] WHITELIST = {
            "/api/auth/register",
            "/api/auth/login",
            "/swagger-ui",
            "/v3/api-docs"
    };

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // 1. 白名单放行
        String uri = request.getRequestURI();
        for (String white : WHITELIST) {
            if (uri.startsWith(white)) {
                return true;
            }
        }

        // 2. 获取用户 ID（未登录用户限流 IP）
        Long userId = UserContext.getUserId();
        long limitKey = userId != null ? userId : request.getRemoteAddr().hashCode();

        // 3. Redis 固定窗口计数
        long windowSeconds = System.currentTimeMillis() / 1000;
        String redisKey = String.format(RATE_LIMIT_KEY, limitKey, windowSeconds);

        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1) {
            // 第一次设置，加过期时间（2 秒后自动清理，防止 key 堆积）
            redisTemplate.expire(redisKey, Duration.ofSeconds(2));
        }

        // 4. 超出限流阈值
        if (count != null && count > MAX_REQUESTS_PER_SECOND) {
            log.warn("用户 {} 触发限流，{} 秒内请求 {} 次", limitKey, windowSeconds, count);
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"message\":\"请求过于频繁，请稍后再试\"}");
            return false;
        }

        return true;
    }
}
