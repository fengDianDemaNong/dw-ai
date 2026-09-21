package persistence;

import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.dto.DayCount;
import com.dwai.lineage.dto.ProjectStats;
import com.dwai.lineage.dto.StatCount;
import com.dwai.lineage.dto.TenantStats;
import com.dwai.lineage.persistence.JdbcStatsRepository;
import com.dwai.lineage.persistence.StatsLimits;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 概览统计两个口径的聚合查询。
 *
 * <p>为什么值得这么多用例：这里全是聚合 SQL，写错了不会抛异常，只会在首页上显示一个错的
 * 数字 —— 覆盖率少算一半、边数多算一倍、空项目从对比表里消失，没有一个会让请求变红。
 * 沿用 {@link CatalogStatsAndLookupTest} 的两条模板（租户隔离 + 空数据不出 null），
 * 再把每条容易写错的口径各钉一遍。
 */
public class StatsRepositoryTest {

    private static final LineageContext CTX = new LineageContext(1, 1);

    /** 同租户下的另一个项目：项目口径必须排除它，租户口径必须算进它。 */
    private static final LineageContext SIBLING = new LineageContext(1, 2);

    private static final LineageContext OTHER_TENANT = new LineageContext(2, 1);

    private static final StatsLimits LIMITS = StatsLimits.DEFAULTS;

    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcStatsRepository stats;

    @Before
    public void setUp() {
        if (dataSource == null) {
            DataSource ds = TestDataSources.h2("stats_repo_h2");
            TestSchema.apply(ds, "h2");
            dataSource = ds;
        }
        jdbc = new JdbcTemplate(dataSource);
        stats = new JdbcStatsRepository(jdbc);
        // project 与 metadata_source 也要清：建库脚本种了一条默认租户/项目，
        // 留着会让「租户下有几个项目」的断言全都要 +1，读起来还得先记住这条隐藏数据
        for (String t : new String[]{"lineage_edge", "lineage_version", "lineage_column",
                "lineage_table", "meta_column", "meta_table", "sync_job", "temp_rule",
                "data_catalog", "metadata_source", "project"}) {
            jdbc.update("delete from " + t);
        }
    }

    // ==================================================================
    // 项目口径 —— 空值语义
    // ==================================================================

    /**
     * 空项目返回的是一堆 0 和一堆 {@code []}，不是 null，也不是缺字段。
     *
     * <p>这条是整份契约的地基：前端对数值字段直接做算术、对数组直接 {@code v-for}，
     * 拿到 null 的表现是页面上一片 NaN 或者整块白屏，而不是优雅降级。
     */
    @Test
    public void emptyProjectYieldsZerosNotNulls() {
        ProjectStats s = stats.projectStats(CTX, LIMITS);

        assertEquals(0, s.scale().lineageTables());
        assertEquals(0, s.scale().tempTables());
        assertEquals(0, s.scale().lineageColumns());
        assertEquals(0, s.scale().currentEdges());
        assertEquals(0, s.scale().versions());
        assertEquals(0, s.scale().metaTables());
        assertEquals(0, s.scale().metaColumns());
        assertEquals(0, s.scale().dataCatalogs());
        assertEquals(0, s.scale().tempRules());

        assertEquals(0, s.quality().lineageTablesNonTemp());
        assertEquals(0, s.quality().metaMatchedByFullName());
        assertEquals(0, s.quality().metaMatchedBySchemaTable());
        assertEquals(0, s.quality().metaInLineage());
        assertEquals(0, s.quality().lineageTableCommentFilled());
        assertEquals(0, s.quality().metaTableCommentFilled());
        assertEquals(0, s.quality().metaColumnCommentFilled());
        assertEquals(0, s.quality().isolatedTables());
        assertEquals(0, s.quality().metaOnlyTables());

        assertEquals(0, s.activity().parses7d());
        assertEquals(0, s.activity().parses30d());
        assertNull("从未解析过时是 null，不是纪元时间也不是空串", s.activity().lastParseAt());
        assertTrue(s.activity().recentParses().isEmpty());

        assertTrue(s.distributions().byDbType().isEmpty());
        assertTrue(s.distributions().byCatalog().isEmpty());
        assertTrue(s.distributions().byMetaSource().isEmpty());
        assertTrue(s.distributions().bySchema().isEmpty());
        assertTrue(s.hubs().topDownstream().isEmpty());
        assertTrue(s.hubs().topUpstream().isEmpty());

        assertEquals(StatsLimits.TREND_DAYS, s.sync().days());
        assertTrue(s.sync().byStatus().isEmpty());
        assertTrue(s.sync().recentFailures().isEmpty());
        assertTrue(s.lists().metaOnlyTables().isEmpty());
        assertTrue(s.lists().isolatedTables().isEmpty());

        assertFullWindow(s.activity().trend());
        for (DayCount d : s.activity().trend()) {
            assertEquals("没有解析的那天要有一个 0 的桶，不能整条省掉", 0, d.count());
        }
    }

