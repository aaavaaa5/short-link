package com.app.controller;

import com.app.dto.req.GroupCreateReqDTO;
import com.app.dto.resp.GroupVO;
import com.app.entity.Result;
import com.app.service.GroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 分组控制器 — CRUD
 */
@Tag(name = "分组管理", description = "短链接分组的创建、修改、删除、列表接口")
@RestController
@RequestMapping("/api/group")
public class GroupController {

    @Autowired
    private GroupService groupService;

    @Operation(summary = "创建分组")
    @PostMapping
    public Result<Void> create(@Valid @RequestBody GroupCreateReqDTO req) {
        return groupService.create(req);
    }

    @Operation(summary = "更新分组")
    @PutMapping
    public Result<Void> update(@Valid @RequestBody GroupCreateReqDTO req) {
        return groupService.update(req);
    }

    @Operation(summary = "删除分组")
    @DeleteMapping("/{id}")
    public Result<Void> delete(@Parameter(description = "分组 ID") @PathVariable Long id) {
        return groupService.delete(id);
    }

    @Operation(summary = "查询当前用户的所有分组")
    @GetMapping("/list")
    public Result<List<GroupVO>> list() {
        return groupService.list();
    }
}
