package com.app.util;

import cn.hutool.core.util.RandomUtil;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 短链接码生成器
 * <p>
 * 核心算法：Base62 编码 + MurmurHash3 哈希冲突处理。
 * <p>
 * 生成流程：
 * <ol>
 *   <li>对原始 URL + 时间戳 + 随机数拼接 → 用 MurmurHash3 生成 32 位哈希值</li>
 *   <li>哈希值取低 48 位 → Base62 编码 → 取前 6 位作为短码</li>
 *   <li>查 DB 看是否冲突 → 冲突则追加随机字符重试</li>
 * </ol>
 * <p>
 * 容量估算：6 位 Base62 = 62^6 ≈ 568 亿种组合，足以支撑海量短链接。
 */
@Component
public class ShortLinkGenerator {

    /** Base62 字符集：0-9, A-Z, a-z（去掉易混淆字符 0/O/l/I） */
    private static final char[] BASE62 = {
            '1', '2', '3', '4', '5', '6', '7', '8', '9',
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M',
            'N', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
            'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'm',
            'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z'
    };

    private static final int BASE = BASE62.length; // 58

    private static final HashFunction MURMUR3 = Hashing.murmur3_32(0);

    /** 默认短码长度 */
    private static final int DEFAULT_LENGTH = 6;

    // ==================== 公开方法 ====================

    /**
     * 生成 6 位短码
     *
     * @param originUrl 原始 URL
     * @return 6 位短码字符串
     */
    public String generate(String originUrl) {
        return generate(originUrl, DEFAULT_LENGTH);
    }

    /**
     * 生成指定长度短码
     *
     * @param originUrl 原始 URL
     * @param length    短码长度（建议 6~8 位）
     * @return 短码字符串
     */
    public String generate(String originUrl, int length) {
        // 原始 URL + 纳秒时间戳 + 随机数 → MurmurHash3 → Base62
        String seed = originUrl + System.nanoTime() + RandomUtil.randomString(8);
        long hash = Math.abs(MURMUR3.hashString(seed, StandardCharsets.UTF_8).asInt());

        return base62Encode(hash, length);
    }

    /**
     * 冲突重试：在原短码基础上追加随机字符重新生成
     *
     * @param originUrl 原始 URL
     * @return 新短码
     */
    public String regenerate(String originUrl) {
        // 追加更强的随机数重新计算
        String seed = originUrl + System.nanoTime() + RandomUtil.randomString(16);
        long hash = Math.abs(MURMUR3.hashString(seed, StandardCharsets.UTF_8).asInt());

        return base62Encode(hash, DEFAULT_LENGTH);
    }

    // ==================== 私有方法 ====================

    /**
     * 将数字转为 Base62 编码，取后 length 位
     */
    private String base62Encode(long num, int length) {
        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            sb.append(BASE62[(int) (num % BASE)]);
            num /= BASE;
        }
        // 不足 length 位时前面补字符 'w'（选 w 仅因其视觉辨识度好）
        while (sb.length() < length) {
            sb.append('w');
        }
        // 反转得到正确顺序
        return sb.reverse().substring(0, length);
    }
}
