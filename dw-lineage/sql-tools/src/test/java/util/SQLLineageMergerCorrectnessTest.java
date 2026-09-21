package util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.Test;
import com.dwai.lineage.util.SQLLineageMerger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * SQLLineageMerger 正确性回归测试。
 *
 * <p>覆盖第一期止血修复的四类缺陷：
 * <ul>
 *   <li>多语句写同一目标字段时血缘被覆盖</li>
 *   <li>字段名解析把 schema 当成表名，导致同层 index 失去区分度</li>
 *   <li>层级计算（最长路径）在菱形依赖下不收敛</li>
 *   <li>环依赖无检测</li>
 * </ul>
 */
public class SQLLineageMergerCorrectnessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 基线：单条链路 stg.a.c1 -> tmp.b.c1 -> dws.c.c1，层级应为 0 / 1 / 2。 */
    @Test
    public void simpleChain() {
        String s1 = lineage("dws", "c", col("c1", "tmp.b.c1"));
        String s2 = lineage("tmp", "b", col("c1", "stg.a.c1"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s1, s2)));

        assertEquals(0, g.level("dws.c.c1"));
        assertEquals(1, g.level("tmp.b.c1"));
        assertEquals(2, g.level("stg.a.c1"));
        assertEquals("最深层级应为 2", 2, g.maxLevel);
        // 链路末端才是 final
        assertTrue("stg.a.c1 无上游，应标记 final", g.isFinal("stg.a.c1"));
        assertFalse("tmp.b.c1 有上游，不应标记 final", g.isFinal("tmp.b.c1"));
    }

    /**
     * 缺陷 1：两条语句写同一张目标表的同一字段（典型场景：UNION 被拆成多条 insert、
     * 分区表按天多次 insert overwrite）。两条语句的上游都必须保留。
     */
    @Test
    public void multipleStatementsWritingSameTargetColumn() {
        String s1 = lineage("dws", "t", col("c1", "ods.a.x"));
        String s2 = lineage("dws", "t", col("c1", "ods.b.y"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s1, s2)));

        assertEquals("两条语句的上游都应保留，不能后者覆盖前者",
                Set.of("ods.a.x", "ods.b.y"), g.refsOf("dws.t.c1"));
    }

    /** 同一目标字段被重复声明相同上游时，应去重而不是产生重复边。 */
    @Test
    public void duplicateSourcesAreDeduplicated() {
        String s1 = lineage("dws", "t", col("c1", "ods.a.x"));
        String s2 = lineage("dws", "t", col("c1", "ods.a.x"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s1, s2)));

        assertEquals(Set.of("ods.a.x"), g.refsOf("dws.t.c1"));
        assertEquals("重复上游应被去重", 1, g.refCount("dws.t.c1"));
    }

    /**
     * 缺陷 2：同一 level、同一 schema 下的不同表，index 必须能相互区分。
     * 原实现用 split(".", 2)[0] 取到的是 schema，导致 ods.a 与 ods.b 的 index 同为 0。
     */
    @Test
    public void tablesInSameSchemaGetDistinctIndex() {
        String s = lineage("dws", "t",
                col("c1", "ods.a.x"),
                col("c2", "ods.b.y"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s)));

        assertEquals("ods.a 与 ods.b 处于同一层，index 必须不同",
                2, Set.of(g.index("ods.a.x"), g.index("ods.b.y")).size());
        // index 按表名字典序分配，ods.a 在前
        assertEquals(0, g.index("ods.a.x"));
        assertEquals(1, g.index("ods.b.y"));
    }

    /** 同一张表的多个字段必须共享同一个 index（它们渲染在同一个节点里）。 */
    @Test
    public void columnsOfSameTableShareIndex() {
        String s = lineage("dws", "t",
                col("c1", "ods.a.x"),
                col("c2", "ods.a.y"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s)));

        assertEquals(g.index("ods.a.x"), g.index("ods.a.y"));
    }

    /**
     * 缺陷 3：菱形依赖下的最长路径层级。
     *
     * <pre>
     *   ods.a.c --> tmp.b.c --> tmp.c.c --> dws.d.c
     *                  \___________________^
     * </pre>
     *
     * tmp.b.c 既直连 dws.d.c（距离 1），又经 tmp.c.c 到达（距离 2），
     * 取最长路径应为 2，ods.a.c 相应为 3。
     * 原实现的 BFS 因 visited 阻止重入队，新层级无法向下游传播，ods.a.c 会停在 2。
     */
    @Test
    public void diamondDependencyUsesLongestPath() {
        String s1 = lineage("dws", "d", col("c", "tmp.b.c", "tmp.c.c"));
        String s2 = lineage("tmp", "c", col("c", "tmp.b.c"));
        String s3 = lineage("tmp", "b", col("c", "ods.a.c"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s1, s2, s3)));

        assertEquals(0, g.level("dws.d.c"));
        assertEquals(1, g.level("tmp.c.c"));
        assertEquals("tmp.b.c 应取最长路径 2 而非直连的 1", 2, g.level("tmp.b.c"));
        assertEquals("层级须向下游传播，ods.a.c 应为 3", 3, g.level("ods.a.c"));
        assertEquals(3, g.maxLevel);
    }

    /** 更深的菱形，确认层级传播不是靠巧合的遍历顺序。 */
    @Test
    public void deepDiamondPropagatesLevels() {
        // t0 <- t1 <- t2 <- t3   以及  t0 <- t3 的短路边
        String s1 = lineage("l0", "t", col("c", "l1.t.c", "l3.t.c"));
        String s2 = lineage("l1", "t", col("c", "l2.t.c"));
        String s3 = lineage("l2", "t", col("c", "l3.t.c"));
        String s4 = lineage("l3", "t", col("c", "src.t.c"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s1, s2, s3, s4)));

        assertEquals(0, g.level("l0.t.c"));
        assertEquals(1, g.level("l1.t.c"));
        assertEquals(2, g.level("l2.t.c"));
        assertEquals("l3 走长路径应为 3，而非短路边的 1", 3, g.level("l3.t.c"));
        assertEquals(4, g.level("src.t.c"));
    }

    /**
     * 缺陷 4：自环（insert into t select ... from t）必须被检测到，
     * 不能死循环，也不能静默产出错误层级。
     */
    @Test
    public void selfCycleIsDetectedAndDoesNotHang() {
        String s = lineage("dws", "t", col("c1", "dws.t.c1"));

        String json = SQLLineageMerger.mergeSQLLineage(List.of(s));
        Graph g = parse(json);

        assertNotNull("自环不应导致返回 null", json);
        assertFalse("应产生环告警", g.warnings.isEmpty());
        assertTrue("告警应指出成环字段", g.warnings.toString().contains("dws.t.c1"));
    }

    /** 两节点互相引用的环。 */
    @Test
    public void mutualCycleIsDetectedAndDoesNotHang() {
        String s1 = lineage("dws", "a", col("c", "dws.b.c"));
        String s2 = lineage("dws", "b", col("c", "dws.a.c"));

        String json = SQLLineageMerger.mergeSQLLineage(List.of(s1, s2));
        Graph g = parse(json);

        assertNotNull(json);
        assertFalse("应产生环告警", g.warnings.isEmpty());
    }

    /** 环上的边应被标记，便于前端用虚线区分渲染。 */
    @Test
    public void cyclicEdgesAreMarked() {
        String s1 = lineage("dws", "a", col("c", "dws.b.c"));
        String s2 = lineage("dws", "b", col("c", "dws.a.c"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s1, s2)));

        assertTrue("环上至少应有一条边被标记为 cyclic", g.hasCyclicEdge);
    }

    /** 空输入不应返回 null，而应返回结构完整的空结果。 */
    @Test
    public void emptyInputReturnsEmptyStructureNotNull() {
        String json = SQLLineageMerger.mergeSQLLineage(new ArrayList<>());

        assertNotNull("空输入不应返回 null，否则前端拿到空 body", json);
        Graph g = parse(json);
        assertEquals(0, g.size);
        assertFalse("应提示未识别到可解析的语句", g.warnings.isEmpty());
    }

    /** 目标字段没有任何上游时，仍应出现在结果中并标记 final。 */
    @Test
    public void targetWithoutSourcesIsKept() {
        String s = lineage("dws", "t", col("c1"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s)));

        assertTrue(g.contains("dws.t.c1"));
        assertTrue(g.isFinal("dws.t.c1"));
        assertEquals(0, g.refCount("dws.t.c1"));
    }

    // ---------------- 列过滤 ----------------

    /** 按列名过滤时，应保留该列相关的血缘。 */
    @Test
    public void columnFilterKeepsRelevantLineage() {
        String s = lineage("dws", "t",
                col("keep_me", "ods.a.keep_me"),
                col("drop_me", "ods.a.drop_me"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s), "keep_me"));

        assertTrue(g.contains("dws.t.keep_me"));
        assertFalse("无关列应被过滤掉", g.contains("dws.t.drop_me"));
    }

    /** 过滤条件命中不到任何列时，不应返回 null。 */
    @Test
    public void columnFilterWithNoMatchReturnsEmptyStructure() {
        String s = lineage("dws", "t", col("c1", "ods.a.x"));

        String json = SQLLineageMerger.mergeSQLLineage(List.of(s), "not_exist");

        assertNotNull("过滤无命中不应返回 null", json);
        Graph g = parse(json);
        assertEquals(0, g.size);
    }

    /** 支持用全限定名精确过滤，避免跨表命中同名列。 */
    @Test
    public void columnFilterSupportsQualifiedName() {
        String s1 = lineage("dws", "t1", col("id", "ods.a.id"));
        String s2 = lineage("dws", "t2", col("id", "ods.b.id"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s1, s2), "dws.t1.id"));

        assertTrue(g.contains("dws.t1.id"));
        assertFalse("全限定过滤不应命中其它表的同名列", g.contains("dws.t2.id"));
    }

    /** 列名过滤不应把 user_id 这类后缀相同的列误当作 id。 */
    @Test
    public void columnFilterDoesNotMatchBySuffixOnly() {
        String s = lineage("dws", "t",
                col("id", "ods.a.id"),
                col("user_id", "ods.a.user_id"));

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(List.of(s), "id"));

        assertTrue(g.contains("dws.t.id"));
        assertFalse("user_id 不应被 id 命中", g.contains("dws.t.user_id"));
    }

    // ---------------- noProcessData ----------------

    /** noProcessData 应把中间过程压平，直接连到根源字段。 */
    @Test
    public void noProcessDataCollapsesToRootSources() {
        String s1 = lineage("dws", "c", col("c1", "tmp.b.c1"));
        String s2 = lineage("tmp", "b", col("c1", "stg.a.c1"));

        JsonNode root = readTree(SQLLineageMerger.mergeSQLLineage(List.of(s1, s2)));
        Graph np = Graph.of(root.path("data").path("noProcessData"));

        assertEquals("只保留最终目标字段", 1, np.size);
        assertEquals("中间的 tmp.b.c1 应被压平掉",
                Set.of("stg.a.c1"), np.refsOf("dws.c.c1"));
    }

    // ---------------- 稳定性与规模 ----------------

    /**
     * 同一份输入重复解析必须得到完全一致的结果（request_id 除外）。
     * 原实现的层级计算起点来自 HashMap.keySet()，遍历顺序不确定，
     * 同一份 SQL 两次解析可能得到不同的图。
     */
    @Test
    public void outputIsDeterministicAcrossRuns() {
        List<String> inputs = List.of(
                lineage("dws", "d", col("c", "tmp.b.c", "tmp.c.c")),
                lineage("tmp", "c", col("c", "tmp.b.c")),
                lineage("tmp", "b", col("c", "ods.a.c", "ods.z.c")),
                lineage("ods", "a", col("c", "stg.x.c")));

        String baseline = stripRequestId(SQLLineageMerger.mergeSQLLineage(inputs));
        for (int i = 0; i < 30; i++) {
            assertEquals("第 " + i + " 次运行结果与基线不一致",
                    baseline, stripRequestId(SQLLineageMerger.mergeSQLLineage(inputs)));
        }
    }

    /** 深链路（1000 跳）不应栈溢出，层级应线性递增。 */
    @Test
    public void deepChainDoesNotOverflowStack() {
        int depth = 1000;
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < depth; i++) {
            inputs.add(lineage("s" + i, "t", col("c", "s" + (i + 1) + ".t.c")));
        }

        Graph g = parse(SQLLineageMerger.mergeSQLLineage(inputs));

        assertEquals(0, g.level("s0.t.c"));
        assertEquals(depth, g.level("s" + depth + ".t.c"));
    }

    /** 长环不应死循环或栈溢出。 */
    @Test
    public void longCycleTerminates() {
        int size = 500;
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            inputs.add(lineage("s" + i, "t", col("c", "s" + ((i + 1) % size) + ".t.c")));
        }

        String json = SQLLineageMerger.mergeSQLLineage(inputs);

        assertNotNull(json);
        Graph g = parse(json);
        assertFalse("长环应被检出", g.warnings.isEmpty());
    }

    private static String stripRequestId(String json) {
        // traceId 每次随机，比对前先归一化
        return json.replaceAll("\"traceId\"\\s*:\\s*\"[0-9a-f]+\"", "\"traceId\":\"<id>\"");
    }

    // ------------------------------------------------------------------
    // 构造与解析辅助
    // ------------------------------------------------------------------

    /** 描述一个目标列及其上游（上游用 schema.table.column 全限定名）。 */
    private static Map.Entry<String, List<String>> col(String column, String... sources) {
        return Map.entry(column, List.of(sources));
    }

    /** 构造一条 sqlflow Output 风格的血缘 JSON。 */
    @SafeVarargs
    private static String lineage(String schema, String table, Map.Entry<String, List<String>>... columns) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putNull("catalogName");
        root.put("schema", schema);
        root.put("table", table);

        ArrayNode cols = root.putArray("columns");
        for (Map.Entry<String, List<String>> c : columns) {
            ObjectNode colNode = cols.addObject();
            colNode.put("column", c.getKey());
            ArrayNode srcs = colNode.putArray("sourceColumns");
            for (String qualified : c.getValue()) {
                int i = qualified.lastIndexOf('.');
                ObjectNode src = srcs.addObject();
                src.put("tableName", qualified.substring(0, i));
                src.put("columnName", qualified.substring(i + 1));
            }
        }
        return root.toString();
    }

    private static JsonNode readTree(String json) {
        assertNotNull("结果不应为 null", json);
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("结果不是合法 JSON: " + json, e);
        }
    }

    private static Graph parse(String json) {
        JsonNode root = readTree(json);
        Graph g = Graph.of(root.path("data").path("withProcessData"));
        for (JsonNode w : root.path("warnings")) {
            g.warnings.add(w.asText());
        }
        return g;
    }

    /** 对 withProcessData / noProcessData 结构的只读视图，方便断言。 */
    private static final class Graph {
        final Map<String, Integer> levels = new HashMap<>();
        final Map<String, Integer> indexes = new HashMap<>();
        final Map<String, Boolean> finals = new HashMap<>();
        final Map<String, List<String>> refs = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
        boolean hasCyclicEdge;
        int size;
        int maxLevel;

        static Graph of(JsonNode section) {
            Graph g = new Graph();
            g.size = section.path("size").asInt();
            g.maxLevel = section.path("level").asInt();
            for (JsonNode item : section.path("data")) {
                JsonNode target = item.path("targetField");
                String targetName = target.path("fieldName").asText();
                g.absorb(target);
                List<String> refNames = new ArrayList<>();
                for (JsonNode ref : item.path("refFields")) {
                    g.absorb(ref);
                    refNames.add(ref.path("fieldName").asText());
                    if (ref.path("cyclic").asBoolean(false)) {
                        g.hasCyclicEdge = true;
                    }
                }
                g.refs.put(targetName, refNames);
            }
            return g;
        }

        private void absorb(JsonNode field) {
            String name = field.path("fieldName").asText();
            levels.put(name, field.path("level").asInt());
            indexes.put(name, field.path("index").asInt());
            // 同一字段可能既作为 target 又作为 ref 出现，final 以 true 为准
            finals.merge(name, field.path("final").asBoolean(false), (a, b) -> a || b);
        }

        int level(String field) {
            assertTrue("结果中缺少字段 " + field + "，实际字段: " + new TreeSet<>(levels.keySet()),
                    levels.containsKey(field));
            return levels.get(field);
        }

        int index(String field) {
            assertTrue("结果中缺少字段 " + field, indexes.containsKey(field));
            return indexes.get(field);
        }

        boolean isFinal(String field) {
            return Boolean.TRUE.equals(finals.get(field));
        }

        boolean contains(String field) {
            return levels.containsKey(field);
        }

        Set<String> refsOf(String target) {
            assertTrue("结果中缺少目标字段 " + target + "，实际目标: " + new TreeSet<>(refs.keySet()),
                    refs.containsKey(target));
            return new java.util.HashSet<>(refs.get(target));
        }

        int refCount(String target) {
            assertTrue("结果中缺少目标字段 " + target, refs.containsKey(target));
            return refs.get(target).size();
        }
    }
}
