package com.dwai.lineage.service.impl;

import com.dwai.lineage.dto.ProjectStats;
import com.dwai.lineage.dto.TenantStats;
import com.dwai.lineage.persistence.StatsLimits;
import com.dwai.lineage.persistence.StatsRepository;
import com.dwai.lineage.service.StatsService;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.stereotype.Service;

/**
 * 概览统计。
 *
 * <p>这一层薄得只剩「用哪套窗口期与条数」这一个决定 —— 数字全在仓储的聚合 SQL 里，
 * 拿到服务层再算就成了 N+1。窗口与条数不从请求参数来，见 {@link StatsLimits} 的说明。
 */
@Service
public class StatsServiceImpl implements StatsService {

    private final StatsRepository stats;

    public StatsServiceImpl(StatsRepository stats) {
        this.stats = stats;
    }

    @Override
    public ProjectStats projectStats(LineageContext ctx) {
        return stats.projectStats(ctx, StatsLimits.DEFAULTS);
    }

    @Override
    public TenantStats tenantStats(LineageContext ctx) {
        return stats.tenantStats(ctx, StatsLimits.DEFAULTS);
    }
}
