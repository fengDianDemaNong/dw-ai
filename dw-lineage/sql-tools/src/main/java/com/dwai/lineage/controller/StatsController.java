package com.dwai.lineage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.dwai.lineage.dto.ProjectStats;
import com.dwai.lineage.dto.TenantStats;
import com.dwai.lineage.service.StatsService;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 概览页的统计。
 *
 * <p>两个口径两个接口，边界由库表的隔离层级决定：{@code project} 与 {@code metadata_source}
 * 只有 {@code tenant_id}，血缘与元数据两侧则都带 {@code project_id}。所以元数据服务的统计
 * 只能出现在租户接口里 —— 它是租户级配置，同一租户下所有项目共用一份。
 *
 * <p>两个接口都<b>不接受任何查询参数</b>：上下文来自 {@code X-Tenant-Id} / {@code X-Project-Id}
 * 请求头（见 {@code TenantInterceptor}），窗口期与条数是服务端常量（见 {@code StatsLimits}）。
 *
 * <p>完整契约见前端仓库的 {@code docs/stats-api.md}，那份文档里的示例 JSON 就是这两个
 * 接口的定义 —— 字段名、层级、空值语义任何一处不一致，页面上都会显示 NaN 或直接报错。
 */
@Tag(name = "统计概览")
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    /** 当前项目口径。 */
    @Operation(
            summary = "当前项目统计",
            description = """
                    按请求头 X-Tenant-Id + X-Project-Id 统计当前项目的血缘/元数据规模与近期解析。
                    不接受查询参数，窗口由服务端常量决定。
                    要看整个租户（含元数据服务条数）请用 GET /api/stats/tenant。
                    只要血缘目录几个汇总数字也可用 GET /api/catalog/stats。
                    """)
    @GetMapping("/project")
    public ProjectStats project() {
        return statsService.projectStats(TenantContextHolder.require());
    }

    /** 本租户口径，忽略 {@code X-Project-Id}。 */
    @Operation(
            summary = "当前租户统计",
            description = """
                    只按 X-Tenant-Id（忽略 X-Project-Id）汇总本租户下所有项目，以及租户级的元数据服务数量。
                    不要用本接口当项目看板。
                    """)
    @GetMapping("/tenant")
    public TenantStats tenant() {
        return statsService.tenantStats(TenantContextHolder.require());
    }
}