    // ==================================================================
    // 项目口径 —— 租户/项目隔离
    // ==================================================================

    /** 各项计数不能把别的租户、也不能把同租户的别的项目算进来。 */
    @Test
    public void countsAreScopedToTheTenant() {
        table(1, CTX, "default", "ods", "mine", "default.ods.mine", null);
        column(1, CTX, 1, "id", "default.ods.mine.id");
        metaTable(1, CTX, "default", "ods", "mine", "default.ods.mine", null);

        table(2, OTHER_TENANT, "default", "ods", "theirs", "default.ods.theirs", null);
        column(2, OTHER_TENANT, 2, "id", "default.ods.theirs.id");
        metaTable(2, OTHER_TENANT, "default", "ods", "theirs", "default.ods.theirs", null);
        metaColumn(1, OTHER_TENANT, 2, "id", "default.ods.theirs.id");

        table(3, SIBLING, "default", "ods", "sibling", "default.ods.sibling", null);
        column(3, SIBLING, 3, "id", "default.ods.sibling.id");

        ProjectStats s = stats.projectStats(CTX, LIMITS);
        assertEquals(1, s.scale().lineageTables());
        assertEquals(1, s.scale().lineageColumns());
        assertEquals(1, s.scale().metaTables());
        assertEquals("别的租户的元数据字段不能算进来", 0, s.scale().metaColumns());
    }

    // ==================================================================
    // 项目口径 —— 各条容易写错的口径
    // ==================================================================

    /**
     * 覆盖率的两个数说的不是一件事，差距本身就是诊断结论。
     *
     * <p>血缘侧按默认数据目录补全成 {@code default.ods.orders}，元数据挂在源端 catalog 下
     * （{@code hive_prod.ods.orders}）—— 首段永远对不上。只给「按全名」一个数，
     * 用户会看到 0% 并跑去重导一遍元数据，导完还是 0%。
     */
    @Test
    public void coverageSeparatesFullNameFromSchemaTableMatch() {
        table(1, CTX, "default", "ods", "orders", "default.ods.orders", null);
        metaTable(1, CTX, "hive_prod", "ods", "orders", "hive_prod.ods.orders", null);

        ProjectStats.Quality q = stats.projectStats(CTX, LIMITS).quality();
        assertEquals(1, q.lineageTablesNonTemp());
        assertEquals("首段目录不同，按全名匹配不上", 0, q.metaMatchedByFullName());
        assertEquals("忽略目录段之后是能对上的", 1, q.metaMatchedBySchemaTable());
        assertEquals("反向同理：这张元数据表在血缘里按全名找不到", 0, q.metaInLineage());
    }

    /**
     * {@code lower()} 只加在血缘侧。
     *
     * <p>血缘侧保留 SQL 的原始大小写，元数据侧入库时恒为小写。两边都不加，
     * 大小写不同的同一张表会被当成两张，覆盖率直接掉到 0。
     */
    @Test
    public void fullNameMatchLowercasesOnlyTheLineageSide() {
        table(1, CTX, "Default", "Ods", "Orders", "Default.Ods.Orders", null);
        metaTable(1, CTX, "default", "ods", "orders", "default.ods.orders", null);

        ProjectStats.Quality q = stats.projectStats(CTX, LIMITS).quality();
        assertEquals(1, q.metaMatchedByFullName());
        assertEquals(1, q.metaMatchedBySchemaTable());
        assertEquals(1, q.metaInLineage());
    }

