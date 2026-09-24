package persistence;

import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.persistence.JdbcLineageCatalogRepository;
import com.dwai.lineage.persistence.JdbcLineageRepository;
import com.dwai.lineage.persistence.LineageCatalogRepository;
import com.dwai.lineage.persistence.LineageEdgeRow;
import com.dwai.lineage.persistence.LineageRepository;
import com.dwai.lineage.persistence.LineageTableRow;
import com.dwai.lineage.persistence.LineageVersionRow;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * 血缘持久化的行为契约。
 *
 * <p>H2 / MySQL / PostgreSQL 三种后端<b>共用这一份用例</b>，
 * 子类只负责提供各自的 DataSource 与迁移脚本位置，以此保证三者行为一致。
 *
 * <p>V3 起的模型：表/字段是<b>累积目录</b>，版本挂在<b>目标表</b>上。
 * 下面的固定装置 {@link #graph()} 是一条 {@code cat.ods.s.c -> cat.dwd.d.c -> cat.dws.t.c} 的三级链路，
 * 因此一次保存会产出<b>两个</b>版本（目标表 cat.dwd.d 和 cat.dws.t 各一个）。
 */
public abstract class AbstractLineageRepositoryTest {

    protected static final LineageContext TENANT_A = new LineageContext(1, 10);
    protected static final LineageContext TENANT_B = new LineageContext(2, 20);

    private LineageRepository repository;
    private LineageCatalogRepository catalog;
    private JdbcTemplate jdbc;

    /** 子类提供已完成迁移的数据源；返回 null 表示该后端不可用，用例将被跳过。 */
    protected abstract DataSource dataSource();

    @Before
    public void setUp() {
        DataSource ds = dataSource();
        org.junit.Assume.assumeNotNull("该数据库后端不可用，跳过", ds);
        jdbc = new JdbcTemplate(ds);
        catalog = new JdbcLineageCatalogRepository(jdbc);
        repository = new JdbcLineageRepository(jdbc, catalog);
        // 每个用例从干净数据开始
        for (String t : List.of("lineage_edge", "lineage_column", "lineage_table", "lineage_version")) {
            jdbc.update("delete from " + t);
        }
    }

    // ---------------- 保存与目录 ----------------

    /** 一段 SQL 里有几张目标表，就产出几个版本，各自独立。 */
    @Test
    public void saveCreatesOneVersionPerTargetTable() {
        List<LineageVersionRow> versions = repository.saveVersions(TENANT_A, "hive", SQL, graph());

        assertEquals("cat.dwd.d 与 cat.dws.t 各一个版本", 2, versions.size());
        Set<Long> targetTables = versions.stream()
                .map(LineageVersionRow::targetTableId).collect(Collectors.toSet());
        assertEquals("两个版本应挂在两张不同的目标表上", 2, targetTables.size());
        versions.forEach(v -> assertTrue("新版本应即为当前版本", v.isCurrent()));
    }

    @Test
    public void saveRegistersTablesAndColumnsInCatalog() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());

        Set<String> tables = catalog.listTables(TENANT_A, null, null).stream()
                .map(LineageTableRow::fullName).collect(Collectors.toSet());
        assertEquals(Set.of("cat.ods.s", "cat.dwd.d", "cat.dws.t"), tables);
        assertEquals("三张表各一个字段", 3, count("lineage_column"));
    }

    /**
     * 目录是累积的：解析完 A 再解析 B，A 仍然在目录里。
     *
     * <p>这是 V3 模型改造要解决的核心问题 —— 旧模型里版本是项目级的，
     * 保存 B 会让 A 从「当前版本」中消失。
     */
    @Test
    public void catalogAccumulatesAcrossSaves() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());
        repository.saveVersions(TENANT_A, "hive", OTHER_SQL, otherGraph());

        Set<String> tables = catalog.listTables(TENANT_A, null, null).stream()
                .map(LineageTableRow::fullName).collect(Collectors.toSet());
        assertTrue("先前解析的表必须还在目录里", tables.containsAll(Set.of("cat.ods.s", "cat.dwd.d", "cat.dws.t")));
        assertTrue("新解析的表也应在", tables.containsAll(Set.of("cat.ods.x", "cat.ads.y")));
    }

    /** 同一张表重复出现不应产生重复行。 */
    @Test
    public void catalogUpsertIsIdempotent() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());
        repository.saveVersions(TENANT_A, "hive", SQL, graph());

        assertEquals("表不应重复", 3, count("lineage_table"));
        assertEquals("字段不应重复", 3, count("lineage_column"));
    }

    // ---------------- 版本号 ----------------

    @Test
    public void versionNumberIncrementsPerTargetTable() {
        List<LineageVersionRow> first = repository.saveVersions(TENANT_A, "hive", SQL, graph());
        List<LineageVersionRow> second = repository.saveVersions(TENANT_A, "hive", SQL + " -- v2", graph());

        first.forEach(v -> assertEquals(1, v.versionNo()));
        second.forEach(v -> assertEquals(2, v.versionNo()));
    }

    /** 不同目标表的版本号各自从 1 开始，互不干扰。 */
    @Test
    public void versionNumbersAreIndependentAcrossTargetTables() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());
        // 只更新 cat.dws.t 这一张表的血缘
        List<LineageVersionRow> again = repository.saveVersions(TENANT_A, "hive", OTHER_SQL, otherGraph());

        assertEquals("新目标表 cat.ads.y 的版本号应从 1 开始", 1, again.get(0).versionNo());
    }

    /** 版本号按项目独立递增，不同租户互不影响。 */
    @Test
    public void versionNumbersAreIndependentAcrossTenants() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());
        repository.saveVersions(TENANT_A, "hive", SQL + " -- 2", graph());
        List<LineageVersionRow> bFirst = repository.saveVersions(TENANT_B, "hive", SQL, graph());

        bFirst.forEach(v -> assertEquals("租户 B 的第一个版本仍应是 1", 1, v.versionNo()));
    }

    // ---------------- 跨租户隔离（负向用例，本期硬性要求）----------------

    @Test
    public void tenantCannotSeeAnotherTenantVersions() {
        long targetTableId = repository.saveVersions(TENANT_A, "hive", SQL, graph()).get(0).targetTableId();

        assertEquals("租户 B 不应看到租户 A 的版本",
                0, repository.listVersions(TENANT_B, targetTableId).size());
        assertTrue(repository.currentVersion(TENANT_B, targetTableId).isEmpty());
    }

    @Test
    public void tenantCannotTraverseAnotherTenantLineage() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());

        List<LineageEdgeRow> asOwner = repository.upstream(TENANT_A, "cat.dws.t.c", 5, null);
        List<LineageEdgeRow> asOther = repository.upstream(TENANT_B, "cat.dws.t.c", 5, null);

        assertFalse("租户 A 应能查到自己的血缘", asOwner.isEmpty());
        assertTrue("租户 B 不应查到任何数据", asOther.isEmpty());
    }

    @Test
    public void tenantCannotDeleteAnotherTenantVersion() {
        LineageVersionRow v = repository.saveVersions(TENANT_A, "hive", SQL, graph()).get(0);

        assertFalse("跨租户删除应失败", repository.deleteVersion(TENANT_B, v.id()));
        assertEquals("租户 A 的版本应完好",
                1, repository.listVersions(TENANT_A, v.targetTableId()).size());
    }

    @Test
    public void tenantCannotFindAnotherTenantBySqlHash() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());
        String hash = JdbcLineageRepository.sha256(SQL);

        assertFalse(repository.findBySqlHash(TENANT_A, hash).isEmpty());
        assertTrue("跨租户按哈希也查不到", repository.findBySqlHash(TENANT_B, hash).isEmpty());
    }

    @Test
    public void tenantCannotSeeAnotherTenantCatalog() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());

        assertTrue("租户 B 不应看到租户 A 的表目录", catalog.listTables(TENANT_B, null, null).isEmpty());
        assertTrue(catalog.listSchemas(TENANT_B).isEmpty());
    }

    // ---------------- 版本切换与删除 ----------------

    /**
     * current 的互斥范围是<b>同一目标表</b>，不是整个项目。
     *
     * <p>否则把 cat.dws.t 的旧版本设为当前，会把 cat.dwd.d 的当前版本一起清掉。
     */
    @Test
    public void markCurrentIsExclusiveWithinTargetTableOnly() {
        List<LineageVersionRow> v1 = repository.saveVersions(TENANT_A, "hive", SQL, graph());
        repository.saveVersions(TENANT_A, "hive", SQL + " -- 2", graph());

        LineageVersionRow firstOfTargetA = v1.get(0);
        repository.markCurrent(TENANT_A, firstOfTargetA.id());

        List<LineageVersionRow> current = repository.listVersions(TENANT_A, firstOfTargetA.targetTableId())
                .stream().filter(LineageVersionRow::isCurrent).toList();
        assertEquals("同一目标表内当前版本至多一条", 1, current.size());
        assertEquals(firstOfTargetA.id(), current.get(0).id());

        // 另一张目标表不受影响，仍应有自己的当前版本
        long otherTargetTableId = v1.get(1).targetTableId();
        assertTrue("另一张目标表应仍有当前版本",
                repository.currentVersion(TENANT_A, otherTargetTableId)
                        .map(LineageVersionRow::isCurrent).orElse(false));
        assertNotEquals(firstOfTargetA.targetTableId(), otherTargetTableId);
    }

    @Test
    public void currentVersionDefaultsToLatestWhenNoneMarked() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());
        List<LineageVersionRow> second = repository.saveVersions(TENANT_A, "hive", SQL + " -- 2", graph());
        long targetTableId = second.get(0).targetTableId();

        // 清掉所有 current 标记，模拟历史数据
        jdbc.update("update lineage_version set is_current = 0");

        Optional<LineageVersionRow> current = repository.currentVersion(TENANT_A, targetTableId);
        assertTrue(current.isPresent());
        assertEquals("没有显式当前版本时应退回最新版本", 2, current.get().versionNo());
    }

    /** 删版本只删边；目录里的表与字段要留着，它们可能还被别的版本引用。 */
    @Test
    public void deleteVersionRemovesEdgesButKeepsCatalog() {
        List<LineageVersionRow> versions = repository.saveVersions(TENANT_A, "hive", SQL, graph());

        for (LineageVersionRow v : versions) {
            assertTrue(repository.deleteVersion(TENANT_A, v.id()));
        }

        assertEquals("边应被删除", 0, count("lineage_edge"));
        assertEquals("表目录应保留", 3, count("lineage_table"));
        assertEquals("字段目录应保留", 3, count("lineage_column"));
    }

    /** 删除当前版本后，应自动回退到该表剩余的最新版本，不能出现「没有当前版本」。 */
    @Test
    public void deletingCurrentVersionFallsBackToLatestRemaining() {
        LineageVersionRow v1 = repository.saveVersions(TENANT_A, "hive", SQL, graph()).get(0);
        List<LineageVersionRow> second = repository.saveVersions(TENANT_A, "hive", SQL + " -- 2", graph());
        LineageVersionRow v2 = second.stream()
                .filter(v -> v.targetTableId() == v1.targetTableId()).findFirst().orElseThrow();

        repository.deleteVersion(TENANT_A, v2.id());

        Optional<LineageVersionRow> current = repository.currentVersion(TENANT_A, v1.targetTableId());
        assertTrue(current.isPresent());
        assertEquals(v1.id(), current.get().id());
        assertTrue("应被显式标记为当前", current.get().isCurrent());
    }

    // ---------------- 图遍历 ----------------

    @Test
    public void upstreamWalksTheWholeChain() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());

        Set<String> sources = repository.upstream(TENANT_A, "cat.dws.t.c", 5, null).stream()
                .map(LineageEdgeRow::sourceField).collect(Collectors.toSet());

        assertEquals("应追溯到 cat.dwd.d.c 与 cat.ods.s.c", Set.of("cat.dwd.d.c", "cat.ods.s.c"), sources);
    }

    @Test
    public void downstreamFindsImpactedFields() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());

        Set<String> targets = repository.downstream(TENANT_A, "cat.ods.s.c", 5, null).stream()
                .map(LineageEdgeRow::targetField).collect(Collectors.toSet());

        assertEquals("改 cat.ods.s.c 会影响 cat.dwd.d.c 与 cat.dws.t.c",
                Set.of("cat.dwd.d.c", "cat.dws.t.c"), targets);
    }

    /** depth 限制应生效，只走一跳。 */
    @Test
    public void depthLimitIsRespected() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());

        Set<String> oneHop = repository.upstream(TENANT_A, "cat.dws.t.c", 1, null).stream()
                .map(LineageEdgeRow::sourceField).collect(Collectors.toSet());

        assertTrue("一跳应包含 cat.dwd.d.c", oneHop.contains("cat.dwd.d.c"));
        assertFalse("一跳不应到达 cat.ods.s.c", oneHop.contains("cat.ods.s.c"));
    }

    /**
     * 遍历只走当前版本的边：某张表的血缘被更新后，旧版本的边不应再出现在图里。
     *
     * <p>这就是「后面的操作覆盖前面的」在查询侧的体现。
     */
    @Test
    public void traversalFollowsCurrentVersionOnly() {
        repository.saveVersions(TENANT_A, "hive", SQL, graph());
        // 重新解析 cat.dws.t：它的上游从 cat.dwd.d.c 改成了 cat.ods.z.c
        repository.saveVersions(TENANT_A, "hive", REWIRED_SQL, rewiredGraph());

        Set<String> sources = repository.upstream(TENANT_A, "cat.dws.t.c", 5, null).stream()
                .map(LineageEdgeRow::sourceField).collect(Collectors.toSet());

        assertEquals("应只看到新版本的上游", Set.of("cat.ods.z.c"), sources);
    }

    /** 指定历史版本时，起点表改用该版本的边。 */
    @Test
    public void traversalCanPinRootToAHistoricalVersion() {
        List<LineageVersionRow> first = repository.saveVersions(TENANT_A, "hive", SQL, graph());
        long dwsTableId = first.stream()
                .filter(v -> tableFullNameOf(v.targetTableId()).equals("cat.dws.t"))
                .findFirst().orElseThrow().targetTableId();
        long oldVersionId = first.stream()
                .filter(v -> v.targetTableId() == dwsTableId).findFirst().orElseThrow().id();

        repository.saveVersions(TENANT_A, "hive", REWIRED_SQL, rewiredGraph());

        Set<String> pinned = repository.upstream(TENANT_A, "cat.dws.t.c", 5, oldVersionId).stream()
                .map(LineageEdgeRow::sourceField).collect(Collectors.toSet());

        assertTrue("钉到旧版本后应看到旧上游 cat.dwd.d.c", pinned.contains("cat.dwd.d.c"));
    }

    // ------------------------------------------------------------------

    private String tableFullNameOf(long tableId) {
        return catalog.findTableById(TENANT_A, tableId).map(LineageTableRow::fullName).orElseThrow();
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private static final String SQL =
            "insert into cat.dwd.d select c from cat.ods.s; insert into cat.dws.t select c from cat.dwd.d;";

    private static final String OTHER_SQL = "insert into cat.ads.y select c from cat.ods.x;";

    private static final String REWIRED_SQL = "insert into cat.dws.t select c from cat.ods.z;";

    /** 构造一条 cat.ods.s.c -> cat.dwd.d.c -> cat.dws.t.c 的三级链路（两张目标表）。 */
    private static LineageGraph graph() {
        LineageGraph.Field tTc = field("cat.dws.t.c", false, 0);
        LineageGraph.Field dDc = field("cat.dwd.d.c", false, 1);
        LineageGraph.Field oSc = field("cat.ods.s.c", true, 2);

        LineageGraph.Section with = new LineageGraph.Section(List.of(
                new LineageGraph.Item(tTc, List.of(dDc)),
                new LineageGraph.Item(dDc, List.of(oSc))), 2, 2);
        LineageGraph.Section no = new LineageGraph.Section(List.of(
                new LineageGraph.Item(tTc, List.of(oSc))), 1, 0);

        return new LineageGraph(0, new LineageGraph.Data(with, no),
                List.of(), List.of(), List.of(), "", "test");
    }

    /** 另一条互不相干的链路：cat.ods.x.c -> cat.ads.y.c。 */
    private static LineageGraph otherGraph() {
        LineageGraph.Field target = field("cat.ads.y.c", false, 0);
        LineageGraph.Field source = field("cat.ods.x.c", true, 1);

        LineageGraph.Section section = new LineageGraph.Section(List.of(
                new LineageGraph.Item(target, List.of(source))), 1, 1);
        return new LineageGraph(0, new LineageGraph.Data(section, section),
                List.of(), List.of(), List.of(), "", "test");
    }

    /** cat.dws.t 的上游被改成 cat.ods.z，用于验证「新版本覆盖旧版本」。 */
    private static LineageGraph rewiredGraph() {
        LineageGraph.Field target = field("cat.dws.t.c", false, 0);
        LineageGraph.Field source = field("cat.ods.z.c", true, 1);

        LineageGraph.Section section = new LineageGraph.Section(List.of(
                new LineageGraph.Item(target, List.of(source))), 1, 1);
        return new LineageGraph(0, new LineageGraph.Data(section, section),
                List.of(), List.of(), List.of(), "", "test");
    }

    private static LineageGraph.Field field(String name, boolean isFinal, int level) {
        return new LineageGraph.Field(name, isFinal, 0, level, null);
    }
}
