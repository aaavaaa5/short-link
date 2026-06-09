package com.app.controller;

import com.app.dto.req.LoginReqDTO;
import com.app.dto.req.RegisterReqDTO;
import com.app.dto.resp.LoginRespDTO;
import com.app.entity.Result;
import com.app.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器 — 注册 / 登录
 */
@Tag(name = "认证管理", description = "用户注册与登录接口")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private UserService userService;

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterReqDTO req) {
        return userService.register(req);
    }

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<LoginRespDTO> login(@Valid @RequestBody LoginReqDTO req) {
        return userService.login(req);
    }
}
