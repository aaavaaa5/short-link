package com.app.service.Impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.app.dto.req.LoginReqDTO;
import com.app.dto.req.RegisterReqDTO;
import com.app.dto.resp.LoginRespDTO;
import com.app.entity.Result;
import com.app.entity.UserEntity;
import com.app.mapper.UserMapper;
import com.app.service.UserService;
import com.app.util.JwtUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 用户服务实现
 * <p>
 * 密码使用 BCrypt 加密存储。
 * 登录成功返回 JWT Token。
 */
@Service
public class UserServiceImpl implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private JwtUtil jwtUtil;

    // ==================== 注册 ====================

    @Override
    public Result<Void> register(RegisterReqDTO req) {
        // 1. 校验用户名唯一性
        UserEntity exist = getByUsername(req.getUsername());
        if (exist != null) {
            return Result.fail(Result.CONFLICT, "用户名已存在");
        }

        // 2. 构建用户实体
        UserEntity user = new UserEntity();
        user.setUsername(req.getUsername());
        user.setPassword(BCrypt.hashpw(req.getPassword(), BCrypt.gensalt()));
        user.setRealName(req.getRealName());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setEnableStatus(1); // 默认启用

        userMapper.insert(user);
        return Result.success();
    }

    // ==================== 登录 ====================

    @Override
    public Result<LoginRespDTO> login(LoginReqDTO req) {
        // 1. 查询用户
        UserEntity user = getByUsername(req.getUsername());
        if (user == null) {
            return Result.fail(Result.UNAUTHORIZED, "用户名或密码错误");
        }

        // 2. 检查账号状态
        if (user.getEnableStatus() == null || user.getEnableStatus() == 0) {
            return Result.fail(Result.FORBIDDEN, "账号已被停用");
        }

        // 3. 校验密码
        if (!BCrypt.checkpw(req.getPassword(), user.getPassword())) {
            return Result.fail(Result.UNAUTHORIZED, "用户名或密码错误");
        }

        // 4. 生成 Token
        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        LoginRespDTO resp = LoginRespDTO.builder()
                .token(token)
                .userId(user.getId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .build();

        return Result.success(resp);
    }

    // ==================== 查询 ====================

    @Override
    public UserEntity getByUsername(String username) {
        if (StrUtil.isBlank(username)) {
            return null;
        }
        LambdaQueryWrapper<UserEntity> wrapper = Wrappers.lambdaQuery();
        wrapper.eq(UserEntity::getUsername, username);
        return userMapper.selectOne(wrapper);
    }

    @Override
    public UserEntity getById(Long id) {
        if (id == null) {
            return null;
        }
        return userMapper.selectById(id);
    }
}
