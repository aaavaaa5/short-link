package com.app.util;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 * <p>
 * 负责 Token 的生成、解析和校验。
 * 使用 HS256 对称加密算法，密钥通过配置注入。
 * <p>
 * Token 结构：
 * <pre>
 *   Header:  {"alg": "HS256"}
 *   Payload: {"sub": userId, "username": "xxx", "iat": ..., "exp": ...}
 * </pre>
 */
@Component
public class JwtUtil {

    /** 签发者 */
    private static final String ISSUER = "shortlink-admin";

    /** Token 有效期：默认 24 小时 */
    @Value("${shortlink.jwt.expire-hours:24}")
    private int expireHours;

    /** 签名密钥（至少 256 位，通过配置注入） */
    @Value("${shortlink.jwt.secret:shortlink-admin-secret-key-min-256-bits!!}")
    private String secret;

    // ==================== 生成 Token ====================

    /**
     * 为用户生成 JWT Token
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @return JWT Token 字符串
     */
    public String generateToken(Long userId, String username) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("username", username);

        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireHours * 3600_000L);

        return Jwts.builder()
                .setClaims(claims)
                .setSubject(String.valueOf(userId))
                .setIssuer(ISSUER)
                .setIssuedAt(now)
                .setExpiration(expiration)
                .signWith(getKey())
                .compact();
    }

    // ==================== 解析 Token ====================

    /**
     * 解析 Token 返回 Claims
     *
     * @param token JWT Token 字符串
     * @return Claims，解析失败返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parserBuilder()
                    .setSigningKey(getKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody();
        } catch (JwtException e) {
            return null;
        }
    }

    /**
     * 校验 Token 是否有效
     */
    public boolean validateToken(String token) {
        return parseToken(token) != null;
    }

    /**
     * 从 Token 中获取用户 ID
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        return Long.valueOf(claims.getSubject());
    }

    /**
     * 从 Token 中获取用户名
     */
    public String getUsername(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        return claims.get("username", String.class);
    }

    // ==================== 私有方法 ====================

    private SecretKey getKey() {
        // 确保密钥 ≥ 256 位
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
