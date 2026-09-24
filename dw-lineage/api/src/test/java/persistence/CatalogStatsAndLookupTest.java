package persistence;

import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.dto.CatalogStats;
import com.dwai.lineage.persistence.JdbcLineageCatalogRepository;
import com.dwai.lineage.persistence.LineageColumnRow;
import com.dwai.lineage.persistence.LineageTableRow;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 概览统计与按全名批量查询。
 *
 * <p>概览统计此前一个用例都没有 —— 它全是聚合 SQL，写错了不会抛异常，
 * 只会在首页上显示一个错的数字，没人能一眼看出来。
 *
 * <p>批量查询是从「全量加载再在 Java 里过滤」重构来的，这里钉住行为不变。
 */
public class CatalogStatsAndLookupTest {

    private static final LineageContext CTX = new LineageContext(1, 1);
    private static final LineageContext OTHER = new LineageContext(2, 2);

    private static DataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcLineageCatalogRepository catalog;

    @Before
    public void setUp() {
        if (dataSource == null) {
            DataSource ds = TestDataSources.h2("catalog_stats_h2");
            TestSchema.apply(ds, "h2");
            dataSource = ds;
        }
        jdbc = new JdbcTemplate(dataSource);
        catalog = new JdbcLineageCatalogRepository(jdbc);
        for (String t : new String[]{"lineage_edge", "lineage_version", "lineage_column",
                "lineage_table", "meta_column", "meta_table"}) {
            jdbc.update("delete from " + t);
        }
    }

    // ==================================================================
    // 概览统计
    // ==================================================================

    /**
     * 「孤立表」清单要能带出中文名。
     *
     * <p>这条钉的是一个真实缺陷：原先中文名是 {@code left join meta_table on full_name}
     * 关联来的，而血缘侧按默认目录补全（{@code default.…}）、元数据挂在源端 catalog 下
     * （{@code hive_prod.…}），首段不同永远匹配不上，于是这一列恒为空。
     * 现在描述就在血缘行上，与目录段无关。
     */
    @Test
    public void isolatedTablesCarryTheirChineseName() {
        // 血缘侧在默认目录，元数据侧在另一个目录 —— 故意让两边的全名对不上
        insertTable(1, CTX, "default", "ods", "orders", "default.ods.orders", "订单主表");
        insertMetaTable(1, CTX, "hive_prod", "ods", "orders", "hive_prod.ods.orders", "元数据里的名字");

        CatalogStats stats = catalog.stats(CTX, 10, 20);

        assertEquals(1, stats.isolatedTables().size());
        assertEquals("孤立表的中文名要取血缘侧自己的列，不能靠关联元数据",
                "订单主表", stats.isolatedTables().get(0).comment());
    }

    /** 临时表不算孤立表 —— 它们本来就会被穿透掉，列出来只是噪音。 */
    @Test
    public void temporaryTablesAreNotCountedAsIsolated() {
        insertTable(1, CTX, "default", "tmp", "bb", "default.tmp.bb", null);
        jdbc.update("update lineage_table set is_temp = 1 where id = 1");

        assertTrue(catalog.stats(CTX, 10, 20).isolatedTables().isEmpty());
    }

    /** 元数据里有、血缘里从没出现过的表。 */
    @Test
    public void metaOnlyTablesAreListed() {
        insertMetaTable(1, CTX, "default", "ods", "never_parsed",
                "default.ods.never_parsed", "还没解析过");
        insertTable(2, CTX, "default", "ods", "parsed", "default.ods.parsed", null);
        insertMetaTable(2, CTX, "default", "ods", "parsed", "default.ods.parsed", null);

        List<CatalogStats.TableBrief> only = catalog.stats(CTX, 10, 20).metaOnlyTables();
        assertEquals(1, only.size());
        assertEquals("default.ods.never_parsed", only.get(0).fullName());
    }

    /** 各项计数不能把别的租户算进来。 */
    @Test
    public void countsAreScopedToTheTenant() {
        insertTable(1, CTX, "default", "ods", "mine", "default.ods.mine", null);
        insertTable(2, OTHER, "default", "ods", "theirs", "default.ods.theirs", null);
        insertMetaTable(1, OTHER, "default", "ods", "theirs", "default.ods.theirs", null);

        CatalogStats stats = catalog.stats(CTX, 10, 20);
        assertEquals(1, stats.tables());
        assertEquals(0, stats.metaTables());
    }

