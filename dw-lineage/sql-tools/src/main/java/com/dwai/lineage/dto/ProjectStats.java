package com.dwai.lineage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 概览页「当前项目」口径的统计，一次请求给全。
 *
 * <p>口径是 {@code tenant_id + project_id}。租户级的东西（项目列表、元数据服务配置）
 * 一概不在这里 —— 见 {@link TenantStats}。
 *
 * <h2>两条硬约定</h2>
 * <ol>
 *   <li><b>数值恒有值、数组恒非 null。</b> 空项目返回的是一堆 0 和一堆 {@code []}，
 *       不是 null、也不是缺字段。允许为 null 的只有
 *       {@code lastParseAt}、{@code comment}、{@code message}、{@code finishedAt}
 *       和分布项的 {@code name}，前端对这几个各有兜底。</li>
 *   <li><b>比率一律给「分子 + 分母」两个整数，服务端不算百分比。</b>
 *       分母为 0 时 {@code 0/0} 在服务端只能瞎编（0% 和 100% 都是错的），
 *       而渲染侧能干净地显示成「—」；提示里还要显示 {@code 41 / 116} 这样的原始数。</li>
 * </ol>
 *
 * <p>完整契约见前端仓库的 {@code docs/stats-api.md}。
 */
public record ProjectStats(Scale scale,
                           Quality quality,
                           Activity activity,
                           Distributions distributions,
                           Hubs hubs,
                           Sync sync,
                           Lists lists) {

    /**
     * 规模：各张表数了多少行。
     *
     * @param lineageTables 血缘表总数，<b>含临时表</b>
     * @param currentEdges  <b>只算当前版本</b>的字段级边。历史版本的边还躺在库里，
     *                      混进来这个数会比任何一张血缘图上能数出来的都大
     * @param tempRules     临时表规则数，<b>含已停用的</b>
     */
    public record Scale(int lineageTables,
                        int tempTables,
                        int lineageColumns,
                        int currentEdges,
                        int versions,
                        int metaTables,
                        int metaColumns,
                        int dataCatalogs,
                        int tempRules) {
    }

    /**
     * 覆盖与质量。每条比率的分子分母都在这里成对出现。
     *
     * <p>分母字段与 {@link Scale} 有重复是<b>刻意的</b>：前端不跨块取分母，
     * 免得哪天 scale 的口径变了把比率也一起带歪。
     *
     * <h2>为什么覆盖率要给两个数</h2>
     * 血缘侧的全名按<b>默认数据目录</b>补全成 {@code default.ods.orders}，
     * 元数据却挂在<b>源端 catalog</b> 下（{@code hive_prod.ods.orders}）—— 首段永远对不上。
     * 只给「按全名」这一个数，用户会看到 35% 并以为元数据没导入，跑去重导一遍，还是 35%。
     * 两个数并排，差距本身就是诊断结论：
     * <ul>
     *   <li>按全名 35% / 按库表 94% → 目录前缀没对齐，该去改数据目录，不是去补元数据</li>
     *   <li>两个都是 35% → 元数据是真的缺</li>
     * </ul>
     *
     * @param lineageTablesNonTemp     两条覆盖率的分母：非临时的血缘表
     * @param metaMatchedByFullName    按<b>三段全名</b>能在元数据里找到对应行的血缘表数
     * @param metaMatchedBySchemaTable 同上，但只比 {@code schema_name + table_name}，
     *                                 忽略首段的数据目录。恒 &gt;= 上面那个
     * @param metaInLineage            反向：元数据表里能在血缘中找到同名表的张数
     * @param isolatedTables           计数；清单在 {@link Lists} 里
     * @param metaOnlyTables           同上
     */
    public record Quality(int lineageTablesNonTemp,
                          int metaMatchedByFullName,
                          int metaMatchedBySchemaTable,
                          int metaTables,
                          int metaInLineage,
                          int lineageTables,
                          int lineageTableCommentFilled,
                          int metaTableCommentFilled,
                          int metaColumns,
                          int metaColumnCommentFilled,
                          int isolatedTables,
                          int metaOnlyTables) {
    }

    /**
     * 活跃度。
     *
     * @param lastParseAt 从未解析过时为 null
     * @param trend       <b>恒为 TREND_DAYS 条</b>，按日期升序（旧 → 新），当天无解析则计数为 0
     */
    public record Activity(int parses7d,
                           int parses30d,
                           LocalDateTime lastParseAt,
                           List<DayCount> trend,
                           List<RecentParse> recentParses) {
    }

    /**
     * 一次解析留下的版本。字段与被替代的 {@code CatalogStats.RecentParse} 完全一致。
     *
     * @param tableId   目标表 id，前端据此跳详情
     * @param versionNo 该表内递增的版本号
     * @param current   是否为该表当前生效的版本
     */
    public record RecentParse(long versionId,
                              long tableId,
                              String fullName,
                              int versionNo,
                              String dbType,
                              boolean current,
                              int statTables,
                              int statColumns,
                              int statEdges,
                              LocalDateTime createdAt) {
    }

    /**
     * 各维度分布，每组取前 {@code DIST_LIMIT} 项、按计数倒序。
     *
     * @param byDbType 按 {@code lineage_version.db_type} 分组。<b>用 version 不用 table</b> ——
     *                 {@code lineage_table.db_type} 可为 null，且「用什么方言解析的」
     *                 才是有意义的问题
     */
    public record Distributions(List<StatCount> byDbType,
                                List<StatCount> byCatalog,
                                List<StatCount> byMetaSource,
                                List<StatCount> bySchema) {
    }

    public record Hubs(List<HubTable> topDownstream, List<HubTable> topUpstream) {
    }

    /**
     * 枢纽表。
     *
     * @param degree 连到了多少张<b>不同的上/下游表</b>。<b>单位是表，不是边</b> ——
     *               边数会被宽表放大，一张 200 列的表随便就上千条边，
     *               按边排出来永远是那几张宽表
     */
    public record HubTable(long tableId, String fullName, int degree) {
    }

    /**
     * 元数据导入任务。
     *
     * @param byStatus <b>只列出现过的状态</b>，前端自己补齐要展示的那几种 ——
     *                 「失败 0」本身就是要看的信息，但补哪几种是展示层的事
     */
    public record Sync(int days, List<StatCount> byStatus, List<SyncFailure> recentFailures) {
    }

    /**
     * 一条失败（或部分失败）的导入任务。
     *
     * @param target     拼好的展示串（{@code hive_prod.ods} 这样），前端不再拼 ——
     *                   源目录/库/表三个字段哪个有值取决于 {@code scope}，
     *                   这个分支判断放在展示层就得跟着 {@code SyncScope} 一起改
     * @param message    可为 null
     * @param finishedAt 可为 null（还没结束）
     */
    public record SyncFailure(long jobId,
                              String status,
                              String scope,
                              String target,
                              int failedCnt,
                              String message,
                              LocalDateTime finishedAt) {
    }

    /** 两张短清单，各 ≤ {@code OVERVIEW_LIST_LIMIT} 条。前端据「取满了」显示截断提示。 */
    public record Lists(List<TableBrief> metaOnlyTables, List<TableBrief> isolatedTables) {
    }

    /**
     * 清单里只需要认出是哪张表并能点进去。
     *
     * @param id 血缘表 id。{@code metaOnlyTables} 里的表在血缘侧不存在，故为 null；
     *           <b>此时整个字段不序列化</b>（前端类型里它是可选的），
     *           而不是给一个 0 那样的假 id 骗它去跳一个不存在的详情页
     */
    public record TableBrief(@JsonInclude(JsonInclude.Include.NON_NULL) Long id,
                             String fullName,
                             String comment) {
    }
}
