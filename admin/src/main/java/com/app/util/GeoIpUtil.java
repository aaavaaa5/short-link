package com.app.util;

import cn.hutool.core.util.StrUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GeoIP 解析工具
 * <p>
 * 根据 IP 地址解析地理位置（国家/省份/城市）。
 * <p>
 * 实现方式：
 * <ul>
 *   <li>优先使用本地 GeoLite2 数据库（需部署 mmdb 文件）</li>
 *   <li>无本地库时回退到简单规则：内网 IP 返回"内网"，公网 IP 返回"未知"</li>
 *   <li>生产环境建议接入 MaxMind GeoLite2 或 ip2region</li>
 * </ul>
 * <p>
 * 当前为简化实现，可根据需要替换为 MaxMind GeoIP2 Reader 或 ip2region。
 */
@Component
public class GeoIpUtil {

    private static final Logger log = LoggerFactory.getLogger(GeoIpUtil.class);

    /** 内网 IP 段前缀缓存 */
    private static final Map<String, String> PRIVATE_IP_CACHE = new ConcurrentHashMap<>();

    static {
        PRIVATE_IP_CACHE.put("127.", "本机");
        PRIVATE_IP_CACHE.put("0.", "本机");
    }

    /** 内网 IP 段前缀 */
    private static final String[] PRIVATE_PREFIXES = {
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "127.", "0."
    };

    // ==================== 公开方法 ====================

    /**
     * 解析 IP 对应的国家
     */
    public String parseCountry(String ip) {
        if (isPrivateIp(ip)) {
            return "内网";
        }
        // TODO: 接入 MaxMind GeoLite2 或 ip2region 数据库
        // 当前简化为返回未知
        return "未知";
    }

    /**
     * 解析 IP 对应的省份
     */
    public String parseProvince(String ip) {
        if (isPrivateIp(ip)) {
            return "内网";
        }
        return "未知";
    }

    /**
     * 解析 IP 对应的城市
     */
    public String parseCity(String ip) {
        if (isPrivateIp(ip)) {
            return "内网";
        }
        return "未知";
    }

    // ==================== 私有方法 ====================

    /**
     * 判断是否为内网 IP
     */
    private boolean isPrivateIp(String ip) {
        if (StrUtil.isBlank(ip)) {
            return false;
        }
        for (String prefix : PRIVATE_PREFIXES) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
