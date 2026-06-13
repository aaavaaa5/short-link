package com.app.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.app.dto.req.LinkCreateReqDTO;
import com.app.dto.req.LinkPageReqDTO;
import com.app.dto.req.LinkUpdateReqDTO;
import com.app.dto.resp.LinkVO;
import com.app.entity.LinkEntity;
import com.app.entity.Result;
import com.app.mapper.LinkMapper;
import com.app.service.LinkService;
import com.app.util.ShortLinkGenerator;
import com.app.util.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

/**
 * 短链接服务实现
 * <p>
 * 创建短链接：原始 URL → 生成短码 → 校验唯一性 → 入库。
 * 分页查询：支持 gid / shortUri / originUrl / title / enableStatus 多条件组合筛选。
 */
@Service
public class LinkServiceImpl implements LinkService {

    private static final Logger log = LoggerFactory.getLogger(LinkServiceImpl.class);

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private ShortLinkGenerator shortLinkGenerator;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 短链接域名前缀 */
    @Value("${shortlink.domain:http://localhost:9000/s}")
    private String domain;

    private static final int MAX_RETRY = 3;
    private static final String LOCK_PREFIX = "shortlink:lock:create:";
    private static final String UPDATE_LOCK_PREFIX = "shortlink:lock:update:";
    private static final String CLICK_KEY_PATTERN = "shortlink:click:%d:%s:%s";

    // ==================== 创建 ====================

    @Override
    public Result<LinkVO> create(LinkCreateReqDTO req) {
        // 1. 生成短码 + 分布式锁防并发冲突
        String shortUri = null;
        for (int i = 0; i < MAX_RETRY; i++) {
            String candidate = shortLinkGenerator.generate(req.getOriginUrl());
            String lockKey = LOCK_PREFIX + candidate;
            RLock lock = redissonClient.getLock(lockKey);
            try {
                if (lock.tryLock(1, 5, TimeUnit.SECONDS)) {
                    try {
                        if (!shortUriExists(candidate)) {
                            shortUri = candidate;
                            break;
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.fail("系统繁忙，请重试");
            }
        }
        if (shortUri == null) {
            return Result.fail("短码生成失败，请重试");
        }

        // 2. 构建实体
        LinkEntity entity = new LinkEntity();
        entity.setShortUri(shortUri);
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

        linkMapper.insert(entity);

        // 3. 返回 VO
        LinkVO vo = toVO(entity);
        return Result.success(vo);
    }

    // ==================== 更新 ====================

    @Override
    public Result<Void> update(LinkUpdateReqDTO req) {
        LinkEntity exist = linkMapper.selectById(req.getId());
        if (exist == null) {
            return Result.fail(Result.NOT_FOUND, "短链接不存在");
        }
        // 校验所有权：只能修改自己的短链接
        if (!exist.getCreateUserId().equals(UserContext.getUserId())) {
            return Result.fail(Result.FORBIDDEN, "无权修改该短链接");
        }

        // 分布式锁：防止并发更新同一条链接导致数据覆盖
        String lockKey = UPDATE_LOCK_PREFIX + req.getId();
        RLock lock = redissonClient.getLock(lockKey);
        try {
            if (lock.tryLock(1, 5, TimeUnit.SECONDS)) {
                try {
                    doUpdate(req);
                } finally {
                    lock.unlock();
                }
            } else {
                return Result.fail("操作过于频繁，请稍后再试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.fail("系统繁忙，请重试");
        }
        return Result.success();
    }

    private void doUpdate(LinkUpdateReqDTO req) {
        // 仅更新非 null 字段
        LambdaUpdateWrapper<LinkEntity> wrapper = Wrappers.lambdaUpdate();
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

    // ==================== 删除（逻辑删除） ====================

    @Override
    public Result<Void> delete(Long id) {
        LinkEntity exist = linkMapper.selectById(id);
        if (exist == null) {
            return Result.fail(Result.NOT_FOUND, "短链接不存在");
        }
        // 校验所有权：只能删除自己的短链接
        if (!exist.getCreateUserId().equals(UserContext.getUserId())) {
            return Result.fail(Result.FORBIDDEN, "无权删除该短链接");
        }
        linkMapper.deleteById(id);
        return Result.success();
    }

    // ==================== 分页查询 ====================

    @Override
    public Result<IPage<LinkVO>> page(LinkPageReqDTO req) {
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        // 用户只能看到自己创建的短链接
        wrapper.eq(LinkEntity::getCreateUserId, UserContext.getUserId());
        wrapper.eq(StrUtil.isNotBlank(req.getGid()), LinkEntity::getGid, req.getGid());
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
        LinkEntity entity = linkMapper.selectById(id);
        return entity != null ? toVO(entity) : null;
    }

    // ==================== 私有方法 ====================

    private boolean shortUriExists(String shortUri) {
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(LinkEntity::getShortUri, shortUri);
        return linkMapper.selectCount(wrapper) > 0;
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
