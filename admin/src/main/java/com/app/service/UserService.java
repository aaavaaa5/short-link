package com.app.service;

import com.app.dto.req.LoginReqDTO;
import com.app.dto.req.RegisterReqDTO;
import com.app.dto.resp.LoginRespDTO;
import com.app.entity.Result;
import com.app.entity.UserEntity;

/**
 * 用户服务
 */
public interface UserService {

    /**
     * 用户注册
     *
     * @param req 注册请求
     * @return 注册结果
     */
    Result<Void> register(RegisterReqDTO req);

    /**
     * 用户登录
     *
     * @param req 登录请求
     * @return 登录响应（含 JWT Token）
     */
    Result<LoginRespDTO> login(LoginReqDTO req);

    /**
     * 根据用户名查询用户
     *
     * @param username 用户名
     * @return 用户实体，不存在返回 null
     */
    UserEntity getByUsername(String username);

    /**
     * 根据 ID 查询用户
     *
     * @param id 用户 ID
     * @return 用户实体
     */
    UserEntity getById(Long id);
}
