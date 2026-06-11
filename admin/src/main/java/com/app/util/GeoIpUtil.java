package com.app.util;

import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import org.lionsoul.ip2region.xdb.Searcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * GeoIP 解析工具 —— 基于 ip2region 离线数据库
 * <p>
 * ip2region 特点：
 * <ul>
 *   <li>完全离线，不需要任何外部服务</li>
 *   <li>内存查询 0.1x 毫秒，性能极高</li>
 *   <li>中国 IP 精确到城市，国外 IP 精确到国家</li>
 *   <li>xdb 数据文件约 11MB，放在 resources/ip2region/ 下</li>
 * </ul>
 * <p>
 * 数据文件来源：<a href="https://github.com/lionsoul2014/ip2region">ip2region GitHub</a>
 * <br>
 * 将 ip2region.xdb 放到 src/main/resources/ip2region/ip2region.xdb 即可。
 */
@Component
public class GeoIpUtil {

    private static final Logger log = LoggerFactory.getLogger(GeoIpUtil.class);

    /** ip2region 查询器（线程安全，全局复用） */
    private volatile Searcher searcher;

    /** 内网 IP 段前缀（不查库，直接返回） */
    private static final String[] PRIVATE_PREFIXES = {
            "10.", "172.16.", "172.17.", "172.18.", "172.19.",
            "172.20.", "172.21.", "172.22.", "172.23.", "172.24.",
            "172.25.", "172.26.", "172.27.", "172.28.", "172.29.",
            "172.30.", "172.31.", "192.168.", "127.", "0."
    };

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        try {
            // 从 classpath 读取 xdb 文件，复制到临时目录（避免 JAR 内无法 mmap）
            ClassPathResource resource = new ClassPathResource("ip2region/ip2region.xdb");
            if (!resource.exists()) {
                log.warn("ip2region.xdb 文件不存在，GeoIP 解析将返回未知。"
                        + "请从 https://github.com/lionsoul2014/ip2region 下载 xdb 文件，"
                        + "放到 src/main/resources/ip2region/ip2region.xdb");
                return;
            }

            Path tempFile = Files.createTempFile("ip2region_", ".xdb");
            tempFile.toFile().deleteOnExit();
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            }

            searcher = Searcher.newWithFileOnly(tempFile.toString());
            log.info("GeoIP 初始化完成，ip2region.xdb 已加载");
        } catch (IOException e) {
            log.error("GeoIP 初始化失败", e);
        }
    }

    // ==================== 公开方法 ====================

    /**
     * 解析 IP 对应的国家
     */
    public String parseCountry(String ip) {
        if (isPrivateOrBlank(ip)) {
            return isPrivateIp(ip) ? "内网" : "未知";
        }
        String[] parts = queryIp2Region(ip);
        return parts.length > 0 ? parts[0] : "未知";
    }

    /**
     * 解析 IP 对应的省份
     */
    public String parseProvince(String ip) {
        if (isPrivateOrBlank(ip)) {
            return isPrivateIp(ip) ? "内网" : "未知";
        }
        String[] parts = queryIp2Region(ip);
        // 格式：国家|区域|省份|城市|ISP
        return parts.length > 2 ? parts[2] : "未知";
    }

    /**
     * 解析 IP 对应的城市
     */
    public String parseCity(String ip) {
        if (isPrivateOrBlank(ip)) {
            return isPrivateIp(ip) ? "内网" : "未知";
        }
        String[] parts = queryIp2Region(ip);
        return parts.length > 3 ? parts[3] : "未知";
    }

    // ==================== 私有方法 ====================

    /**
     * 调用 ip2region 查询，返回 "国家|区域|省份|城市|ISP" 拆分后的数组
     */
    private String[] queryIp2Region(String ip) {
        if (searcher == null) {
            return new String[0];
        }
        try {
            String result = searcher.search(ip);
            if (StrUtil.isBlank(result)) {
                return new String[0];
            }
            return result.split("\\|", -1);
        } catch (Exception e) {
            log.debug("ip2region 查询失败: ip={}", ip, e);
            return new String[0];
        }
    }

    private boolean isPrivateOrBlank(String ip) {
        return StrUtil.isBlank(ip) || isPrivateIp(ip);
    }

    /**
     * 判断是否为内网 IP
     */
    private boolean isPrivateIp(String ip) {
        for (String prefix : PRIVATE_PREFIXES) {
            if (ip.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
