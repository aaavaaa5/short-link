package com.app.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户上下文持有器（ThreadLocal）
 * <p>
 * 在请求进入时由 {@code LoginInterceptor} 将 JWT 中的用户信息存入，
 * 请求结束时由 {@code WebMvcConfig} 注册的拦截器清理，防止内存泄漏。
 * <p>
 * 使用方式：
 * <pre>
 *   // 拦截器中设置
 *   UserContext.set(userId, username, realName);
 *
 *   // 业务代码中获取
 *   Long userId = UserContext.getUserId();
 *   String username = UserContext.getUsername();
 *
 *   // 请求结束后清理（必须！避免线程池复用导致串值）
 *   UserContext.clear();
 * </pre>
 */
public class UserContext {

    private static final ThreadLocal<Map<String, Object>> CONTEXT = new ThreadLocal<>();

    private static final String KEY_USER_ID = "userId";
    private static final String KEY_USERNAME = "username";
    private static final String KEY_REAL_NAME = "realName";

    // ==================== 设置 ====================

    /**
     * 设置当前用户信息
     */
    public static void set(Long userId, String username, String realName) {
        Map<String, Object> map = new HashMap<>();
        map.put(KEY_USER_ID, userId);
        map.put(KEY_USERNAME, username);
        map.put(KEY_REAL_NAME, realName);
        CONTEXT.set(map);
    }

    // ==================== 获取 ====================

    /**
     * 获取当前用户 ID
     */
    public static Long getUserId() {
        Map<String, Object> map = CONTEXT.get();
        if (map == null) return null;
        return (Long) map.get(KEY_USER_ID);
    }

    /**
     * 获取当前用户名
     */
    public static String getUsername() {
        Map<String, Object> map = CONTEXT.get();
        if (map == null) return null;
        return (String) map.get(KEY_USERNAME);
    }

    /**
     * 获取当前用户真实姓名
     */
    public static String getRealName() {
        Map<String, Object> map = CONTEXT.get();
        if (map == null) return null;
        return (String) map.get(KEY_REAL_NAME);
    }

    // ==================== 清理 ====================

    /**
     * 清理当前线程用户信息（必须调用，防止内存泄漏和线程池串值）
     */
    public static void clear() {
        CONTEXT.remove();
    }
}
