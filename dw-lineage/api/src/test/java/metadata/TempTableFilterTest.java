package metadata;

import org.junit.Test;
import com.dwai.lineage.enums.MatchType;
import com.dwai.lineage.enums.TempRuleTarget;
import com.dwai.lineage.persistence.TempRuleRow;
import com.dwai.lineage.service.metadata.TempTableFilter;
import com.dwai.lineage.service.metadata.TempTableMatcher;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 临时表的识别与穿透。
 *
 * <p>核心断言用的就是需求里给的那段 SQL：
 * <pre>
 * create tmp.bb as select * from source_a;
 * insert into sink_b select * from tmp.bb;
 * </pre>
 * 过滤后必须是 {@code source_a → sink_b}，<b>不能是两段断开的图</b>。
 */
public class TempTableFilterTest {

    private static TempTableMatcher matcher(TempRuleRow... rules) {
        return TempTableMatcher.of(List.of(rules));
    }

    private static TempRuleRow schemaRule(String pattern, MatchType type) {
        return new TempRuleRow(1, 1, 1, null, TempRuleTarget.SCHEMA, type, pattern, true, null, null, null);
    }

    private static TempRuleRow tableRule(String pattern, MatchType type) {
        return new TempRuleRow(2, 1, 1, null, TempRuleTarget.TABLE, type, pattern, true, null, null, null);
    }

    // ---------------- 匹配 ----------------

    /** 通配符与正则对 tmp_* 的解释完全不同 —— 这正是要让用户显式选的原因。 */
    @Test
    public void globAndRegexDifferOnTmpStar() {
        assertTrue("通配符 tmp_* 应当匹配 tmp_abc",
                matcher(tableRule("tmp_*", MatchType.GLOB)).isTemp("cat.db.tmp_abc"));
        assertFalse("正则 tmp_* 匹配不到 tmp_abc（* 修饰的是下划线）",
                matcher(tableRule("tmp_*", MatchType.REGEX)).isTemp("cat.db.tmp_abc"));
        assertTrue("正则要写成 tmp_.* 才对",
                matcher(tableRule("tmp_.*", MatchType.REGEX)).isTemp("cat.db.tmp_abc"));
    }

    @Test
    public void schemaRulesMatchTheSchemaSegment() {
        TempTableMatcher m = matcher(schemaRule("tmp", MatchType.GLOB), schemaRule("test", MatchType.GLOB));
        assertTrue(m.isTemp("cat.tmp.bb"));
        assertTrue(m.isTemp("cat.test.anything"));
        assertFalse(m.isTemp("cat.ods.orders"));
        assertTrue("两段式全名也要能判", m.isTemp("tmp.bb"));
    }

    /**
     * 通配符里的正则元字符要按字面处理，不能当成正则语义。
     *
     * <p>表名本身不能含点号（全名靠点分段），所以这里用 {@code +} 来验：
     * 通配符下 {@code tmp+bak} 只匹配字面量 {@code tmp+bak}，
     * 而不是正则里的「一个或多个 p」。
     */
    @Test
    public void globEscapesRegexMetacharacters() {
        TempTableMatcher m = matcher(tableRule("tmp+bak", MatchType.GLOB));
        assertTrue("字面量应当匹配", m.isTemp("cat.db.tmp+bak"));
        assertFalse("不该按正则把 + 解释成重复", m.isTemp("cat.db.tmppbak"));
        assertFalse(m.isTemp("cat.db.tmpbak"));
    }

    /** 写坏的正则不该让整个功能挂掉，只是这条规则失效。 */
    @Test
    public void brokenRegexIsSkippedNotThrown() {
        TempTableMatcher m = matcher(tableRule("tmp_[", MatchType.REGEX));
        assertFalse(m.hasRules());
        assertFalse(m.isTemp("cat.db.tmp_abc"));
    }