    /** 临时表算进规模，但不算进覆盖率的分母 —— 它们本来就不该有元数据。 */
    @Test
    public void temporaryTablesCountInScaleButNotInTheCoverageDenominator() {
        table(1, CTX, "default", "ods", "real", "default.ods.real", null);
        table(2, CTX, "default", "tmp", "t", "default.tmp.t", null);
        jdbc.update("update lineage_table set is_temp = 1 where id = 2");

        ProjectStats s = stats.projectStats(CTX, LIMITS);
        assertEquals(2, s.scale().lineageTables());
        assertEquals(1, s.scale().tempTables());
        assertEquals(1, s.quality().lineageTablesNonTemp());
        assertEquals("临时表不进孤立表清单：它们本来就会被穿透掉，列出来只是噪音",
                1, s.quality().isolatedTables());
    }

    /** 中文名填充率数的是「非空且非空串」，空串不算填了。 */
    @Test
    public void commentFilledIgnoresEmptyStrings() {
        table(1, CTX, "default", "ods", "a", "default.ods.a", "订单表");
        table(2, CTX, "default", "ods", "b", "default.ods.b", "");
        table(3, CTX, "default", "ods", "c", "default.ods.c", null);
        metaTable(1, CTX, "default", "ods", "a", "default.ods.a", "订单表");
        metaTable(2, CTX, "default", "ods", "b", "default.ods.b", "");
        metaColumn(1, CTX, 1, "id", "default.ods.a.id", "主键");
        metaColumn(2, CTX, 1, "name", "default.ods.a.name", "");

        ProjectStats.Quality q = stats.projectStats(CTX, LIMITS).quality();
        assertEquals(1, q.lineageTableCommentFilled());
        assertEquals(1, q.metaTableCommentFilled());
        assertEquals(1, q.metaColumnCommentFilled());
        assertEquals(2, q.metaColumns());
    }

    /**
     * 边数只算当前版本。
     *
     * <p>历史版本的边还躺在库里，混进来的话这个数会比任何一张血缘图上能数出来的都大 ——
     * 而「首页写着 4210 条边，点进去只有 2100 条」是没法解释的。
     */
    @Test
    public void currentEdgesIgnoreHistoricalVersions() {
        table(1, CTX, "default", "ods", "src", "default.ods.src", null);
        table(2, CTX, "default", "dwd", "dst", "default.dwd.dst", null);
        column(1, CTX, 1, "id", "default.ods.src.id");
        column(2, CTX, 2, "id", "default.dwd.dst.id");
        version(1, CTX, 2, 1, "hive", false, LocalDateTime.now().minusDays(2));
        version(2, CTX, 2, 2, "hive", true, LocalDateTime.now());
        edge(CTX, 1, 2, 1);
        edge(CTX, 2, 2, 1);

        ProjectStats s = stats.projectStats(CTX, LIMITS);
        assertEquals("两个版本各一条边，只有当前版本那条算数", 1, s.scale().currentEdges());
        assertEquals("版本总数仍然是两个 —— 这一项本来就含历史", 2, s.scale().versions());
    }

    /**
     * 枢纽表的 degree 数的是<b>表</b>，不是边；且不能把自己算成自己的上游。
     *
     * <p>按边数排，一张 200 列的宽表随便就上千条边，榜单上永远是同几张宽表；
     * 而 {@code insert overwrite t select … from t} 这种写法很常见，
     * 不排除自引用的话这张表会凭空多出一个下游。
     */
    @Test
    public void hubDegreeCountsTablesAndSkipsSelfReference() {
        table(1, CTX, "default", "ods", "src", "default.ods.src", null);
        table(2, CTX, "default", "dwd", "dst", "default.dwd.dst", null);
        column(1, CTX, 1, "a", "default.ods.src.a");
        column(2, CTX, 1, "b", "default.ods.src.b");
        column(3, CTX, 2, "a", "default.dwd.dst.a");
        column(4, CTX, 2, "b", "default.dwd.dst.b");
        version(1, CTX, 2, 1, "hive", true, LocalDateTime.now());
        // src 的两个字段各连到 dst 的一个字段：两条边，但只是一张下游表
        edge(CTX, 1, 3, 1);
        edge(CTX, 1, 4, 2);
        // 自引用：src.b <- src.a，不能让 src 成为自己的下游
        edge(CTX, 1, 2, 1);

        List<ProjectStats.HubTable> down = stats.projectStats(CTX, LIMITS).hubs().topDownstream();
        assertEquals(1, down.size());
        assertEquals("default.ods.src", down.get(0).fullName());
        assertEquals("两条边打到同一张下游表，degree 是 1 不是 2", 1, down.get(0).degree());

        List<ProjectStats.HubTable> up = stats.projectStats(CTX, LIMITS).hubs().topUpstream();
        assertEquals(1, up.size());
        assertEquals("default.dwd.dst", up.get(0).fullName());
        assertEquals(1, up.get(0).degree());
    }

