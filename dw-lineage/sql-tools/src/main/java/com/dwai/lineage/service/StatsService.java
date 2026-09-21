package com.dwai.lineage.service;

import com.dwai.lineage.dto.ProjectStats;
import com.dwai.lineage.dto.TenantStats;
import com.dwai.lineage.tenant.LineageContext;

/**
 * 概览页的统计，分项目级与租户级两个口径。
 *
 * <p>拆成两个方法而不是一个大对象，是因为两边的成本差着量级：
 * 租户级要跑一整套 {@code group by project_id} 的聚合，而绝大多数人进页面只看当前项目。
 * 前端两个标签页各自懒加载，第二个标签页不点开就不会有这次查询。
 */
public interface StatsService {

    /** 当前项目口径：{@code tenant_id + project_id}。 */
    ProjectStats projectStats(LineageContext ctx);

    /** 本租户口径：{@code tenant_id}，忽略上下文里的项目。 */
    TenantStats tenantStats(LineageContext ctx);
}
