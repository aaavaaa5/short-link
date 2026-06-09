package com.app.controller;

import com.app.entity.LinkEntity;
import com.app.service.RedirectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * 短链接跳转控制器
 * <p>
 * 浏览器访问短链接 → 状态校验 → 302 重定向到原始 URL。
 * 校验不通过时展示 Thymeleaf 错误页面。
 */
@Tag(name = "短链接跳转", description = "短码解析与 302 重定向接口")
@Controller
public class RedirectController {

    @Autowired
    private RedirectService redirectService;

    @Operation(summary = "短链接跳转", description = "根据短码查询原始 URL 并执行重定向")
    @ApiResponses({
            @ApiResponse(responseCode = "302", description = "重定向到原始 URL"),
            @ApiResponse(responseCode = "301", description = "永久重定向到原始 URL"),
            @ApiResponse(responseCode = "404", description = "短链接不存在"),
            @ApiResponse(responseCode = "403", description = "短链接已停用"),
            @ApiResponse(responseCode = "410", description = "短链接已删除或已过期")
    })
    @GetMapping("/{shortUri}")
    public String redirect(
            @Parameter(description = "短码", example = "abc123")
            @PathVariable String shortUri,
            HttpServletRequest request,
            HttpServletResponse response,
            Model model) throws IOException {

        LinkEntity entity = redirectService.getLinkEntity(shortUri);

        // 1. 短码不存在 → 404 页面
        if (entity == null) {
            model.addAttribute("message", "短链接不存在");
            response.setStatus(404);
            return "error/404";
        }

        // 2. 已删除 → 410 页面
        if (entity.getDelFlag() != null && entity.getDelFlag() == 1) {
            model.addAttribute("message", "短链接已被删除");
            response.setStatus(410);
            return "error/410";
        }

        // 3. 已停用 → 403 页面
        if (entity.getEnableStatus() != null && entity.getEnableStatus() == 0) {
            model.addAttribute("message", "短链接已被停用");
            response.setStatus(403);
            return "error/403";
        }

        // 4. 已过期 → 410 页面
        if (entity.getExpireType() != null && entity.getExpireType() == 1
                && entity.getExpireTime() != null
                && entity.getExpireTime().isBefore(LocalDateTime.now())) {
            model.addAttribute("message", "短链接已过期");
            response.setStatus(410);
            return "error/410";
        }

        // 5. 全部通过 → 异步记录访问 → 重定向
        redirectService.recordAccess(entity, request);

        if (entity.getRedirectType() != null && entity.getRedirectType() == 1) {
            // 301 永久重定向
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", entity.getOriginUrl());
            return null; // 响应已手动提交
        }

        // 302 临时重定向（默认），Spring "redirect:" 前缀自动处理
        return "redirect:" + entity.getOriginUrl();
    }
}
