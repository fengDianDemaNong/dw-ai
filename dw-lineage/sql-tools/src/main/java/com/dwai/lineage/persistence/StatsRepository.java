package com.dwai.lineage.persistence;

import com.dwai.lineage.dto.ProjectStats;
import com.dwai.lineage.dto.TenantStats;
import com.dwai.lineage.tenant.LineageContext;

/**
 * 概览页两个口径的统计。
 *
 * <p>独立于 {@link LineageCatalogRepository}：统计横跨 {@code lineage_*}、{@code meta_*}、
 * {@code data_catalog}、{@code temp_rule}、{@code sync_job}、{@code project}、
 * {@code metadata_source} 七类表，挂在其中任何一个仓储下都名不副实。
 *
 * <p>放在仓储层而不是服务层，是因为这些数字只能靠聚合查询拿：让服务层把表列出来再逐个数，
 * 就退化成 N+1 —— 而这正是这个接口存在的理由。
 */
public interface StatsRepository {

    /** 口径：{@code tenant_id + project_id}。 */
    ProjectStats projectStats(LineageContext ctx, StatsLimits limits);

    /**
     * 口径：{@code tenant_id}，<b>忽略 {@code ctx.projectId()}</b>。
     *
     * <p>实现必须是<b>固定查询数</b>，与项目数无关。「先查项目列表再对每个项目跑几条 count」
     * 在 20 个项目时就是上百条 SQL。
     */
    TenantStats tenantStats(LineageContext ctx, StatsLimits limits);
}
