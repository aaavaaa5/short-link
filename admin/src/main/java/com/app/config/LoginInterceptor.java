package com.app.config;

import cn.hutool.core.util.StrUtil;
import com.app.util.JwtUtil;
import com.app.util.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 登录拦截器
 * <p>
 * 从请求头 Authorization 中提取 Bearer Token，校验 JWT 并设置用户上下文。
 * <p>
 * 白名单路径（无需登录）：/api/auth/register、/api/auth/login
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Autowired
    private JwtUtil jwtUtil;

    /** Token 前缀 */
    private static final String BEARER_PREFIX = "Bearer ";

    /** 白名单路径 */
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

        // 2. 提取 Token
        String authHeader = request.getHeader("Authorization");
        if (StrUtil.isBlank(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"未登录或 Token 已过期\"}");
            return false;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        // 3. 校验 Token
        Claims claims = jwtUtil.parseToken(token);
        if (claims == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"Token 无效或已过期\"}");
            return false;
        }

        // 4. 设置用户上下文
        Long userId = Long.valueOf(claims.getSubject());
        String username = claims.get("username", String.class);
        String realName = claims.get("realName", String.class);
        UserContext.set(userId, username, realName != null ? realName : username);

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 请求结束，清理 ThreadLocal，防止内存泄漏 + 线程池串值
        UserContext.clear();
    }
}