    /**
     * 分布按 {@code lineage_version.db_type} 而不是 {@code lineage_table.db_type}。
     *
     * <p>后者可为 null，而且「这段 SQL 是用什么方言解析的」才是有意义的问题。
     */
    @Test
    public void dialectDistributionComesFromTheVersionNotTheTable() {
        table(1, CTX, "default", "ods", "t", "default.ods.t", null);
        jdbc.update("update lineage_table set db_type = 'mysql' where id = 1");
        version(1, CTX, 1, 1, "hive", true, LocalDateTime.now());

        List<StatCount> byDbType = stats.projectStats(CTX, LIMITS).distributions().byDbType();
        assertEquals(1, byDbType.size());
        assertEquals("hive", byDbType.get(0).name());
        assertEquals(1, byDbType.get(0).count());
    }

    /** 分布按计数倒序，并截到 DIST_LIMIT 条。 */
    @Test
    public void distributionsAreSortedByCountAndTruncated() {
        StatsLimits narrow = new StatsLimits(StatsLimits.TREND_DAYS, 10, 20, 5, 2);
        int id = 1;
        // ods 三张、dwd 两张、dim 一张
        for (int i = 0; i < 3; i++, id++) {
            table(id, CTX, "default", "ods", "t" + id, "default.ods.t" + id, null);
        }
        for (int i = 0; i < 2; i++, id++) {
            table(id, CTX, "default", "dwd", "t" + id, "default.dwd.t" + id, null);
        }
        table(id, CTX, "default", "dim", "t" + id, "default.dim.t" + id, null);

        List<StatCount> bySchema = stats.projectStats(CTX, narrow).distributions().bySchema();
        assertEquals("DIST_LIMIT = 2 时只保留前两组", 2, bySchema.size());
        assertEquals("ods", bySchema.get(0).name());
        assertEquals(3, bySchema.get(0).count());
        assertEquals("dwd", bySchema.get(1).name());
    }

    /**
     * 趋势恒为 TREND_DAYS 条、旧 → 新、缺口补零，且窗口外的解析不计入近 30 天。
     *
     * <p>补零在服务端做：浏览器与服务器的「今天」可能差一天（时区、跨零点），
     * 前端补零会让 X 轴整体错位一格，而这种 bug 只在特定时段复现。
     */
    @Test
    public void trendIsZeroFilledAndBoundedByTheWindow() {
        table(1, CTX, "default", "ods", "t", "default.ods.t", null);
        // 用当天零点而不是 now().minusHours(1)：凌晨跑用例时后者会落到昨天的桶里
        version(1, CTX, 1, 1, "hive", true, LocalDate.now().atStartOfDay());
        version(2, CTX, 1, 2, "hive", false, LocalDate.now().minusDays(3).atTime(10, 0));
        version(3, CTX, 1, 3, "hive", false, LocalDate.now().minusDays(9).atTime(10, 0));
        // 窗口之外：进版本总数与 lastParseAt 的候选，但不进趋势、不进近 30 天
        version(4, CTX, 1, 4, "hive", false, LocalDate.now().minusDays(60).atTime(10, 0));

        ProjectStats.Activity a = stats.projectStats(CTX, LIMITS).activity();
        List<DayCount> trend = assertFullWindow(a.trend());

        assertEquals(1, trend.get(trend.size() - 1).count());
        assertEquals(1, trend.get(trend.size() - 4).count());
        assertEquals(1, trend.get(trend.size() - 10).count());
        assertEquals(0, trend.get(trend.size() - 2).count());

        assertEquals("近 7 天只有今天和 3 天前那两次", 2, a.parses7d());
        assertEquals("60 天前那次在窗口之外", 3, a.parses30d());
        assertEquals("版本总数含窗口外的历史", 4, stats.projectStats(CTX, LIMITS).scale().versions());
        assertNotNull(a.lastParseAt());
        assertEquals(LocalDate.now(), a.lastParseAt().toLocalDate());
    }

