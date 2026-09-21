package com.dwai.lineage.persistence;

import com.dwai.lineage.dto.DayCount;
import com.dwai.lineage.dto.ProjectStats;
import com.dwai.lineage.dto.StatCount;
import com.dwai.lineage.dto.TenantStats;
import com.dwai.lineage.enums.SyncStatus;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * 概览统计的 JdbcTemplate 实现。
 *
 * <h2>三条贯穿全文件的写法约束</h2>
 * <ol>
 *   <li><b>SQL 里不出现任何方言日期函数。</b> 同一份 SQL 要同时跑 H2、MySQL 8 与 PostgreSQL，
 *       而 {@code DATE_SUB} / {@code INTERVAL} / {@code DATEADD} / {@code date_trunc} / {@code DATE()}
 *       没有一个是三家通用的。窗口起点在 Java 算好绑成 {@code ?}；
 *       按天分桶把原始时间戳取回 Java 做。条件聚合一律
 *       {@code case when … then 1 else 0 end} —— 这是三方言唯一通用的写法。</li>
 *   <li><b>每条 SQL 自己带 {@code tenant_id}。</b> {@code TenantIsolationArchTest} 以
 *       Java 语句为单位聚合字符串字面量做静态检查，所以 where 子句不能拆到调用方去拼 ——
 *       拆了之后单条语句里就看不见租户过滤了。需要复用同一条 SQL 时（计数 + 清单），
 *       用「返回 SQL 字符串的私有方法」而不是「常量片段 + 调用方拼接」，
 *       前者整条 SQL 仍在一个语句里。</li>
 *   <li><b>{@code comment} 一律带别名限定</b>（{@code lt.comment} / {@code m.comment}）——
 *       裸写在部分方言下是保留字。判空用 {@code is not null and <> ''}，不用 {@code trim()}：
 *       收益不抵可移植性风险。</li>
 * </ol>
 *
 * <h2>血缘侧与元数据侧关联时的大小写</h2>
 * {@code lineage_table.full_name} 保留 SQL 的原始大小写，{@code meta_table.full_name}
 * 入库时恒为小写。两侧关联必须写成 {@code m.full_name = lower(lt.full_name)} ——
 * {@code lower()} <b>只加在血缘侧</b>：给已经是小写的元数据侧再套一层会让索引失效，
 * 两边都不加则大小写不同的同一张表会被当成两张，覆盖率直接掉到 0。
 */
@Repository
public class JdbcStatsRepository implements StatsRepository {

    private final JdbcTemplate jdbc;

    public JdbcStatsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================================================================
    // 项目口径
    // ==================================================================

