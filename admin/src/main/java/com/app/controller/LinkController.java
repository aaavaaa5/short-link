package com.app.controller;

import com.app.dto.req.LinkCreateReqDTO;
import com.app.dto.req.LinkPageReqDTO;
import com.app.dto.req.LinkUpdateReqDTO;
import com.app.dto.resp.LinkVO;
import com.app.entity.Result;
import com.app.service.LinkService;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 短链接控制器 — CRUD
 */
@Tag(name = "短链接管理", description = "短链接的创建、修改、删除、分页查询接口")
@RestController
@RequestMapping("/api/link")
public class LinkController {

    private static final Logger log = LoggerFactory.getLogger(LinkController.class);

    @Autowired
    private LinkService linkService;

    @Operation(summary = "创建短链接")
    @PostMapping
    @SentinelResource(value = "create_short-link",
            blockHandler = "createBlockHandler",
            fallback = "createFallback")
    public Result<LinkVO> create(@Valid @RequestBody LinkCreateReqDTO req) {
        return linkService.create(req);
    }

    /**
     * Sentinel 限流/降级触发时的 blockHandler（BlockException）
     * <p>
     * 与原始方法签名一致 + 额外 BlockException 参数，必须为 public。
     */
    public Result<LinkVO> createBlockHandler(LinkCreateReqDTO req, BlockException ex) {
        log.warn("创建短链接被限流: {}", ex.getMessage());
        return Result.fail(429, "创建短链接请求过于频繁，请稍后再试（QPS 限制：1次/秒）");
    }

    /**
     * Sentinel 熔断/异常触发时的 fallback
     */
    public Result<LinkVO> createFallback(LinkCreateReqDTO req, Throwable ex) {
        log.error("创建短链接服务异常: {}", ex.getMessage(), ex);
        return Result.fail(500, "创建短链接服务暂时不可用，请稍后再试");
    }

    @Operation(summary = "更新短链接")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody LinkUpdateReqDTO req) {
        return linkService.update(req);
    }

    @Operation(summary = "删除短链接（逻辑删除）")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "短链接 ID") @PathVariable Long id) {
        return linkService.delete(id);
    }

    @Operation(summary = "分页查询短链接")
    @GetMapping("/page")
    public Result<IPage<LinkVO>> page(@Valid LinkPageReqDTO req) {
        return linkService.page(req);
    }

    @Operation(summary = "查询短链接详情")
    @GetMapping("/{id}")
    public Result<LinkVO> getById(@Parameter(description = "短链接 ID") @PathVariable Long id) {
        LinkVO vo = linkService.getById(id);
        return vo != null ? Result.success(vo) : Result.fail(Result.NOT_FOUND, "短链接不存在");
    }
}