    /** 最近解析按时间倒序，且带上目标表全名 —— 前端要据 tableId 跳详情。 */
    @Test
    public void recentParsesAreOrderedNewestFirst() {
        table(1, CTX, "default", "ods", "t", "default.ods.t", null);
        version(1, CTX, 1, 1, "hive", false, LocalDateTime.now().minusDays(2));
        version(2, CTX, 1, 2, "hive", true, LocalDateTime.now().minusDays(1));

        List<ProjectStats.RecentParse> recent =
                stats.projectStats(CTX, LIMITS).activity().recentParses();
        assertEquals(2, recent.size());
        assertEquals(2, recent.get(0).versionNo());
        assertTrue(recent.get(0).current());
        assertEquals("default.ods.t", recent.get(0).fullName());
        assertEquals(1L, recent.get(0).tableId());
    }

    /** 两张清单：元数据独有的表没有血缘 id，孤立表有。 */
    @Test
    public void listsCarryWhatThePageNeedsToLinkTo() {
        metaTable(1, CTX, "default", "ods", "never", "default.ods.never", "还没解析过");
        table(1, CTX, "default", "ods", "lonely", "default.ods.lonely", "孤零零");

        ProjectStats.Lists lists = stats.projectStats(CTX, LIMITS).lists();
        assertEquals(1, lists.metaOnlyTables().size());
        assertEquals("default.ods.never", lists.metaOnlyTables().get(0).fullName());
        assertNull("元数据独有的表在血缘侧不存在，不能给一个假 id 骗页面去跳",
                lists.metaOnlyTables().get(0).id());

        assertEquals(1, lists.isolatedTables().size());
        assertEquals(Long.valueOf(1), lists.isolatedTables().get(0).id());
        assertEquals("孤立表的中文名取血缘侧自己的列，不靠关联元数据",
                "孤零零", lists.isolatedTables().get(0).comment());
    }

    /** 清单取满 listLimit 就停 —— 前端据此显示截断提示。 */
    @Test
    public void listsAreTruncatedToTheLimit() {
        StatsLimits narrow = new StatsLimits(StatsLimits.TREND_DAYS, 10, 2, 5, 8);
        for (int i = 1; i <= 5; i++) {
            table(i, CTX, "default", "ods", "t" + i, "default.ods.t" + i, null);
        }
        assertEquals(2, stats.projectStats(CTX, narrow).lists().isolatedTables().size());
        assertEquals("计数不受清单条数上限影响", 5,
                stats.projectStats(CTX, narrow).quality().isolatedTables());
    }

    /**
     * 同步任务的 target 由服务端拼好。
     *
     * <p>三个来源字段哪个有值取决于 scope（Gravitino 用 catalog、dbx 用 database），
     * 这个分支判断跟着 {@code SyncScope} 走，放到展示层就得两边一起改。
     */
    @Test
    public void syncFailuresCarryAPrebuiltTarget() {
        syncJob(1, CTX, "SCHEMA", "SUCCESS", "hive_prod", null, "ods", 0, null,
                LocalDateTime.now().minusDays(1));
        syncJob(2, CTX, "SCHEMA", "FAILED", "hive_prod", null, "ods", 3, "connection refused",
                LocalDateTime.now().minusDays(1));
        syncJob(3, CTX, "CATALOG", "PARTIAL", "hive_prod", null, null, 1, null,
                LocalDateTime.now().minusDays(2));
        // 窗口之外：卡片上写着「近 30 天」，底下就不该列半年前的失败
        syncJob(4, CTX, "SCHEMA", "FAILED", "hive_prod", null, "dim", 9, "old",
                LocalDateTime.now().minusDays(90));

        ProjectStats.Sync sync = stats.projectStats(CTX, LIMITS).sync();
        assertEquals(StatsLimits.TREND_DAYS, sync.days());
        assertEquals("只列出现过的状态，不补零 —— 补哪几种是展示层的事", 3, sync.byStatus().size());

        assertEquals(2, sync.recentFailures().size());
        ProjectStats.SyncFailure first = sync.recentFailures().get(0);
        assertEquals("FAILED", first.status());
        assertEquals("hive_prod.ods", first.target());
        assertEquals(3, first.failedCnt());
        assertEquals("connection refused", first.message());
        assertEquals("整目录导入没有更细的层级可写", "hive_prod",
                sync.recentFailures().get(1).target());
    }