    @Override
    public ProjectStats projectStats(LineageContext ctx, StatsLimits limits) {
        long t = ctx.tenantId();
        long p = ctx.projectId();

        LocalDate trendFrom = trendFrom(limits);
        Timestamp cutoff = Timestamp.valueOf(trendFrom.atStartOfDay());

        TableCounts lineageTables = lineageTableCounts(t, p);
        CommentCounts metaTables = metaTableCounts(t, p);
        CommentCounts metaColumns = metaColumnCounts(t, p);
        VersionCounts versions = versionCounts(t, p);

        int lineageColumns = count(
                "select count(*) from lineage_column lc "
                        + "where lc.tenant_id = ? and lc.project_id = ?", t, p);

        // 只数当前版本的边。历史版本的边还躺在库里，混进来这个数会比任何一张
        // 血缘图上能数出来的都大
        int currentEdges = count(
                "select count(*) from lineage_edge e "
                        + "join lineage_version v on v.id = e.version_id "
                        + "where e.tenant_id = ? and e.project_id = ? and v.is_current = 1", t, p);

        int dataCatalogs = count(
                "select count(*) from data_catalog dc "
                        + "where dc.tenant_id = ? and dc.project_id = ?", t, p);

        // 含 enabled = 0 的规则：停用的规则也是「配过的东西」，
        // 页面上那个数字回答的是「这个项目配了多少条」
        int tempRules = count(
                "select count(*) from temp_rule tr "
                        + "where tr.tenant_id = ? and tr.project_id = ?", t, p);

        List<DayCount> trend = trend(
                jdbc.query("select v.created_at from lineage_version v "
                                + "where v.tenant_id = ? and v.project_id = ? and v.created_at >= ?",
                        TIMESTAMP_MAPPER, t, p, cutoff),
                trendFrom, limits.trendDays());

        ProjectStats.Scale scale = new ProjectStats.Scale(
                lineageTables.total(), lineageTables.temps(), lineageColumns, currentEdges,
                versions.total(), metaTables.total(), metaColumns.total(), dataCatalogs, tempRules);

        ProjectStats.Quality quality = new ProjectStats.Quality(
                lineageTables.total() - lineageTables.temps(),
                matchedByFullName(t, p),
                matchedBySchemaTable(t, p),
                metaTables.total(),
                metaInLineage(t, p),
                lineageTables.total(),
                lineageTables.commentFilled(),
                metaTables.commentFilled(),
                metaColumns.total(),
                metaColumns.commentFilled(),
                count(isolatedTablesSql("count(*)"), t, p),
                count(metaOnlyTablesSql("count(*)"), t, p));

        // 近 7 天 / 近 30 天都由趋势加出来，不再各跑一条 count：
        // 图上七根柱子加起来是 12、旁边那行字写着 13 —— 这种差一没人解释得清
        ProjectStats.Activity activity = new ProjectStats.Activity(
                sumOfLast(trend, 7), sumOfLast(trend, trend.size()), versions.lastAt(),
                trend, recentParses(t, p, limits.recentParseLimit()));

        return new ProjectStats(scale, quality, activity,
                distributions(ctx, limits.distLimit()),
                new ProjectStats.Hubs(
                        hubs(t, p, "source_col_id", "target_col_id", limits.topLimit()),
                        hubs(t, p, "target_col_id", "source_col_id", limits.topLimit())),
                sync(t, p, cutoff, limits),
                new ProjectStats.Lists(
                        metaOnlyTableList(t, p, limits.listLimit()),
                        isolatedTableList(t, p, limits.listLimit())));
    }

    // ---------------- 规模 ----------------

    private TableCounts lineageTableCounts(long tenantId, long projectId) {
        return jdbc.queryForObject(
                "select count(*) as total, "
                        + "sum(case when lt.is_temp = 1 then 1 else 0 end) as temps, "
                        + "sum(case when lt.comment is not null and lt.comment <> '' "
                        + "         then 1 else 0 end) as commented "
                        + "from lineage_table lt where lt.tenant_id = ? and lt.project_id = ?",
                (rs, i) -> new TableCounts(rs.getInt("total"), rs.getInt("temps"),
                        rs.getInt("commented")),
                tenantId, projectId);
    }

    private CommentCounts metaTableCounts(long tenantId, long projectId) {
        return jdbc.queryForObject(
                "select count(*) as total, "
                        + "sum(case when m.comment is not null and m.comment <> '' "
                        + "         then 1 else 0 end) as commented "
                        + "from meta_table m where m.tenant_id = ? and m.project_id = ?",
                COMMENT_COUNTS_MAPPER, tenantId, projectId);
    }

    private CommentCounts metaColumnCounts(long tenantId, long projectId) {
        return jdbc.queryForObject(
                "select count(*) as total, "
                        + "sum(case when mc.comment is not null and mc.comment <> '' "
                        + "         then 1 else 0 end) as commented "
                        + "from meta_column mc where mc.tenant_id = ? and mc.project_id = ?",
                COMMENT_COUNTS_MAPPER, tenantId, projectId);
    }

    /** 版本总数与最近一次解析时间。一条 SQL 就够，别为 {@code max()} 再跑一趟。 */
    private VersionCounts versionCounts(long tenantId, long projectId) {
        return jdbc.queryForObject(
                "select count(*) as total, max(v.created_at) as last_at from lineage_version v "
                        + "where v.tenant_id = ? and v.project_id = ?",
                (rs, i) -> new VersionCounts(rs.getInt("total"),
                        toLocalDateTime(rs.getTimestamp("last_at"))),
                tenantId, projectId);
    }

    // ---------------- 覆盖率 ----------------

