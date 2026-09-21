package com.dwai.lineage.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 概览页「本租户」口径的统计：横跨该租户下的全部项目。
 *
 * <p>口径是 {@code tenant_id}，<b>忽略 {@code X-Project-Id}</b>。
 *
 * <h2>为什么它和 {@link ProjectStats} 是两个接口</h2>
 * 库表的隔离层级决定了口径边界：{@code project} 与 {@code metadata_source} 只有
 * {@code tenant_id}，血缘与元数据两侧则都带 {@code project_id}。所以
 * <b>元数据服务的统计只能出现在这里</b> —— 它是租户级配置，同一租户下所有项目共用一份，
 * 放进项目接口是口径错误。
 *
 * <h2>{@link #overview}、{@link #scale} 与 {@link #activity} 都是算出来的</h2>
 * 它们全部由 {@link #projects} 在 Java 侧求和 / 计数得出，<b>0 次额外查询</b>。
 * 这不只是省事：汇总数和明细表出自同一份数据，「上面写 512、下面加起来 480」
 * 这种最招人怀疑的不一致在结构上就不可能发生。
 *
 * <p>空值语义与 {@link ProjectStats} 一致：数值恒有值、数组恒非 null，
 * 允许为 null 的只有 {@code lastParseAt}。
 */
public record TenantStats(Overview overview,
                          Scale scale,
                          Sources sources,
                          Activity activity,
                          List<ProjectSummary> projects) {

    /**
     * @param emptyProjects 一条血缘表都没有的项目数。这张页面最该暴露的东西之一 ——
     *                      建了没用的项目会一直占着切换列表
     */
    public record Overview(int projects, int enabledProjects, int disabledProjects, int emptyProjects) {
    }

    /** 全租户汇总，由 {@link ProjectSummary} 求和得出。没有 dataCatalogs / tempRules —— 那两项只在项目页看。 */
    public record Scale(int lineageTables,
                        int tempTables,
                        int lineageColumns,
                        int currentEdges,
                        int versions,
                        int metaTables,
                        int metaColumns) {
    }

    /** 元数据服务配置。{@code metadata_source} 是租户级表，只能出现在这里。 */
    public record Sources(int total, int enabled, List<SourceTypeCount> byType) {
    }

    /**
     * 元数据服务按类型的分布。
     *
     * <p>比 {@link StatCount} 多一个 {@code enabled}：「配了 2 个 Gravitino，其中 0 个启用」
     * 与「配了 2 个都在用」是完全不同的两件事，只给总数看不出来。
     */
    public record SourceTypeCount(String name, int count, int enabled) {
    }

    /**
     * @param parses7d  趋势末 7 桶之和
     * @param parses30d 各项目近 30 天解析数之和
     * @param trend     恒为 TREND_DAYS 条，按日期升序，补零
     */
    public record Activity(int parses7d, int parses30d, LocalDateTime lastParseAt, List<DayCount> trend) {
    }

    /**
     * 租户下一个项目的横向对比行。
     *
     * <p><b>空项目也必须在列</b>，各项为 0 —— 这张表的价值有一半在「谁是空的」上，
     * 所以查询必须以项目列表为驱动做左连接，而不是以血缘表为驱动
     * （那样一条血缘都没有的项目会直接从表里消失）。
     *
     * @param enabled     由 {@code status} 映射，与 {@link ProjectResponse} 一致
     * @param lastParseAt 从未解析过时为 null
     */
    public record ProjectSummary(long projectId,
                                 String code,
                                 String name,
                                 boolean enabled,
                                 int lineageTables,
                                 int tempTables,
                                 int lineageColumns,
                                 int currentEdges,
                                 int versions,
                                 int metaTables,
                                 int metaColumns,
                                 int parses30d,
                                 LocalDateTime lastParseAt) {
    }
}
