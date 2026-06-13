package com.app.controller;

import com.app.dto.resp.StatsVO;
import com.app.entity.Result;
import com.app.service.StatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 统计控制器 — 访问数据查询
 */
@Tag(name = "统计分析", description = "短链接访问统计与图表数据接口")
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private StatsService statsService;

    @Operation(summary = "查询单个短链接统计")
    @GetMapping("/link/{linkId}")
    public Result<StatsVO> getLinkStats(
            @Parameter(description = "短链接 ID") @PathVariable Long linkId,
            @Parameter(description = "时间范围：today / 7d / 30d") @RequestParam(defaultValue = "today") String range) {
        return statsService.getLinkStats(linkId, range);
    }

    @Operation(summary = "查询总量统计")
    @GetMapping("/overall")
    public Result<StatsVO> getOverallStats() {
        return statsService.getOverallStats();
    }

    @Operation(summary = "查询当前用户趋势数据（按日期聚合）")
    @GetMapping("/trend")
    public Result<StatsVO> getTrend(
            @Parameter(description = "时间范围：7d / 30d") @RequestParam(defaultValue = "7d") String range) {
        return statsService.getTrend(range);
    }

    @Operation(summary = "查询仪表盘地区/浏览器/设备分布（当前用户所有短链接汇总）")
    @GetMapping("/dashboard/distributions")
    public Result<StatsVO> getDashboardDistributions() {
        return statsService.getDashboardDistributions();
    }

    @Operation(summary = "查询单个短链接的地区/浏览器分布")
    @GetMapping("/link/{linkId}/distributions")
    public Result<StatsVO> getLinkDistributions(
            @Parameter(description = "短链接 ID") @PathVariable Long linkId) {
        return statsService.getLinkStats(linkId, "today");
    }
}