    /**
     * 非临时血缘表中，按<b>三段全名</b>能在元数据里找到对应行的张数。
     *
     * <p>{@code exists} 子句里的租户条件写成 {@code m.tenant_id = lt.tenant_id} 这种关联传递，
     * 而不是省略：{@code TenantIsolationArchTest} 只检查「整条语句里出现过 tenant_id」，
     * 子查询是它的已知盲区，这里得靠自觉。
     */
    private int matchedByFullName(long tenantId, long projectId) {
        return count("select count(*) from lineage_table lt "
                + "where lt.tenant_id = ? and lt.project_id = ? and lt.is_temp = 0 "
                + "and exists (select 1 from meta_table m "
                + "             where m.tenant_id = lt.tenant_id and m.project_id = lt.project_id "
                + "               and m.full_name = lower(lt.full_name))", tenantId, projectId);
    }

    /**
     * 同上，但<b>只比 {@code schema_name + table_name}</b>，忽略首段的数据目录。
     *
     * <p>这个数和上面那个的差距就是诊断结论本身：血缘侧按默认目录补全成
     * {@code default.ods.orders}，元数据挂在源端 catalog 下（{@code hive_prod.ods.orders}），
     * 首段永远对不上。两个数并排，用户才知道该去改数据目录还是去补元数据。
     */
    private int matchedBySchemaTable(long tenantId, long projectId) {
        return count("select count(*) from lineage_table lt "
                + "where lt.tenant_id = ? and lt.project_id = ? and lt.is_temp = 0 "
                + "and exists (select 1 from meta_table m "
                + "             where m.tenant_id = lt.tenant_id and m.project_id = lt.project_id "
                + "               and m.schema_name = lower(lt.schema_name) "
                + "               and m.table_name = lower(lt.table_name))", tenantId, projectId);
    }

    /** 反向：元数据表里能在血缘中找到同名表的张数。 */
    private int metaInLineage(long tenantId, long projectId) {
        return count("select count(*) from meta_table m "
                + "where m.tenant_id = ? and m.project_id = ? "
                + "and exists (select 1 from lineage_table lt "
                + "             where lt.tenant_id = m.tenant_id and lt.project_id = m.project_id "
                + "               and lower(lt.full_name) = m.full_name)", tenantId, projectId);
    }

    // ---------------- 两张「无血缘」清单 ----------------

    /**
     * 元数据里有结构、血缘里从没出现过的表。
     *
     * <p>返回 SQL 而不是常量片段，是为了让计数与清单共用同一份口径的同时，
     * 整条语句仍然落在一个 Java 语句里 —— {@code TenantIsolationArchTest}
     * 按语句聚合字面量，片段拼接会让它看不见 {@code tenant_id}。
     *
     * @param projection {@code count(*)} 或具体的列清单。<b>只能传本类里的字面量</b>
     */
    private static String metaOnlyTablesSql(String projection) {
        return "select " + projection + " from meta_table m "
                + "where m.tenant_id = ? and m.project_id = ? "
                + "and not exists (select 1 from lineage_table lt "
                + "                 where lt.tenant_id = m.tenant_id "
                + "                   and lt.project_id = m.project_id "
                + "                   and lower(lt.full_name) = m.full_name)";
    }

    /**
     * 血缘里有、但当前版本下既无上游也无下游的孤立表。临时表不算 ——
     * 它们本来就会被穿透掉，列出来只是噪音。
     *
     * <p>上下游各写一个 {@code not exists}，<b>不能合并成一个带 {@code or} 的 join</b>：
     * OR 挂在 join 条件上，{@code target_col_id} / {@code source_col_id} 两个索引谁都用不上，
     * {@code lineage_edge} 一大就退化成每张表扫一遍全表。拆开之后两个 exists 各走各的索引，
     * 且任一命中就能短路。
     */
    private static String isolatedTablesSql(String projection) {
        return "select " + projection + " from lineage_table lt "
                + "where lt.tenant_id = ? and lt.project_id = ? and lt.is_temp = 0 "
                + "and not exists ("
                + "  select 1 from lineage_column c "
                + "  join lineage_edge e on e.target_col_id = c.id "
                + "  join lineage_version v on v.id = e.version_id and v.is_current = 1 "
                + "  where c.tenant_id = lt.tenant_id and c.project_id = lt.project_id "
                + "    and c.table_id = lt.id) "
                + "and not exists ("
                + "  select 1 from lineage_column c "
                + "  join lineage_edge e on e.source_col_id = c.id "
                + "  join lineage_version v on v.id = e.version_id and v.is_current = 1 "
                + "  where c.tenant_id = lt.tenant_id and c.project_id = lt.project_id "
                + "    and c.table_id = lt.id)";
    }