    /** 数据目录与临时表规则都是「配了多少条」，停用的规则也算。 */
    @Test
    public void catalogAndRuleCountsIncludeDisabledOnes() {
        jdbc.update("insert into data_catalog(tenant_id,project_id,name,is_default) values(?,?,?,1)",
                CTX.tenantId(), CTX.projectId(), "default");
        jdbc.update("insert into temp_rule(tenant_id,project_id,catalog_name,target,match_type,"
                        + "pattern,enabled) values(?,?,?,'TABLE','GLOB','tmp_*',1)",
                CTX.tenantId(), CTX.projectId(), "default");
        jdbc.update("insert into temp_rule(tenant_id,project_id,catalog_name,target,match_type,"
                        + "pattern,enabled) values(?,?,?,'TABLE','GLOB','stg_*',0)",
                CTX.tenantId(), CTX.projectId(), "default");

        ProjectStats.Scale scale = stats.projectStats(CTX, LIMITS).scale();
        assertEquals(1, scale.dataCatalogs());
        assertEquals("停用的规则也是配过的东西", 2, scale.tempRules());
    }

    // ==================================================================
    // 租户口径
    // ==================================================================

    /** 空租户：项目列表为空，汇总全 0，趋势仍是满窗口。 */
    @Test
    public void emptyTenantYieldsZerosNotNulls() {
        TenantStats s = stats.tenantStats(CTX, LIMITS);

        assertEquals(0, s.overview().projects());
        assertEquals(0, s.overview().enabledProjects());
        assertEquals(0, s.overview().disabledProjects());
        assertEquals(0, s.overview().emptyProjects());
        assertEquals(0, s.scale().lineageTables());
        assertEquals(0, s.scale().currentEdges());
        assertEquals(0, s.scale().metaColumns());
        assertEquals(0, s.sources().total());
        assertEquals(0, s.sources().enabled());
        assertTrue(s.sources().byType().isEmpty());
        assertEquals(0, s.activity().parses7d());
        assertEquals(0, s.activity().parses30d());
        assertNull(s.activity().lastParseAt());
        assertTrue(s.projects().isEmpty());

        assertFullWindow(s.activity().trend());
    }

    /**
     * 租户口径与项目口径的区别就在这一条：<b>同租户下另一个项目的数据必须被算进来</b>。
     */
    @Test
    public void tenantStatsSpanEveryProjectOfTheTenant() {
        project(1, 1, "default", "默认项目", 1);
        project(2, 1, "sales", "销售域", 1);
        project(9, 2, "other", "别家的项目", 1);

        table(1, CTX, "default", "ods", "a", "default.ods.a", null);
        column(1, CTX, 1, "id", "default.ods.a.id");
        metaTable(1, CTX, "default", "ods", "a", "default.ods.a", null);

        table(2, SIBLING, "default", "ods", "b", "default.ods.b", null);
        jdbc.update("update lineage_table set is_temp = 1 where id = 2");
        column(2, SIBLING, 2, "id", "default.ods.b.id");
        metaColumn(1, SIBLING, 2, "id", "default.ods.b.id");

        table(3, OTHER_TENANT, "default", "ods", "c", "default.ods.c", null);

        TenantStats s = stats.tenantStats(CTX, LIMITS);
        assertEquals(2, s.overview().projects());
        assertEquals("两个项目的血缘表要加起来，别的租户的不算", 2, s.scale().lineageTables());
        assertEquals(1, s.scale().tempTables());
        assertEquals(2, s.scale().lineageColumns());
        assertEquals(1, s.scale().metaTables());
        assertEquals(1, s.scale().metaColumns());

        assertEquals("两个项目都要在列", Set.of("默认项目", "销售域"),
                s.projects().stream().map(TenantStats.ProjectSummary::name)
                        .collect(Collectors.toSet()));
    }

    /**
     * 项目按 name 升序返回。
     *
     * <p>用 ASCII 名字断言：中文的排序结果取决于库的排序规则（H2 / MySQL / PostgreSQL
     * 各不相同），拿中文钉顺序钉的其实是某一家的 collation，换个后端就红。
     */
    @Test
    public void tenantProjectsAreOrderedByName() {
        project(1, 1, "c", "charlie", 1);
        project(2, 1, "a", "alpha", 1);
        project(3, 1, "b", "bravo", 1);

        assertEquals(List.of("alpha", "bravo", "charlie"),
                stats.tenantStats(CTX, LIMITS).projects().stream()
                        .map(TenantStats.ProjectSummary::name).toList());
    }