    @Test
    public void catalogScopedRuleOnlyAppliesToThatCatalog() {
        TempRuleRow scoped = new TempRuleRow(3, 1, 1, "hive_test", TempRuleTarget.SCHEMA,
                MatchType.GLOB, "tmp", true, null, null, null);
        TempTableMatcher m = TempTableMatcher.of(List.of(scoped));
        assertTrue(m.isTemp("hive_test.tmp.bb"));
        assertFalse("别的数据目录下不该生效", m.isTemp("hive_prod.tmp.bb"));
    }

    // ---------------- 穿透 ----------------

    /** 需求里给的那个例子。 */
    @Test
    public void bridgesThroughTempTable() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put("cat.tmp.bb.c", Set.of("cat.ods.source_a.c"));
        edges.put("cat.dws.sink_b.c", Set.of("cat.tmp.bb.c"));

        Map<String, Set<String>> filtered =
                TempTableFilter.filter(edges, matcher(schemaRule("tmp", MatchType.GLOB)));

        assertEquals("临时表自己不该留下", 1, filtered.size());
        assertEquals("source_a 必须直接接到 sink_b，而不是断成两段",
                Set.of("cat.ods.source_a.c"), filtered.get("cat.dws.sink_b.c"));
    }

    /** 链式临时表要一路穿到底。 */
    @Test
    public void bridgesThroughChainedTempTables() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put("cat.tmp.t1.c", Set.of("cat.ods.a.c"));
        edges.put("cat.tmp.t2.c", Set.of("cat.tmp.t1.c"));
        edges.put("cat.dws.b.c", Set.of("cat.tmp.t2.c"));

        Map<String, Set<String>> filtered =
                TempTableFilter.filter(edges, matcher(schemaRule("tmp", MatchType.GLOB)));

        assertEquals(1, filtered.size());
        assertEquals(Set.of("cat.ods.a.c"), filtered.get("cat.dws.b.c"));
    }

    /** 临时表成环时必须能停下来，不能死循环。 */
    @Test
    public void cyclicTempTablesTerminate() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put("cat.tmp.t1.c", Set.of("cat.tmp.t2.c"));
        edges.put("cat.tmp.t2.c", Set.of("cat.tmp.t1.c", "cat.ods.a.c"));
        edges.put("cat.dws.b.c", Set.of("cat.tmp.t1.c"));

        Map<String, Set<String>> filtered =
                TempTableFilter.filter(edges, matcher(schemaRule("tmp", MatchType.GLOB)));

        assertEquals(Set.of("cat.ods.a.c"), filtered.get("cat.dws.b.c"));
    }

    /** 一个下游有多个临时上游时，全部要展开。 */
    @Test
    public void expandsAllTempSources() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put("cat.tmp.t1.c", Set.of("cat.ods.a.c"));
        edges.put("cat.tmp.t2.c", Set.of("cat.ods.b.c"));
        edges.put("cat.dws.x.c", Set.of("cat.tmp.t1.c", "cat.tmp.t2.c", "cat.ods.c.c"));

        Map<String, Set<String>> filtered =
                TempTableFilter.filter(edges, matcher(schemaRule("tmp", MatchType.GLOB)));

        assertEquals(Set.of("cat.ods.a.c", "cat.ods.b.c", "cat.ods.c.c"),
                filtered.get("cat.dws.x.c"));
    }

    /** 没配规则时原样返回，不该有任何开销或改动。 */
    @Test
    public void noRulesMeansNoChange() {
        Map<String, Set<String>> edges = Map.of("a.b.c", Set.of("d.e.f"));
        assertEquals(edges, TempTableFilter.filter(edges, TempTableMatcher.empty()));
    }

    /** 全链路都是临时表时，结果为空而不是残留半截。 */
    @Test
    public void allTempYieldsEmpty() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put("cat.tmp.t2.c", Set.of("cat.tmp.t1.c"));
        assertTrue(TempTableFilter.filter(edges,
                matcher(schemaRule("tmp", MatchType.GLOB))).isEmpty());
    }
}
