package com.app.service.Impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.app.dto.req.GroupCreateReqDTO;
import com.app.dto.resp.GroupVO;
import com.app.entity.GroupEntity;
import com.app.entity.LinkEntity;
import com.app.entity.Result;
import com.app.mapper.GroupMapper;
import com.app.mapper.LinkMapper;
import com.app.service.GroupService;
import com.app.util.UserContext;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 分组服务实现
 * <p>
 * gid 生成策略：使用 UUID 前 12 位作为全局唯一分组标识。
 * 删除分组时检查分组下是否存在短链接。
 */
@Service
public class GroupServiceImpl implements GroupService {

    @Autowired
    private GroupMapper groupMapper;

    @Autowired
    private LinkMapper linkMapper;

    // ==================== 创建 ====================

    @Override
    public Result<Void> create(GroupCreateReqDTO req) {
        GroupEntity entity = new GroupEntity();
        entity.setGid(generateGid());
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());
        entity.setUserId(UserContext.getUserId());
        entity.setSortOrder(req.getSortOrder() != null ? req.getSortOrder() : 0);

        groupMapper.insert(entity);
        return Result.success();
    }

    // ==================== 更新 ====================

    @Override
    public Result<Void> update(GroupCreateReqDTO req) {
        if (req.getId() == null) {
            return Result.fail(Result.BAD_REQUEST, "分组 ID 不能为空");
        }
        GroupEntity exist = groupMapper.selectById(req.getId());
        if (exist == null) {
            return Result.fail(Result.NOT_FOUND, "分组不存在");
        }
        if (StrUtil.isNotBlank(req.getName())) {
            exist.setName(req.getName());
        }
        if (req.getDescription() != null) {
            exist.setDescription(req.getDescription());
        }
        if (req.getSortOrder() != null) {
            exist.setSortOrder(req.getSortOrder());
        }
        groupMapper.updateById(exist);
        return Result.success();
    }

    // ==================== 删除 ====================

    @Override
    public Result<Void> delete(Long id) {
        GroupEntity exist = groupMapper.selectById(id);
        if (exist == null) {
            return Result.fail(Result.NOT_FOUND, "分组不存在");
        }

        // 检查分组下是否存在短链接
        LambdaQueryWrapper<LinkEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(LinkEntity::getGid, exist.getGid());
        long count = linkMapper.selectCount(wrapper);
        if (count > 0) {
            return Result.fail(Result.CONFLICT, "分组下存在 " + count + " 条短链接，请先删除或移出");
        }

        groupMapper.deleteById(id);
        return Result.success();
    }

    // ==================== 列表 ====================

    @Override
    public Result<List<GroupVO>> list() {
        LambdaQueryWrapper<GroupEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(GroupEntity::getUserId, UserContext.getUserId());
        wrapper.orderByAsc(GroupEntity::getSortOrder);

        List<GroupEntity> entities = groupMapper.selectList(wrapper);
        List<GroupVO> vos = new ArrayList<>();
        for (GroupEntity entity : entities) {
            GroupVO vo = new GroupVO();
            BeanUtil.copyProperties(entity, vo);

            // 统计分组下的短链接数量
            LambdaQueryWrapper<LinkEntity> linkWrapper = Wrappers.lambdaQuery();
            linkWrapper.eq(LinkEntity::getGid, entity.getGid());
            vo.setLinkCount(linkMapper.selectCount(linkWrapper).intValue());

            vos.add(vo);
        }
        return Result.success(vos);
    }

    // ==================== 私有方法 ====================

    /** 生成 12 位全局唯一分组标识 */
    private String generateGid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
