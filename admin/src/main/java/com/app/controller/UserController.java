package com.app.controller;

import com.app.entity.Result;
import com.app.entity.UserEntity;
import com.app.service.UserService;
import com.app.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户控制器 — 当前用户信息
 */
@Tag(name = "用户管理", description = "当前登录用户信息接口")
@RestController
@RequestMapping("/api/user")
public class UserController {

    @Autowired
    private UserService userService;

    @Operation(summary = "获取当前登录用户信息")
    @GetMapping("/me")
    public Result<UserEntity> currentUser() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail(Result.UNAUTHORIZED, "未登录");
        }
        UserEntity user = userService.getById(userId);
        if (user == null) {
            return Result.fail(Result.NOT_FOUND, "用户不存在");
        }
        // 脱敏：不返回密码
        user.setPassword(null);
        return Result.success(user);
    }
}
