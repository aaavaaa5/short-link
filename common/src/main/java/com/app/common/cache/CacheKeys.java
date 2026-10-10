package com.app.common.cache;

/**
 * 跨服务共享的 Redis Key 规范
 * <p>
 * project 负责写缓存、admin 在修改/删除短链后负责删缓存，两边必须拼出完全一样的 key，
 * 所以统一放在 common 模块，而不是各自写字符串字面量。
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    private static final String LINK_ENTITY_PREFIX = "shortlink:";
    private static final String NULL_MARK_PREFIX = "shortlink:null:";

    /** 短链实体缓存：shortlink:{shortUri} */
    public static String linkEntity(String shortUri) {
        return LINK_ENTITY_PREFIX + shortUri;
    }

    /** 空值标记（该短码不存在）：shortlink:null:{shortUri} */
    public static String nullMark(String shortUri) {
        return NULL_MARK_PREFIX + shortUri;
    }
}
