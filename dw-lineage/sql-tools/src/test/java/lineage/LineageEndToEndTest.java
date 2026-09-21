package lineage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.service.impl.LineageServiceImpl;
import com.dwai.lineage.service.SqlParseExecutor;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 端到端血缘解析：SQL 文本 -> superior-sql-parser -> sqlflow -> SQLLineageMerger。
 *
 * <p>不启动 Spring 容器，直接组装 service，避免依赖外部 Gravitino 服务。
 */
public class LineageEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LineageServiceImpl service = newService();

    private static LineageServiceImpl newService() {
        LineageServiceImpl s = new LineageServiceImpl(new MetadataServiceFactory(), new SqlParseExecutor());
        s.init();
        return s;
    }

    @Test
    public void supportedDatabasesAreExposed() {
        assertTrue(service.getSupportedDatabases().contains("hive"));
        assertTrue(service.getSupportedDatabases().contains("spark"));
    }

    /** 建表 + 插入，用 DDL 作为元数据来源，应解析出字段级血缘。 */
    @Test
    public void insertFromDdlDefinedTableProducesColumnLineage() {
        String sql = """
                create table ods.users(id int, name string, age int);
                create table dws.user_stat(user_id int, user_name string);
                insert into dws.user_stat select id, name from ods.users;
                """;

        Lineage lineage = analyze("hive", true, null, sql);

        assertEquals("ods.users.id", lineage.singleSourceOf("dws.user_stat.user_id"));
        assertEquals("ods.users.name", lineage.singleSourceOf("dws.user_stat.user_name"));
        assertEquals(0, lineage.levelOf("dws.user_stat.user_id"));
        assertEquals(1, lineage.levelOf("ods.users.id"));
    }

    /** select * 需要靠 DDL 展开成具体列。 */
    @Test
    public void selectStarIsExpandedFromDdl() {
        String sql = """
                create table ods.src(a int, b int);
                create table dws.dst(a int, b int);
                insert into dws.dst select * from ods.src;
                """;

        Lineage lineage = analyze("hive", true, null, sql);

        assertEquals("ods.src.a", lineage.singleSourceOf("dws.dst.a"));
        assertEquals("ods.src.b", lineage.singleSourceOf("dws.dst.b"));
    }

    /** 多级链路：ods -> dwd -> dws，层级应递增。 */
    @Test
    public void multiHopLineageHasIncreasingLevels() {
        String sql = """
                create table ods.o(c int);
                create table dwd.d(c int);
                create table dws.s(c int);
                insert into dwd.d select c from ods.o;
                insert into dws.s select c from dwd.d;
                """;

        Lineage lineage = analyze("hive", true, null, sql);

        assertEquals(0, lineage.levelOf("dws.s.c"));
        assertEquals(1, lineage.levelOf("dwd.d.c"));
        assertEquals(2, lineage.levelOf("ods.o.c"));
    }

    /**
     * 同一目标表被两条 insert 写入（UNION 拆分场景）：两个上游都要保留。
     * 这是修复前会丢失血缘的核心场景。
     */
    @Test
    public void twoInsertsIntoSameTableKeepBothSources() {
        String sql = """
                create table ods.a(c int);
                create table ods.b(c int);
                create table dws.t(c int);
                insert into dws.t select c from ods.a;
                insert into dws.t select c from ods.b;
                """;

        Lineage lineage = analyze("hive", true, null, sql);

        assertEquals("两条 insert 的上游都应保留",
                Set.of("ods.a.c", "ods.b.c"), lineage.sourcesOf("dws.t.c"));
    }

    /** join 场景：目标列分别来自两张表。 */
    @Test
    public void joinLineageResolvesPerColumnSources() {
        String sql = """
                create table ods.u(id int, name string);
                create table ods.o(uid int, amt int);
                create table dws.uo(id int, name string, amt int);
                insert into dws.uo
                select u.id, u.name, o.amt from ods.u u join ods.o o on u.id = o.uid;
                """;

        Lineage lineage = analyze("hive", true, null, sql);

        assertEquals("ods.u.id", lineage.singleSourceOf("dws.uo.id"));
        assertEquals("ods.u.name", lineage.singleSourceOf("dws.uo.name"));
        assertEquals("ods.o.amt", lineage.singleSourceOf("dws.uo.amt"));
    }

    /** 聚合表达式的上游应指向参与计算的列。 */
    @Test
    public void aggregateExpressionTracksSourceColumns() {
        String sql = """
                create table ods.sale(shop string, price int, cnt int);
                create table dws.shop_sum(shop string, total int);
                insert into dws.shop_sum
                select shop, sum(price * cnt) from ods.sale group by shop;
                """;

        Lineage lineage = analyze("hive", true, null, sql);

        assertEquals("ods.sale.shop", lineage.singleSourceOf("dws.shop_sum.shop"));
        assertEquals("表达式中引用的列都应成为上游",
                Set.of("ods.sale.price", "ods.sale.cnt"), lineage.sourcesOf("dws.shop_sum.total"));
    }

    /** 列过滤：只返回指定列相关的血缘。 */
    @Test
    public void columnFilterNarrowsResult() {
        String sql = """
                create table ods.s(a int, b int);
                create table dws.t(a int, b int);
                insert into dws.t select a, b from ods.s;
                """;

        Lineage lineage = analyze("hive", true, "a", sql);

        assertTrue(lineage.contains("dws.t.a"));
        assertFalse("未被过滤命中的列不应出现", lineage.contains("dws.t.b"));
    }

    /**
     * 脚本里夹杂 set / drop 等非 DML 语句时，应被正常跳过而不影响血缘解析。
     * sqlflow 的语法不认 `set k=v`，必须由 superior-sql-parser 先拆分过滤。
     */
    @Test
    public void sessionSettingsAndDdlAreSkipped() {
        String sql = """
                set hive.exec.orc.split.strategy=BI;
                set hive.exec.dynamic.partition=true;
                drop table if exists dws.t;
                create table ods.s(a int, b int);
                create table dws.t(a int, b int);
                insert into dws.t select a, b from ods.s;
                """;

        Lineage lineage = analyze("hive", true, null, sql);

        assertEquals("ods.s.a", lineage.singleSourceOf("dws.t.a"));
        assertEquals("ods.s.b", lineage.singleSourceOf("dws.t.b"));
    }

    /**
     * Doris 的 {@code USE @compute_group} 上游解析器不认，整段会报 not support sql。
     * 切计算组不产出血缘，应跳过后续 INSERT 照常解析。
     */
    @Test
    public void dorisUseComputeGroupDoesNotFailWholeScript() {
        String sql = """
                USE @cg1;
                SET enable_spill = true;
                create table ods.users(id int, name string);
                create table dws.stat(user_id int, user_name string);
                insert into dws.stat select id, name from ods.users;
                """;

        Lineage lineage = analyze("doris", true, null, sql);

        assertEquals("ods.users.id", lineage.singleSourceOf("dws.stat.user_id"));
        assertEquals("ods.users.name", lineage.singleSourceOf("dws.stat.user_name"));
    }

    /** insert overwrite + 静态分区，分区列不应吃掉 select 项。 */
    @Test
    public void insertOverwriteWithStaticPartition() {
        String sql = """
                create table ods.src(a int, b int);
                create table dws.dst(a int, b int) partitioned by (dt string);
                insert overwrite table dws.dst partition(dt='20260822')
                select a, b from ods.src;
                """;

        Lineage lineage = analyze("hive", true, null, sql);

        assertEquals("ods.src.a", lineage.singleSourceOf("dws.dst.a"));
        assertEquals("ods.src.b", lineage.singleSourceOf("dws.dst.b"));
    }

    // ---------------- 部分失败容忍 ----------------

    /**
     * 一条语句因表结构未知而失败时，其余语句的血缘照常产出，
     * 并且失败原因要能回传给用户，而不是只看到一张不完整的图。
     */
    @Test
    public void partialFailureStillReturnsOtherLineageWithDetail() {
        // dws.ok 有 DDL；dws.bad 的来源表 nowhere.missing 没有任何结构信息
        String sql = """
                create table ods.s(a int);
                create table dws.ok(a int);
                insert into dws.ok select a from ods.s;
                insert into dws.bad select * from nowhere.missing;
                """;

        JsonNode root = tree("hive", true, null, sql);
        Lineage lineage = Lineage.of(root);

        assertEquals("正常语句的血缘不应受影响", "ods.s.a", lineage.singleSourceOf("dws.ok.a"));

        boolean reported = root.path("failedStatements").size() > 0
                || root.path("unresolvedTables").size() > 0;
        assertTrue("失败语句或未解析的表必须回传给用户，实际响应:\n" + root.toPrettyString(), reported);
    }

    /** 响应中必须始终带上 failedStatements / unresolvedTables 字段，便于前端统一处理。 */
    @Test
    public void diagnosticFieldsAlwaysPresent() {
        String sql = """
                create table ods.s(a int);
                create table dws.t(a int);
                insert into dws.t select a from ods.s;
                """;

        JsonNode root = tree("hive", true, null, sql);

        assertTrue("应有 failedStatements 字段", root.has("failedStatements"));
        assertTrue("应有 unresolvedTables 字段", root.has("unresolvedTables"));
        assertTrue("应有 warnings 字段", root.has("warnings"));
        assertEquals("全部成功时不应有失败语句", 0, root.path("failedStatements").size());
    }

    /** 失败明细要带上语句序号与原因，方便用户在编辑器里定位。 */
    @Test
    public void failedStatementCarriesIndexAndReason() {
        String sql = """
                insert into dws.bad select * from nowhere.missing;
                """;

        JsonNode root = tree("hive", false, null, sql);
        JsonNode failures = root.path("failedStatements");

        if (failures.size() > 0) {
            JsonNode first = failures.get(0);
            assertTrue("语句序号应从 1 开始", first.path("index").asInt() >= 1);
            assertFalse("应带失败原因", first.path("message").asText().isBlank());
        } else {
            // 表结构未知也可能表现为 unresolvedTables，两者至少有一个要能说明问题
            assertTrue("应至少通过 unresolvedTables 或 warnings 说明原因",
                    root.path("unresolvedTables").size() > 0 || root.path("warnings").size() > 0);
        }
    }

    /** 语法错误应转成 SqlParseException，而不是裸异常穿透。 */
    @Test
    public void invalidSqlRaisesSqlParseException() {
        try {
            service.analyzeSqlLineage("hive", false, null, "this is not sql at all");
            fail("语法错误应抛出 SqlParseException");
        } catch (SqlParseException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /** 不支持的方言应给出明确提示。 */
    @Test
    public void unsupportedDialectRaisesSqlParseException() {
        try {
            service.analyzeSqlLineage("not_a_db", false, null, "select 1");
            fail("不支持的方言应抛出 SqlParseException");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage().contains("不支持") || expected.getMessage().contains("Unsupported"));
        }
    }

    /** 纯 select（无写入目标）不应抛异常，而是返回带告警的空结果。 */
    @Test
    public void selectOnlyReturnsEmptyResultWithWarning() {
        JsonNode root = tree("hive", false, null, "select a from ods.t");
        assertNotNull("不应返回 null", root);
        assertEquals(0, root.path("data").path("withProcessData").path("size").asInt());
        assertTrue("应带告警说明未识别到写入语句", root.path("warnings").size() > 0);
    }

    // ------------------------------------------------------------------

    private Lineage analyze(String dbType, boolean useCreateTable, String columnName, String sql) {
        return Lineage.of(tree(dbType, useCreateTable, columnName, sql));
    }

    /** 调用 service 并把领域对象转成 JsonNode，便于沿用既有断言。 */
    private JsonNode tree(String dbType, boolean useCreateTable, String columnName, String sql) {
        com.dwai.lineage.dto.LineageGraph graph = service.analyzeSqlLineage(dbType, useCreateTable, columnName, sql);
        assertNotNull("解析结果不应为 null", graph);
        return MAPPER.valueToTree(graph);
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("结果不是合法 JSON:\n" + json, e);
        }
    }

    /** withProcessData 的只读视图。 */
    private record Lineage(Map<String, Set<String>> edges, Map<String, Integer> levels, String raw) {

        static Lineage of(JsonNode root) {
            Map<String, Set<String>> edges = new HashMap<>();
            Map<String, Integer> levels = new HashMap<>();
            JsonNode section = root.path("data").path("withProcessData");
            for (JsonNode item : section.path("data")) {
                JsonNode target = item.path("targetField");
                String name = target.path("fieldName").asText();
                levels.put(name, target.path("level").asInt());
                Set<String> sources = new HashSet<>();
                for (JsonNode ref : item.path("refFields")) {
                    sources.add(ref.path("fieldName").asText());
                    levels.put(ref.path("fieldName").asText(), ref.path("level").asInt());
                }
                edges.put(name, sources);
            }
            return new Lineage(edges, levels, root.toPrettyString());
        }

        boolean contains(String field) {
            return levels.containsKey(field);
        }

        Set<String> sourcesOf(String target) {
            assertTrue("结果中缺少目标字段 " + target + "\n实际结果:\n" + raw, edges.containsKey(target));
            return edges.get(target);
        }

        String singleSourceOf(String target) {
            Set<String> sources = sourcesOf(target);
            assertEquals(target + " 应恰好有一个上游，实际: " + sources + "\n" + raw, 1, sources.size());
            return sources.iterator().next();
        }

        int levelOf(String field) {
            assertTrue("结果中缺少字段 " + field + "\n实际结果:\n" + raw, levels.containsKey(field));
            return levels.get(field);
        }
    }
}
