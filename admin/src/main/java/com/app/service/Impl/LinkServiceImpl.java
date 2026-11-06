package com.app.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.app.common.bloom.BloomFilterHelper;
import com.app.common.cache.CacheKeys;
import com.app.dto.req.LinkCreateReqDTO;
import com.app.dto.req.LinkPageReqDTO;
import com.app.dto.req.LinkUpdateReqDTO;
import com.app.dto.resp.LinkVO;
import com.app.entity.LinkEntity;
import com.app.entity.LinkRouteEntity;
import com.app.entity.Result;
import com.app.mapper.LinkRouteMapper;
import com.app.mapper.LinkMapper;
import com.app.service.LinkService;
import com.app.util.ShortLinkGenerator;
import com.app.util.UserContext;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.dao.DuplicateKeyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 短链接服务实现
 * <p>
 * 创建短链接：生成分布式 ID → 路由表占位短码 → 分片主表入库。
 * 分页查询：支持 gid / shortUri / originUrl / title / enableStatus 多条件组合筛选。
 */
@Service
public class LinkServiceImpl implements LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkServiceImpl.class);

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private LinkRouteMapper linkRouteMapper;

    @Autowired
    private ShortLinkGenerator shortLinkGenerator;

    @Autowired
    private IdentifierGenerator identifierGenerator;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private BloomFilterHelper bloomFilterHelper;

    /** 短链接域名前缀 */
    @Value("${shortlink.domain:http://localhost:9000/s}")
    private String domain;

    private static final int MAX_RETRY = 3;
    private static final String CLICK_KEY_PATTERN = "shortlink:click:%d:%s:%s";

    // ==================== 创建 ====================

    @Override
    public Result<LinkVO> create(LinkCreateReqDTO req) {
        // 1. 构建实体（短码在下面的重试循环里确定）
        LinkEntity entity = new LinkEntity();
        entity.setOriginUrl(req.getOriginUrl());
        entity.setGid("u_" + UserContext.getUserId());
        entity.setTitle(StrUtil.isBlank(req.getTitle()) ? "未命名" : req.getTitle());
        entity.setDescribe(req.getDescribe());
        entity.setRedirectType(req.getRedirectType() != null ? req.getRedirectType() : 0);
        entity.setExpireType(req.getExpireType() != null ? req.getExpireType() : 0);
        entity.setExpireTime(req.getExpireTime());
        entity.setCreatedType(req.getCreatedType() != null ? req.getCreatedType() : 1);
        entity.setCreateUserId(UserContext.getUserId());
        entity.setEnableStatus(1);

        // 2. 路由表主键原子占位，替代跨 16 张分片表的重复检查
        boolean inserted = false;
        for (int i = 0; i < MAX_RETRY && !inserted; i++) {
            String candidate = shortLinkGenerator.generate(req.getOriginUrl());
            entity.setId(identifierGenerator.nextId(entity).longValue());

            LinkRouteEntity route = new LinkRouteEntity();
            route.setShortUri(candidate);
            route.setLinkId(entity.getId());
            route.setGid(entity.getGid());
            route.setCreateTime(LocalDateTime.now());
            try {
                linkRouteMapper.insert(route);
            } catch (DuplicateKeyException e) {
                continue;
            }

            try {
                entity.setShortUri(candidate);
                // 先写布隆位图、再入库；任一失败都释放刚占用的短码
                bloomFilterHelper.add(candidate);
                linkMapper.insert(entity);
                inserted = true;
            } catch (Exception e) {
                try {
                    linkRouteMapper.deleteById(candidate);
                } catch (Exception cleanupException) {
                    log.error("释放短码路由失败: shortUri={}", candidate, cleanupException);
                }
                throw e;
            }
        }
        if (!inserted) {
            return Result.fail("短码生成失败，请重试");
        }

        // 3. 返回 VO
        return Result.success(toVO(entity));
    }

    // ==================== 更新 ====================

    @Override
    public Result<Void> update(LinkUpdateReqDTO req) {
        LinkEntity exist = selectCurrentUserLink(req.getId());
        if (exist == null) {
            return Result.fail(Result.NOT_FOUND, "短链接不存在");
        }
        // 校验所有权：只能修改自己的短链接
        if (!exist.getCreateUserId().equals(UserContext.getUserId())) {
            return Result.fail(Result.FORBIDDEN, "无权修改该短链接");
        }

        // doUpdate 只拼一条「仅更新非空字段」的原子 UPDATE，不存在读-改-写，
        // 并发更新本身不会互相覆盖，因此不需要分布式锁
        doUpdate(req);
        evictCache(exist.getShortUri());
        return Result.success();
    }

    private void doUpdate(LinkUpdateReqDTO req) {
        // 所有可更新字段都为 null 时直接返回：
        // 否则 MyBatis-Plus 会拼出非法 SQL "UPDATE t_link SET WHERE id=?"，接口直接 500
        if (req.getOriginUrl() == null && req.getTitle() == null && req.getDescribe() == null
                && req.getRedirectType() == null && req.getExpireType() == null
                && req.getExpireTime() == null && req.getEnableStatus() == null) {
            return;
        }

        // 仅更新非 null 字段
        LambdaUpdateWrapper<LinkEntity> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(LinkEntity::getGid, currentUserGid());
        wrapper.eq(LinkEntity::getId, req.getId());
        if (req.getOriginUrl() != null) wrapper.set(LinkEntity::getOriginUrl, req.getOriginUrl());
        if (req.getTitle() != null) wrapper.set(LinkEntity::getTitle, req.getTitle());
        if (req.getDescribe() != null) wrapper.set(LinkEntity::getDescribe, req.getDescribe());
        if (req.getRedirectType() != null) wrapper.set(LinkEntity::getRedirectType, req.getRedirectType());
        if (req.getExpireType() != null) wrapper.set(LinkEntity::getExpireType, req.getExpireType());
        if (req.getExpireTime() != null) wrapper.set(LinkEntity::getExpireTime, req.getExpireTime());
        if (req.getEnableStatus() != null) wrapper.set(LinkEntity::getEnableStatus, req.getEnableStatus());

        linkMapper.update(null, wrapper);
    }

    /**
     * 写操作后删除 Redis 里的实体缓存（L2），让"停用 / 删除 / 改原链接"尽快生效。
     * <p>
     * L1 是各个 project 实例自己的 JVM 内存，跨进程删不掉，只能依赖它 60 秒的 TTL 自然过期，
     * 所以这里只能做到 L2 即时失效、L1 最长 1 分钟内失效。
     */
    private void evictCache(String shortUri) {
        if (StrUtil.isBlank(shortUri)) {
            return;
        }
        try {
            redisTemplate.delete(CacheKeys.linkEntity(shortUri));
        } catch (Exception e) {
            // 删缓存失败不影响主流程：缓存还有 TTL 兜底，最多晚一点生效
            log.warn("删除短链缓存失败: shortUri={}", shortUri, e);
        }
    }

    // ==================== 删除（逻辑删除） ====================

    @Override
    public Result<Void> delete(Long id) {
        LinkEntity exist = selectCurrentUserLink(id);
        if (exist == null) {
            return Result.fail(Result.NOT_FOUND, "短链接不存在");
        }
        // 校验所有权：只能删除自己的短链接
        if (!exist.getCreateUserId().equals(UserContext.getUserId())) {
            return Result.fail(Result.FORBIDDEN, "无权删除该短链接");
        }
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(LinkEntity::getGid, currentUserGid());
        wrapper.eq(LinkEntity::getId, id);
        linkMapper.delete(wrapper);
        evictCache(exist.getShortUri());
        return Result.success();
    }

    // ==================== 分页查询 ====================

    @Override
    public Result<IPage<LinkVO>> page(LinkPageReqDTO req) {
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        // 用户只能看到自己创建的短链接
        wrapper.eq(LinkEntity::getCreateUserId, UserContext.getUserId());
        String gid = StrUtil.isNotBlank(req.getGid()) ? req.getGid() : currentUserGid();
        wrapper.eq(LinkEntity::getGid, gid);
        wrapper.like(StrUtil.isNotBlank(req.getShortUri()), LinkEntity::getShortUri, req.getShortUri());
        wrapper.like(StrUtil.isNotBlank(req.getOriginUrl()), LinkEntity::getOriginUrl, req.getOriginUrl());
        wrapper.like(StrUtil.isNotBlank(req.getTitle()), LinkEntity::getTitle, req.getTitle());
        wrapper.eq(req.getEnableStatus() != null, LinkEntity::getEnableStatus, req.getEnableStatus());
        wrapper.eq(req.getCreatedType() != null, LinkEntity::getCreatedType, req.getCreatedType());
        wrapper.orderByDesc(LinkEntity::getCreateTime);

        Page<LinkEntity> page = new Page<>(req.getPage(), req.getSize());
        // ShardingSphere 不支持跨分片 count，禁用计数查询
        page.setSearchCount(false);
        IPage<LinkEntity> entityPage = linkMapper.selectPage(page, wrapper);
        // 手动设置 total（近似值，避免前端分页错乱）
        if (entityPage.getRecords().size() < req.getSize()) {
            entityPage.setTotal((long) (req.getPage() - 1) * req.getSize() + entityPage.getRecords().size());
        } else {
            entityPage.setTotal((long) req.getPage() * req.getSize() + 1);
        }

        // 转换 VO + 用 Redis 实时数据覆盖 todayPv/todayUv/todayIpCount
        // 累计 = DB 历史 + Redis 今日（聚合任务凌晨才写入 DB）
        String today = LocalDate.now().toString();
        IPage<LinkVO> voPage = entityPage.convert(entity -> {
            LinkVO vo = toVO(entity);
            int todayPv = getRedisInt(String.format(CLICK_KEY_PATTERN, entity.getId(), today, "pv"));
            int todayUv = getRedisInt(String.format(CLICK_KEY_PATTERN, entity.getId(), today, "uv"));
            int todayIp = getRedisInt(String.format(CLICK_KEY_PATTERN, entity.getId(), today, "ip"));
            vo.setTodayPv(todayPv);
            vo.setTodayUv(todayUv);
            vo.setTodayIpCount(todayIp);
            vo.setTotalPv((vo.getTotalPv() != null ? vo.getTotalPv() : 0L) + todayPv);
            vo.setTotalUv((vo.getTotalUv() != null ? vo.getTotalUv() : 0L) + todayUv);
            vo.setTotalIpCount((vo.getTotalIpCount() != null ? vo.getTotalIpCount() : 0L) + todayIp);
            return vo;
        });
        return Result.success(voPage);
    }

    // ==================== 详情查询 ====================

    @Override
    public LinkVO getById(Long id) {
        LinkEntity entity = selectCurrentUserLink(id);
        return entity != null ? toVO(entity) : null;
    }

    // ==================== 私有方法 ====================

    private LinkEntity selectCurrentUserLink(Long id) {
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(LinkEntity::getGid, currentUserGid());
        wrapper.eq(LinkEntity::getId, id);
        return linkMapper.selectOne(wrapper);
    }

    private String currentUserGid() {
        return "u_" + UserContext.getUserId();
    }

    private LinkVO toVO(LinkEntity entity) {
        LinkVO vo = new LinkVO();
        BeanUtil.copyProperties(entity, vo);
        vo.setFullShortUrl(domain + "/" + entity.getShortUri());
        return vo;
    }

    /** 从 Redis 读取整数（PV/UV/IP 计数） */
    private int getRedisInt(String key) {
        try {
            String val = redisTemplate.opsForValue().get(key);
            return val != null ? Integer.parseInt(val) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
