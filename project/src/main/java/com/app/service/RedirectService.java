package com.app.service;

import com.app.entity.LinkEntity;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 短链接跳转核心服务 — 多级缓存查询 + 访问记录
 */
public interface RedirectService {

    /**
     * 根据短码查询短链接实体（L2 Redis → 布隆过滤器 → MySQL）
     */
    LinkEntity getLinkEntity(String shortUri);

    /**
     * 异步记录访问行为（Redis 计数 + Stream 消息发送）
     */
    void recordAccess(LinkEntity entity, HttpServletRequest request);
}
