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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 短链接服务实现
 * <p>
 * 创建短链接：原始 URL → 生成短码 → 校验唯一性 → 入库。
 * 分页查询：支持 gid / shortUri / originUrl / title / enableStatus 多条件组合筛选。
 */
@Service
public class LinkServiceImpl implements LinkService {

    @Autowired
    private LinkMapper linkMapper;

    @Autowired
    private ShortLinkGenerator shortLinkGenerator;

    /** 短链接域名前缀 */
    @Value("${shortlink.domain:http://s.local}")
    private String domain;

    private static final int MAX_RETRY = 3;

    // ==================== 创建 ====================

    @Override
    public Result<LinkVO> create(LinkCreateReqDTO req) {
        // 1. 生成短码（最多重试 3 次避免冲突）
        String shortUri = null;
        for (int i = 0; i < MAX_RETRY; i++) {
            String candidate = shortLinkGenerator.generate(req.getOriginUrl());
            if (!shortUriExists(candidate)) {
                shortUri = candidate;
                break;
            }
        }
        if (shortUri == null) {
            return Result.fail("短码生成失败，请重试");
        }

        // 2. 构建实体
        LinkEntity entity = new LinkEntity();
        entity.setShortUri(shortUri);
        entity.setOriginUrl(req.getOriginUrl());
        entity.setGid(req.getGid());
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

        // 仅更新非 null 字段
        LambdaUpdateWrapper<LinkEntity> wrapper = Wrappers.lambdaUpdate();
        wrapper.eq(LinkEntity::getId, req.getId());
        if (req.getOriginUrl() != null) wrapper.set(LinkEntity::getOriginUrl, req.getOriginUrl());
        if (req.getGid() != null) wrapper.set(LinkEntity::getGid, req.getGid());
        if (req.getTitle() != null) wrapper.set(LinkEntity::getTitle, req.getTitle());
        if (req.getDescribe() != null) wrapper.set(LinkEntity::getDescribe, req.getDescribe());
        if (req.getRedirectType() != null) wrapper.set(LinkEntity::getRedirectType, req.getRedirectType());
        if (req.getExpireType() != null) wrapper.set(LinkEntity::getExpireType, req.getExpireType());
        if (req.getExpireTime() != null) wrapper.set(LinkEntity::getExpireTime, req.getExpireTime());
        if (req.getEnableStatus() != null) wrapper.set(LinkEntity::getEnableStatus, req.getEnableStatus());

        linkMapper.update(null, wrapper);
        return Result.success();
    }

    // ==================== 删除（逻辑删除） ====================

    @Override
    public Result<Void> delete(Long id) {
        LinkEntity exist = linkMapper.selectById(id);
        if (exist == null) {
            return Result.fail(Result.NOT_FOUND, "短链接不存在");
        }
        linkMapper.deleteById(id);
        return Result.success();
    }

    // ==================== 分页查询 ====================

    @Override
    public Result<IPage<LinkVO>> page(LinkPageReqDTO req) {
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(StrUtil.isNotBlank(req.getGid()), LinkEntity::getGid, req.getGid());
        wrapper.like(StrUtil.isNotBlank(req.getShortUri()), LinkEntity::getShortUri, req.getShortUri());
        wrapper.like(StrUtil.isNotBlank(req.getOriginUrl()), LinkEntity::getOriginUrl, req.getOriginUrl());
        wrapper.like(StrUtil.isNotBlank(req.getTitle()), LinkEntity::getTitle, req.getTitle());
        wrapper.eq(req.getEnableStatus() != null, LinkEntity::getEnableStatus, req.getEnableStatus());
        wrapper.eq(req.getCreatedType() != null, LinkEntity::getCreatedType, req.getCreatedType());
        wrapper.orderByDesc(LinkEntity::getCreateTime);

        Page<LinkEntity> page = new Page<>(req.getPage(), req.getSize());
        IPage<LinkEntity> entityPage = linkMapper.selectPage(page, wrapper);

        // 转换 VO
        IPage<LinkVO> voPage = entityPage.convert(this::toVO);
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
}