    private List<ProjectStats.TableBrief> metaOnlyTableList(long tenantId, long projectId, int limit) {
        return jdbc.query(metaOnlyTablesSql("m.full_name, m.comment")
                        + " order by m.full_name limit ?",
                // 元数据独有的表在血缘侧不存在，没有可跳转的 id
                (rs, i) -> new ProjectStats.TableBrief(null, rs.getString(1), rs.getString(2)),
                tenantId, projectId, limit);
    }

    private List<ProjectStats.TableBrief> isolatedTableList(long tenantId, long projectId, int limit) {
        // 中文名取血缘侧自己的列，不关联元数据：两边的 full_name 首段对不上，
        // 关联出来的那一列恒为空（这是修过的一个真实缺陷）
        return jdbc.query(isolatedTablesSql("lt.id, lt.full_name, lt.comment")
                        + " order by lt.full_name limit ?",
                (rs, i) -> new ProjectStats.TableBrief(rs.getLong(1), rs.getString(2),
                        rs.getString(3)),
                tenantId, projectId, limit);
    }

    // ---------------- 最近解析 ----------------

    /** 一次解析可能产生多个版本（多张目标表），这里按版本逐条列。 */
    private List<ProjectStats.RecentParse> recentParses(long tenantId, long projectId, int limit) {
        return jdbc.query(
                "select v.id, v.target_table_id, tb.full_name, v.version_no, v.db_type, v.is_current, "
                        + "v.stat_tables, v.stat_columns, v.stat_edges, v.created_at "
                        + "from lineage_version v "
                        + "join lineage_table tb on tb.id = v.target_table_id "
                        + "where v.tenant_id = ? and v.project_id = ? "
                        + "order by v.created_at desc, v.id desc limit ?",
                (rs, i) -> new ProjectStats.RecentParse(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getBoolean(6),
                        rs.getInt(7), rs.getInt(8), rs.getInt(9),
                        toLocalDateTime(rs.getTimestamp(10))),
                tenantId, projectId, limit);
    }

    // ---------------- 分布 ----------------

    private ProjectStats.Distributions distributions(LineageContext ctx, int limit) {
        return new ProjectStats.Distributions(
                // 用 version 的方言不用 table 的：lineage_table.db_type 可为 null，
                // 而且「这段 SQL 是用什么方言解析的」才是有意义的问题
                distribution(ctx, "lineage_version", "v", "db_type", limit),
                distribution(ctx, "lineage_table", "lt", "catalog_name", limit),
                distribution(ctx, "meta_table", "m", "source", limit),
                distribution(ctx, "lineage_table", "lt", "schema_name", limit));
    }

    /**
     * 按某一列分组计数，取前 N。
     *
     * <p>{@code name} 允许为 null，不在 SQL 里 {@code coalesce} 成「未指定」——
     * 那是展示层的措辞。
     *
     * @param table 表名，@param alias 别名，@param column 分组列。
     *              <b>三者都只能传本类里的字面量</b>，它们直接拼进 SQL
     */
    private List<StatCount> distribution(LineageContext ctx, String table, String alias,
                                         String column, int limit) {
        String col = alias + "." + column;
        return jdbc.query(
                "select " + col + " as name, count(*) as c from " + table + " " + alias
                        + " where " + alias + ".tenant_id = ? and " + alias + ".project_id = ? "
                        + "group by " + col + " order by c desc, name limit ?",
                COUNT_MAPPER, ctx.tenantId(), ctx.projectId(), limit);
    }

    // ---------------- 枢纽表 ----------------

