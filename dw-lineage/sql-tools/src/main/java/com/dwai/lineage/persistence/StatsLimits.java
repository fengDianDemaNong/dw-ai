package com.dwai.lineage.persistence;

/**
 * 概览统计的窗口期与条数。
 *
 * <p><b>接口不接受任何查询参数</b>，这些全是服务端常量：一旦开放成 query 参数，
 * 「近 N 天」就成了缓存键的一部分，而且前端各处传的 N 不一致时，
 * 同一个页面上两块数字的口径会悄悄对不上。前端以同名常量镜像本类的取值
 * （见 {@code src/services/api.ts} 里标注「与后端 XXX 对齐」的那几个）。
 *
 * <p>做成记录而不是一堆 {@code static final int}，是为了让测试能构造别的取值 ——
 * 比如用 3 天的窗口验补零，而不必造 30 天的数据。
 */
public record StatsLimits(int trendDays,
                          int recentParseLimit,
                          int listLimit,
                          int topLimit,
                          int distLimit) {

    /** 趋势窗口，同时也是所有「近 30 天」计数的窗口。 */
    public static final int TREND_DAYS = 30;

    /** 「最近解析」的条数。够看出最近在做什么就行，不是审计日志。 */
    public static final int RECENT_PARSE_LIMIT = 10;

    /** 「元数据独有」/「孤立表」两张清单各自的条数。超出部分让用户到对应页面去筛。 */
    public static final int OVERVIEW_LIST_LIMIT = 20;

    /** 枢纽表、最近失败任务各取前几条。 */
    public static final int TOP_LIMIT = 5;

    /** 每组分布取前几项。 */
    public static final int DIST_LIMIT = 8;

    public static final StatsLimits DEFAULTS = new StatsLimits(
            TREND_DAYS, RECENT_PARSE_LIMIT, OVERVIEW_LIST_LIMIT, TOP_LIMIT, DIST_LIMIT);
}
