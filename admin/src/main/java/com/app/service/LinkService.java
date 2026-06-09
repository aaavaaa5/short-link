package com.app.service;

import com.app.dto.req.LinkCreateReqDTO;
import com.app.dto.req.LinkPageReqDTO;
import com.app.dto.req.LinkUpdateReqDTO;
import com.app.dto.resp.LinkVO;
import com.app.entity.Result;
import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 短链接服务
 */
public interface LinkService {

    /**
     * 创建短链接
     *
     * @param req 创建请求
     * @return 创建的短链接 VO
     */
    Result<LinkVO> create(LinkCreateReqDTO req);

    /**
     * 更新短链接（部分字段更新）
     *
     * @param req 更新请求
     * @return 操作结果
     */
    Result<Void> update(LinkUpdateReqDTO req);

    /**
     * 删除短链接（逻辑删除）
     *
     * @param id 短链接 ID
     * @return 操作结果
     */
    Result<Void> delete(Long id);

    /**
     * 分页查询短链接列表
     *
     * @param req 分页 + 筛选条件
     * @return 分页结果
     */
    Result<IPage<LinkVO>> page(LinkPageReqDTO req);

    /**
     * 根据 ID 查询短链接详情
     *
     * @param id 短链接 ID
     * @return 短链接 VO
     */
    LinkVO getById(Long id);
}