    /** 空库不该抛异常，也不该出现 null 列表。 */
    @Test
    public void emptyProjectYieldsZerosNotNulls() {
        CatalogStats stats = catalog.stats(CTX, 10, 20);
        assertEquals(0, stats.tables());
        assertEquals(0, stats.columns());
        assertEquals(0, stats.edges());
        assertNotNull(stats.recentParses());
        assertNotNull(stats.metaOnlyTables());
        assertNotNull(stats.isolatedTables());
    }

    // ==================================================================
    // 按全名批量查询
    // ==================================================================

    @Test
    public void findTablesByFullNamesReturnsOnlyTheAskedOnes() {
        insertTable(1, CTX, "default", "ods", "a", "default.ods.a", null);
        insertTable(2, CTX, "default", "ods", "b", "default.ods.b", null);
        insertTable(3, CTX, "default", "ods", "c", "default.ods.c", null);

        List<LineageTableRow> rows = catalog.findTablesByFullNames(
                CTX, Set.of("default.ods.c", "default.ods.a", "default.ods.missing"));

        assertEquals("查不到的名字直接跳过，不占位", 2, rows.size());
        assertEquals("结果要按全名升序，与重构前自己 sort 的顺序一致",
                List.of("default.ods.a", "default.ods.c"),
                rows.stream().map(LineageTableRow::fullName).toList());
    }

    @Test
    public void findTablesByFullNamesIsTenantScoped() {
        insertTable(1, OTHER, "default", "ods", "a", "default.ods.a", null);

        assertTrue("不能查到别的租户的表",
                catalog.findTablesByFullNames(CTX, Set.of("default.ods.a")).isEmpty());
    }

    @Test
    public void findColumnsByFullNamesSpansMultipleTables() {
        insertTable(1, CTX, "default", "ods", "a", "default.ods.a", null);
        insertTable(2, CTX, "default", "ods", "b", "default.ods.b", null);
        insertColumn(1, CTX, 1, "id", "default.ods.a.id");
        insertColumn(2, CTX, 1, "name", "default.ods.a.name");
        insertColumn(3, CTX, 2, "id", "default.ods.b.id");

        List<LineageColumnRow> rows = catalog.findColumnsByFullNames(
                CTX, Set.of("default.ods.b.id", "default.ods.a.id"));

        assertEquals("上游字段散落在多张表里，一次要能全查回来",
                List.of("default.ods.a.id", "default.ods.b.id"),
                rows.stream().map(LineageColumnRow::fullName).toList());
    }

    @Test
    public void emptyInputDoesNotHitTheDatabase() {
        assertTrue(catalog.findTablesByFullNames(CTX, Set.of()).isEmpty());
        assertTrue(catalog.findColumnsByFullNames(CTX, null).isEmpty());
    }

    /** 超过 IN 分块大小时结果仍完整、仍有序 —— 分批查回来要整体再排一次。 */
    @Test
    public void resultsStayOrderedAcrossInChunks() {
        int total = 1200;
        for (int i = 0; i < total; i++) {
            String name = String.format("default.ods.t%04d", i);
            insertTable(i + 1, CTX, "default", "ods", "t" + i, name, null);
        }
        Set<String> wanted = new java.util.LinkedHashSet<>();
        for (int i = 0; i < total; i++) {
            wanted.add(String.format("default.ods.t%04d", i));
        }

        List<LineageTableRow> rows = catalog.findTablesByFullNames(CTX, wanted);

        assertEquals(total, rows.size());
        List<String> names = rows.stream().map(LineageTableRow::fullName).toList();
        assertEquals("跨批次的结果整体要有序，否则批次边界处顺序是乱的",
                names.stream().sorted().toList(), names);
    }

    // ------------------------------------------------------------------

    private void insertTable(long id, LineageContext ctx, String catalogName, String schema,
                             String table, String fullName, String comment) {
        jdbc.update("insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                        + "table_name,full_name,comment) values(?,?,?,?,?,?,?,?)",
                id, ctx.tenantId(), ctx.projectId(), catalogName, schema, table, fullName, comment);
    }

    private void insertColumn(long id, LineageContext ctx, long tableId, String name,
                              String fullName) {
        jdbc.update("insert into lineage_column(id,tenant_id,project_id,table_id,column_name,"
                        + "full_name) values(?,?,?,?,?,?)",
                id, ctx.tenantId(), ctx.projectId(), tableId, name, fullName);
    }

    private void insertMetaTable(long id, LineageContext ctx, String catalogName, String schema,
                                 String table, String fullName, String comment) {
        jdbc.update("insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,"
                        + "table_name,full_name,comment,source) values(?,?,?,?,?,?,?,?,'DDL')",
                id, ctx.tenantId(), ctx.projectId(), catalogName, schema, table, fullName, comment);
    }
}