    /**
     * 空项目必须留在对比表里。
     *
     * <p>以血缘表为驱动做聚合，一条血缘都没有的项目会直接从表里消失 ——
     * 而空项目恰恰是这张表最该暴露的东西。
     */
    @Test
    public void emptyProjectsStayInTheComparisonTable() {
        project(1, 1, "default", "默认项目", 1);
        project(2, 1, "empty", "空项目", 0);
        table(1, CTX, "default", "ods", "a", "default.ods.a", null);

        TenantStats s = stats.tenantStats(CTX, LIMITS);
        assertEquals(2, s.overview().projects());
        assertEquals(1, s.overview().enabledProjects());
        assertEquals(1, s.overview().disabledProjects());
        assertEquals(1, s.overview().emptyProjects());

        TenantStats.ProjectSummary empty = s.projects().stream()
                .filter(p -> p.projectId() == 2).findFirst().orElseThrow();
        assertEquals("空项目的每一格都是 0，不是缺行", 0, empty.lineageTables());
        assertEquals(0, empty.versions());
        assertEquals(0, empty.parses30d());
        assertNull(empty.lastParseAt());
        assertFalse("status = 0 要映射成 enabled = false", empty.enabled());
    }

    /** 各项目的近 30 天解析与全租户趋势出自同一批行，加起来必须相等。 */
    @Test
    public void tenantActivityAggregatesThePerProjectNumbers() {
        project(1, 1, "default", "默认项目", 1);
        project(2, 1, "sales", "销售域", 1);
        table(1, CTX, "default", "ods", "a", "default.ods.a", null);
        table(2, SIBLING, "default", "ods", "b", "default.ods.b", null);
        version(1, CTX, 1, 1, "hive", true, LocalDate.now().atStartOfDay());
        version(2, SIBLING, 2, 1, "spark", true, LocalDate.now().minusDays(5).atTime(9, 0));
        version(3, SIBLING, 2, 2, "spark", false, LocalDate.now().minusDays(45).atTime(9, 0));

        TenantStats s = stats.tenantStats(CTX, LIMITS);
        assertEquals(3, s.scale().versions());
        assertEquals("45 天前那次不在窗口里", 2, s.activity().parses30d());
        assertEquals("今天与 5 天前都落在近 7 天里", 2, s.activity().parses7d());
        assertEquals(s.projects().stream()
                        .mapToInt(TenantStats.ProjectSummary::parses30d).sum(),
                s.activity().parses30d());
        assertNotNull(s.activity().lastParseAt());
        assertEquals(LocalDate.now(), s.activity().lastParseAt().toLocalDate());

        int trendTotal = s.activity().trend().stream().mapToInt(DayCount::count).sum();
        assertEquals("趋势之和与近 30 天必须是同一个数", 2, trendTotal);
    }

    /**
     * 元数据服务是租户级配置，按类型分组时还要给出「其中启用了几个」。
     *
     * <p>「配了 2 个 Gravitino，其中 0 个启用」与「配了 2 个都在用」是完全不同的两件事。
     */
    @Test
    public void metadataSourcesAreGroupedByTypeWithEnabledBreakdown() {
        source(1, "gv-a", "GRAVITINO", true);
        source(1, "gv-b", "GRAVITINO", false);
        source(1, "dbx", "DBX", true);
        source(2, "theirs", "DBX", true);

        TenantStats.Sources sources = stats.tenantStats(CTX, LIMITS).sources();
        assertEquals("别的租户的服务不能算进来", 3, sources.total());
        assertEquals(2, sources.enabled());

        TenantStats.SourceTypeCount gravitino = sources.byType().stream()
                .filter(t -> "GRAVITINO".equals(t.name())).findFirst().orElseThrow();
        assertEquals(2, gravitino.count());
        assertEquals(1, gravitino.enabled());
    }

    /** 租户口径忽略上下文里的项目 id：换个项目问，答案必须一模一样。 */
    @Test
    public void tenantStatsIgnoreTheProjectInContext() {
        project(1, 1, "default", "默认项目", 1);
        project(2, 1, "sales", "销售域", 1);
        table(1, CTX, "default", "ods", "a", "default.ods.a", null);

        assertEquals(stats.tenantStats(CTX, LIMITS), stats.tenantStats(SIBLING, LIMITS));
    }

    // ------------------------------------------------------------------
    // 断言与造数
    // ------------------------------------------------------------------