    /**
     * 枢纽表：某一侧的表连到了多少张<b>不同的对侧表</b>。
     *
     * <p>两点不能省：
     * <ul>
     *   <li>{@code count(distinct t.id)} 而不是 {@code count(*)} —— 边数会被宽表放大，
     *       一张 200 列的表随便就上千条边，按边排出来永远是那几张宽表。
     *       「这张表被多少地方用」问的是表数。</li>
     *   <li>{@code t.id <> st.id} —— {@code insert overwrite t select … from t} 这种写法很常见，
     *       不排会让表成为自己的上游。</li>
     * </ul>
     *
     * @param fromCol 起点侧的边列，@param toCol 对侧的边列。
     *                <b>只能传本类里的字面量</b>，它们直接拼进 SQL
     */
    private List<ProjectStats.HubTable> hubs(long tenantId, long projectId,
                                             String fromCol, String toCol, int limit) {
        return jdbc.query(
                "select st.id, st.full_name, count(distinct t.id) as degree "
                        + "from lineage_edge e "
                        + "join lineage_version v on v.id = e.version_id and v.is_current = 1 "
                        + "join lineage_column sc on sc.id = e." + fromCol + " "
                        + "join lineage_table st on st.id = sc.table_id "
                        + "join lineage_column tc on tc.id = e." + toCol + " "
                        + "join lineage_table t on t.id = tc.table_id "
                        + "where e.tenant_id = ? and e.project_id = ? and t.id <> st.id "
                        + "group by st.id, st.full_name "
                        + "order by degree desc, st.full_name limit ?",
                (rs, i) -> new ProjectStats.HubTable(rs.getLong(1), rs.getString(2), rs.getInt(3)),
                tenantId, projectId, limit);
    }

    // ---------------- 同步任务 ----------------

    private ProjectStats.Sync sync(long tenantId, long projectId, Timestamp cutoff,
                                   StatsLimits limits) {
        List<StatCount> byStatus = jdbc.query(
                "select j.status as name, count(*) as c from sync_job j "
                        + "where j.tenant_id = ? and j.project_id = ? and j.created_at >= ? "
                        + "group by j.status order by c desc, name",
                COUNT_MAPPER, tenantId, projectId, cutoff);

        // 与 byStatus 同一个窗口：同一个卡片上写着「近 N 天」，
        // 底下却列出半年前的失败，用户只会以为这些任务刚跑过
        List<ProjectStats.SyncFailure> failures = jdbc.query(
                "select j.id, j.status, j.scope, j.source_catalog, j.source_database, "
                        + "j.source_schema, j.tables, j.failed_cnt, j.message, j.finished_at "
                        + "from sync_job j "
                        + "where j.tenant_id = ? and j.project_id = ? and j.created_at >= ? "
                        + "and j.status in (?, ?) "
                        + "order by j.created_at desc, j.id desc limit ?",
                (rs, i) -> new ProjectStats.SyncFailure(
                        rs.getLong("id"), rs.getString("status"), rs.getString("scope"),
                        syncTarget(rs.getString("scope"), rs.getString("source_catalog"),
                                rs.getString("source_database"), rs.getString("source_schema"),
                                rs.getString("tables")),
                        rs.getInt("failed_cnt"), rs.getString("message"),
                        toLocalDateTime(rs.getTimestamp("finished_at"))),
                tenantId, projectId, cutoff,
                SyncStatus.FAILED.name(), SyncStatus.PARTIAL.name(), limits.topLimit());

        return new ProjectStats.Sync(limits.trendDays(), byStatus, failures);
    }

    /**
     * 把「导了什么」拼成一句能直接显示的话。
     *
     * <p>拼在服务端而不是前端：三个来源字段哪个有值取决于 {@code scope}
     * （Gravitino 用 catalog、dbx 用 database），这个分支判断跟着
     * {@link com.dwai.lineage.enums.SyncScope} 走，放展示层就得两边一起改。
     */
    static String syncTarget(String scope, String sourceCatalog, String sourceDatabase,
                             String sourceSchema, String tables) {
        String root = firstNonBlank(sourceCatalog, sourceDatabase);
        if ("SCHEMA".equals(scope)) {
            return join(root, sourceSchema);
        }
        if ("TABLE".equals(scope)) {
            List<String> names = tables == null || tables.isBlank()
                    ? List.of() : List.of(tables.split("\n"));
            if (names.isEmpty()) {
                return join(root, sourceSchema);
            }
            String first = join(join(root, sourceSchema), names.get(0));
            return names.size() == 1 ? first : first + " 等 " + names.size() + " 张";
        }
        // CATALOG：整个目录，没有更细的层级可写
        return root == null ? "-" : root;
    }

