package com.app.service;

import com.app.dto.req.GroupCreateReqDTO;
import com.app.dto.resp.GroupVO;
import com.app.entity.Result;

import java.util.List;

/**
 * 分组服务
 */
public interface GroupService {

    /**
     * 创建分组
     *
     * @param req 创建请求
     * @return 操作结果
     */
    Result<Void> create(GroupCreateReqDTO req);

    /**
     * 更新分组
     *
     * @param req 更新请求（需传 id）
     * @return 操作结果
     */
    Result<Void> update(GroupCreateReqDTO req);

    /**
     * 删除分组（逻辑删除，分组下存在短链接时不允许删除）
     *
     * @param id 分组 ID
     * @return 操作结果
     */
    Result<Void> delete(Long id);

    /**
     * 查询当前用户的所有分组
     *
     * @return 分组列表（含短链接数量）
     */
    Result<List<GroupVO>> list();
}