    /** 趋势恒为 TREND_DAYS 条、按日期升序、最后一桶是今天。 */
    private static List<DayCount> assertFullWindow(List<DayCount> trend) {
        assertEquals("趋势恒为 TREND_DAYS 条，缺口不能省略",
                StatsLimits.TREND_DAYS, trend.size());
        assertEquals("最后一桶是今天", LocalDate.now(), trend.get(trend.size() - 1).day());
        for (int i = 1; i < trend.size(); i++) {
            assertTrue("按日期升序（旧 → 新）",
                    trend.get(i - 1).day().isBefore(trend.get(i).day()));
        }
        return trend;
    }

    private void table(long id, LineageContext ctx, String catalog, String schema,
                       String name, String fullName, String comment) {
        jdbc.update("insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                        + "table_name,full_name,comment) values(?,?,?,?,?,?,?,?)",
                id, ctx.tenantId(), ctx.projectId(), catalog, schema, name, fullName, comment);
    }

    private void column(long id, LineageContext ctx, long tableId, String name, String fullName) {
        jdbc.update("insert into lineage_column(id,tenant_id,project_id,table_id,column_name,"
                        + "full_name) values(?,?,?,?,?,?)",
                id, ctx.tenantId(), ctx.projectId(), tableId, name, fullName);
    }

    private void metaTable(long id, LineageContext ctx, String catalog, String schema,
                           String name, String fullName, String comment) {
        jdbc.update("insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,"
                        + "table_name,full_name,comment,source) values(?,?,?,?,?,?,?,?,'DDL')",
                id, ctx.tenantId(), ctx.projectId(), catalog, schema, name, fullName, comment);
    }

    private void metaColumn(long id, LineageContext ctx, long tableId, String name,
                            String fullName) {
        metaColumn(id, ctx, tableId, name, fullName, null);
    }

    private void metaColumn(long id, LineageContext ctx, long tableId, String name,
                            String fullName, String comment) {
        jdbc.update("insert into meta_column(id,tenant_id,project_id,table_id,column_name,"
                        + "full_name,comment,source) values(?,?,?,?,?,?,?,'DDL')",
                id, ctx.tenantId(), ctx.projectId(), tableId, name, fullName, comment);
    }

    private void version(long id, LineageContext ctx, long targetTableId, int versionNo,
                         String dbType, boolean current, LocalDateTime createdAt) {
        jdbc.update("insert into lineage_version(id,tenant_id,project_id,target_table_id,"
                        + "version_no,db_type,sql_hash,is_current,created_at) "
                        + "values(?,?,?,?,?,?,?,?,?)",
                id, ctx.tenantId(), ctx.projectId(), targetTableId, versionNo, dbType,
                "h" + id, current ? 1 : 0, Timestamp.valueOf(createdAt));
    }

    private void edge(LineageContext ctx, long versionId, long targetColId, long sourceColId) {
        jdbc.update("insert into lineage_edge(tenant_id,project_id,version_id,target_col_id,"
                        + "source_col_id) values(?,?,?,?,?)",
                ctx.tenantId(), ctx.projectId(), versionId, targetColId, sourceColId);
    }

    private void project(long id, long tenantId, String code, String name, int status) {
        jdbc.update("insert into project(id,tenant_id,code,name,status) values(?,?,?,?,?)",
                id, tenantId, code, name, status);
    }

    private void source(long tenantId, String name, String type, boolean enabled) {
        jdbc.update("insert into metadata_source(tenant_id,name,type,base_url,priority,enabled) "
                        + "values(?,?,?,'http://localhost',100,?)",
                tenantId, name, type, enabled ? 1 : 0);
    }

    private void syncJob(long id, LineageContext ctx, String scope, String status,
                         String sourceCatalog, String sourceDatabase, String sourceSchema,
                         int failedCnt, String message, LocalDateTime createdAt) {
        jdbc.update("insert into sync_job(id,tenant_id,project_id,source_id,scope,source_catalog,"
                        + "source_database,source_schema,status,failed_cnt,message,created_at) "
                        + "values(?,?,?,1,?,?,?,?,?,?,?,?)",
                id, ctx.tenantId(), ctx.projectId(), scope, sourceCatalog, sourceDatabase,
                sourceSchema, status, failedCnt, message, Timestamp.valueOf(createdAt));
    }
}