    private static String join(String left, String right) {
        if (left == null || left.isBlank()) {
            return right == null || right.isBlank() ? null : right;
        }
        return right == null || right.isBlank() ? left : left + "." + right;
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.strip();
        }
        return fallback != null && !fallback.isBlank() ? fallback.strip() : null;
    }

    // ==================================================================
    // 租户口径
    // ==================================================================

    /**
     * 固定 9 条查询 + Java 侧合并，<b>与项目数无关</b>。
     *
     * <p>「先查项目列表，再对每个项目跑 6 条 count」在 20 个项目时就是 121 条 SQL ——
     * 这是本接口唯一真正的技术风险，所以每一条聚合都按 {@code project_id} 分组一次拿全，
     * 再以项目列表为驱动做左连接。
     *
     * <p><b>必须以 Q0 的项目列表为驱动。</b> 一条血缘都没有的项目不会出现在任何一条
     * 聚合结果里，反过来以血缘表为驱动会让空项目从表里消失 ——
     * 而空项目恰恰是这张对比表最该暴露的东西。
     */
    @Override
    public TenantStats tenantStats(LineageContext ctx, StatsLimits limits) {
        long t = ctx.tenantId();
        LocalDate trendFrom = trendFrom(limits);
        Timestamp cutoff = Timestamp.valueOf(trendFrom.atStartOfDay());

        // Q0 项目列表（驱动表）
        List<ProjectHeader> headers = jdbc.query(
                "select p.id, p.code, p.name, p.status from project p "
                        + "where p.tenant_id = ? order by p.name",
                (rs, i) -> new ProjectHeader(rs.getLong("id"), rs.getString("code"),
                        rs.getString("name"), rs.getInt("status")),
                t);

        // Q1 血缘表 + 临时表
        Map<Long, TableCounts> lineageTables = new HashMap<>();
        jdbc.query("select lt.project_id, count(*) as c, "
                        + "sum(case when lt.is_temp = 1 then 1 else 0 end) as temps "
                        + "from lineage_table lt where lt.tenant_id = ? group by lt.project_id",
                rs -> {
                    // 租户口径不看中文名填充率（那是项目页的质量指标），第三项恒 0
                    lineageTables.put(rs.getLong("project_id"),
                            new TableCounts(rs.getInt("c"), rs.getInt("temps"), 0));
                }, t);

        // Q2 血缘字段
        Map<Long, Integer> lineageColumns = groupedCount(
                "select lc.project_id, count(*) as c from lineage_column lc "
                        + "where lc.tenant_id = ? group by lc.project_id", t);

        // Q3 当前边
        Map<Long, Integer> currentEdges = groupedCount(
                "select e.project_id, count(*) as c from lineage_edge e "
                        + "join lineage_version v on v.id = e.version_id and v.is_current = 1 "
                        + "where e.tenant_id = ? group by e.project_id", t);

        // Q4 版本总数 + 最近解析
        Map<Long, VersionCounts> versions = new HashMap<>();
        jdbc.query("select v.project_id, count(*) as c, max(v.created_at) as last_at "
                        + "from lineage_version v where v.tenant_id = ? group by v.project_id",
                rs -> {
                    versions.put(rs.getLong("project_id"), new VersionCounts(
                            rs.getInt("c"), toLocalDateTime(rs.getTimestamp("last_at"))));
                }, t);

        // Q5 元数据表
        Map<Long, Integer> metaTables = groupedCount(
                "select m.project_id, count(*) as c from meta_table m "
                        + "where m.tenant_id = ? group by m.project_id", t);

        // Q6 元数据字段
        Map<Long, Integer> metaColumns = groupedCount(
                "select mc.project_id, count(*) as c from meta_column mc "
                        + "where mc.tenant_id = ? group by mc.project_id", t);

        // Q7 元数据服务（租户级，没有 project_id）
        List<TenantStats.SourceTypeCount> byType = jdbc.query(
                "select ms.type as name, count(*) as c, "
                        + "sum(case when ms.enabled = 1 then 1 else 0 end) as enabled_cnt "
                        + "from metadata_source ms where ms.tenant_id = ? "
                        + "group by ms.type order by c desc, name",
                (rs, i) -> new TenantStats.SourceTypeCount(
                        rs.getString("name"), rs.getInt("c"), rs.getInt("enabled_cnt")),
                t);

        // Q8 全租户近 N 天的解析时间戳，回 Java 分桶。
        //
        // 这里连 project_id 一起取回来，各项目的「近 30 天解析」就由同一批行分组数出来 ——
        // 文档里那版把它做成 Q4 的 sum(case when created_at >= ?)，两个 ? 一个在 select
        // 列表一个在 where，JDBC 按位置绑，写反了不报错、只让这个数全变 0 或全等于总数。
        // 少一个绑参陷阱之外还多一层好处：汇总趋势和每行的近 30 天出自同一批行，
        // 不会出现「趋势加起来 41、表里那格写 42」这种谁也说不清的差一。
        List<ProjectTimestamp> stamps = jdbc.query(
                "select v.project_id, v.created_at from lineage_version v "
                        + "where v.tenant_id = ? and v.created_at >= ?",
                (rs, i) -> new ProjectTimestamp(rs.getLong("project_id"),
                        toLocalDateTime(rs.getTimestamp("created_at"))),
                t, cutoff);

        LocalDate trendTo = trendFrom.plusDays(limits.trendDays() - 1L);
        Map<Long, Integer> parses30d = new HashMap<>();
        List<LocalDateTime> all = new ArrayList<>(stamps.size());
        for (ProjectTimestamp row : stamps) {
            if (row.createdAt() == null) {
                continue;
            }
            all.add(row.createdAt());
            // 只数落在 30 个桶里的：与趋势图口径一致。窗口右边界之外的行
            // （时钟偏差写进来的未来时间）在图上没有对应的柱子，也就不该计进这个数
            LocalDate day = row.createdAt().toLocalDate();
            if (!day.isBefore(trendFrom) && !day.isAfter(trendTo)) {
                parses30d.merge(row.projectId(), 1, Integer::sum);
            }
        }
        List<DayCount> trend = trend(all, trendFrom, limits.trendDays());

        List<TenantStats.ProjectSummary> projects = new ArrayList<>(headers.size());
        for (ProjectHeader h : headers) {
            TableCounts tables = lineageTables.getOrDefault(h.id(), TableCounts.ZERO);
            VersionCounts version = versions.getOrDefault(h.id(), VersionCounts.ZERO);
            projects.add(new TenantStats.ProjectSummary(
                    h.id(), h.code(), h.name(), h.status() == 1,
                    tables.total(), tables.temps(),
                    lineageColumns.getOrDefault(h.id(), 0),
                    currentEdges.getOrDefault(h.id(), 0),
                    version.total(),
                    metaTables.getOrDefault(h.id(), 0),
                    metaColumns.getOrDefault(h.id(), 0),
                    parses30d.getOrDefault(h.id(), 0),
                    version.lastAt()));
        }

        return new TenantStats(overview(projects), scale(projects),
                sources(byType),
                new TenantStats.Activity(sumOfLast(trend, 7),
                        projects.stream().mapToInt(TenantStats.ProjectSummary::parses30d).sum(),
                        projects.stream()
                                .map(TenantStats.ProjectSummary::lastParseAt)
                                .filter(Objects::nonNull)
                                .max(Comparator.naturalOrder())
                                .orElse(null),
                        trend),
                projects);
    }

    private static TenantStats.Overview overview(List<TenantStats.ProjectSummary> projects) {
        int enabled = (int) projects.stream().filter(TenantStats.ProjectSummary::enabled).count();
        int empty = (int) projects.stream().filter(p -> p.lineageTables() == 0).count();
        return new TenantStats.Overview(projects.size(), enabled, projects.size() - enabled, empty);
    }

    private static TenantStats.Scale scale(List<TenantStats.ProjectSummary> projects) {
        return new TenantStats.Scale(
                sum(projects, TenantStats.ProjectSummary::lineageTables),
                sum(projects, TenantStats.ProjectSummary::tempTables),
                sum(projects, TenantStats.ProjectSummary::lineageColumns),
                sum(projects, TenantStats.ProjectSummary::currentEdges),
                sum(projects, TenantStats.ProjectSummary::versions),
                sum(projects, TenantStats.ProjectSummary::metaTables),
                sum(projects, TenantStats.ProjectSummary::metaColumns));
    }

    private static TenantStats.Sources sources(List<TenantStats.SourceTypeCount> byType) {
        int total = byType.stream().mapToInt(TenantStats.SourceTypeCount::count).sum();
        int enabled = byType.stream().mapToInt(TenantStats.SourceTypeCount::enabled).sum();
        return new TenantStats.Sources(total, enabled, byType);
    }

    private static int sum(List<TenantStats.ProjectSummary> projects,
                           ToIntFunction<TenantStats.ProjectSummary> field) {
        return projects.stream().mapToInt(field).sum();
    }

    /** {@code project_id -> count} 的通用取法。SQL 必须 select 出 {@code project_id} 与 {@code c}。 */
    private Map<Long, Integer> groupedCount(String sql, long tenantId) {
        Map<Long, Integer> result = new HashMap<>();
        jdbc.query(sql, rs -> {
            result.put(rs.getLong("project_id"), rs.getInt("c"));
        }, tenantId);
        return result;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** 窗口的第一天：含今天在内共 {@code trendDays} 天。 */
    private static LocalDate trendFrom(StatsLimits limits) {
        return LocalDate.now().minusDays(limits.trendDays() - 1L);
    }

    /**
     * 把原始时间戳按天归并，并<b>补满整个窗口</b>。
     *
     * <p>补零在服务端做，不返回稀疏数组让前端补：浏览器与服务器的「今天」可能差一天
     * （时区、跨零点），前端补零会让 X 轴整体错位一格，而这种 bug 只在特定时段复现。
     */
    private static List<DayCount> trend(List<LocalDateTime> stamps, LocalDate from, int days) {
        Map<LocalDate, Integer> byDay = new HashMap<>();
        for (LocalDateTime ts : stamps) {
            if (ts != null) {
                byDay.merge(ts.toLocalDate(), 1, Integer::sum);
            }
        }
        List<DayCount> out = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate day = from.plusDays(i);
            out.add(new DayCount(day, byDay.getOrDefault(day, 0)));
        }
        return out;
    }

    /**
     * 趋势末 N 桶之和。
     *
     * <p>「近 7 天」由趋势算出而不是再跑一条 count：两个数出自同一批行，
     * 就不会出现图上七根柱子加起来是 12、旁边那行字写着 13 的情况。
     */
    private static int sumOfLast(List<DayCount> trend, int days) {
        int total = 0;
        for (int i = Math.max(0, trend.size() - days); i < trend.size(); i++) {
            total += trend.get(i).count();
        }
        return total;
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    private static final RowMapper<StatCount> COUNT_MAPPER =
            (rs, i) -> new StatCount(rs.getString("name"), rs.getInt("c"));

    private static final RowMapper<LocalDateTime> TIMESTAMP_MAPPER =
            (rs, i) -> toLocalDateTime(rs.getTimestamp(1));

    private static final RowMapper<CommentCounts> COMMENT_COUNTS_MAPPER =
            (rs, i) -> new CommentCounts(rs.getInt("total"), rs.getInt("commented"));

    private record TableCounts(int total, int temps, int commentFilled) {
        static final TableCounts ZERO = new TableCounts(0, 0, 0);
    }

    private record CommentCounts(int total, int commentFilled) {
    }

    private record VersionCounts(int total, LocalDateTime lastAt) {
        static final VersionCounts ZERO = new VersionCounts(0, null);
    }

    private record ProjectHeader(long id, String code, String name, int status) {
    }

    private record ProjectTimestamp(long projectId, LocalDateTime createdAt) {
    }
}
