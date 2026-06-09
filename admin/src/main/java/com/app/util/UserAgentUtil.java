package com.app.util;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * User-Agent 解析工具
 * <p>
 * 从请求头 User-Agent 中解析浏览器、操作系统、设备类型。
 * 使用正则匹配主流浏览器和 OS，无需第三方 UA 解析库。
 */
@Component
public class UserAgentUtil {

    // ==================== 浏览器正则 ====================

    private static final Pattern EDGE = Pattern.compile("Edg/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHROME = Pattern.compile("Chrome/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FIREFOX = Pattern.compile("Firefox/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SAFARI = Pattern.compile("Version/(\\d+).*Safari", Pattern.CASE_INSENSITIVE);
    private static final Pattern IE = Pattern.compile("MSIE (\\d+)|Trident.*rv:(\\d+)", Pattern.CASE_INSENSITIVE);

    // ==================== OS 正则 ====================

    private static final Pattern WINDOWS = Pattern.compile("Windows NT (\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MAC = Pattern.compile("Mac OS X (\\d+[_\\d]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINUX = Pattern.compile("Linux", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANDROID = Pattern.compile("Android (\\d+\\.?\\d*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern IOS = Pattern.compile("iPhone OS (\\d+[_\\d]*)", Pattern.CASE_INSENSITIVE);

    // ==================== 设备正则 ====================

    private static final Pattern MOBILE = Pattern.compile(
            "Mobile|Android.*Mobile|iPhone|iPod|BlackBerry|Opera Mini|IEMobile", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLET = Pattern.compile("iPad|Android(?!.*Mobile)|Tablet", Pattern.CASE_INSENSITIVE);

    /** 设备类型常量 */
    public static final String DEVICE_PC = "PC";
    public static final String DEVICE_MOBILE = "Mobile";
    public static final String DEVICE_TABLET = "Tablet";
    public static final String DEVICE_UNKNOWN = "Unknown";

    // ==================== 解析方法 ====================

    /**
     * 解析浏览器名称
     */
    public String parseBrowser(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return DEVICE_UNKNOWN;
        }
        if (EDGE.matcher(userAgent).find()) return "Edge";
        if (CHROME.matcher(userAgent).find()) return "Chrome";
        if (FIREFOX.matcher(userAgent).find()) return "Firefox";
        if (SAFARI.matcher(userAgent).find()) return "Safari";
        if (IE.matcher(userAgent).find()) return "IE";
        return "Other";
    }

    /**
     * 解析操作系统名称
     */
    public String parseOs(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return DEVICE_UNKNOWN;
        }
        if (IOS.matcher(userAgent).find()) return "iOS";
        if (ANDROID.matcher(userAgent).find()) return "Android";
        if (WINDOWS.matcher(userAgent).find()) return "Windows";
        if (MAC.matcher(userAgent).find()) return "macOS";
        if (LINUX.matcher(userAgent).find()) return "Linux";
        return "Other";
    }

    /**
     * 解析设备类型：PC / Mobile / Tablet
     */
    public String parseDevice(String userAgent) {
        if (StrUtil.isBlank(userAgent)) {
            return DEVICE_UNKNOWN;
        }
        if (TABLET.matcher(userAgent).find()) return DEVICE_TABLET;
        if (MOBILE.matcher(userAgent).find()) return DEVICE_MOBILE;
        return DEVICE_PC;
    }
}
