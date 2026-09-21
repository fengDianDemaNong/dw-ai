package metadata;

import org.junit.Test;
import com.dwai.lineage.service.metadata.TableNameNormalizer;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * 表名 / 字段名补齐数据目录。
 *
 * <p>核心风险是<b>表名和字段名段数相同却含义不同</b>：{@code a.b.c} 作为表名已经完整，
 * 作为字段名却缺目录。两条路径分开验，防止哪天有人图省事合并成一个方法。
 */
public class TableNameNormalizerTest {

    private static final String CAT = "default";

    @Test
    public void twoPartTableGetsCatalog() {
        assertEquals("default.ods.orders",
                TableNameNormalizer.normalizeTable("ods.orders", CAT));
    }

    @Test
    public void threePartTableIsLeftAlone() {
        assertEquals("已经带目录的表名不该再补一层",
                "hive_prod.ods.orders",
                TableNameNormalizer.normalizeTable("hive_prod.ods.orders", CAT));
    }

    @Test
    public void threePartFieldGetsCatalog() {
        assertEquals("default.ods.orders.id",
                TableNameNormalizer.normalizeField("ods.orders.id", CAT));
    }

    @Test
    public void fourPartFieldIsLeftAlone() {
        assertEquals("hive_prod.ods.orders.id",
                TableNameNormalizer.normalizeField("hive_prod.ods.orders.id", CAT));
    }

    /**
     * 同一个字符串走两条路径结果必须不同 —— 这正是两个方法不能合并的原因。
     */
    @Test
    public void sameStringMeansDifferentThingsAsTableAndField() {
        String ambiguous = "a.b.c";
        assertEquals("当表名看：已经是 catalog.schema.table，不动",
                "a.b.c", TableNameNormalizer.normalizeTable(ambiguous, CAT));
        assertEquals("当字段名看：是 schema.table.column，要补目录",
                "default.a.b.c", TableNameNormalizer.normalizeField(ambiguous, CAT));
    }

    /** 段数差得太多的不猜：补一段也凑不出可用的全名。 */
    @Test
    public void tooFewPartsAreNotGuessed() {
        assertEquals("orders", TableNameNormalizer.normalizeTable("orders", CAT));
        assertEquals("id", TableNameNormalizer.normalizeField("id", CAT));
    }

    @Test
    public void blankDefaultCatalogDisablesEverything() {
        assertEquals("ods.orders", TableNameNormalizer.normalizeTable("ods.orders", null));
        assertEquals("ods.orders", TableNameNormalizer.normalizeTable("ods.orders", "  "));
        assertEquals("ods.orders.id", TableNameNormalizer.normalizeField("ods.orders.id", ""));
    }

    @Test
    public void nullNameSurvives() {
        assertEquals(null, TableNameNormalizer.normalizeTable(null, CAT));
        assertEquals(null, TableNameNormalizer.normalizeField(null, CAT));
    }

    // ==================================================================
    // 边表
    // ==================================================================

    @Test
    public void edgesAreNormalizedOnBothSides() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put("dwd.detail.id", new LinkedHashSet<>(Set.of("ods.orders.id")));

        Map<String, Set<String>> out = TableNameNormalizer.normalizeEdges(edges, CAT);

        assertEquals(Set.of("default.dwd.detail.id"), out.keySet());
        assertEquals("上游没一起补的话，同一张表会变成两个节点，图直接断开",
                Set.of("default.ods.orders.id"), out.get("default.dwd.detail.id"));
    }

    /**
     * 一段 SQL 里既写了 {@code ods.orders} 又写了 {@code default.ods.orders} 时，
     * 补齐后两个 key 会撞成一个，各自的上游必须合并而不是后者覆盖前者。
     */
    @Test
    public void collidingKeysMergeInsteadOfOverwrite() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put("dwd.detail.id", new LinkedHashSet<>(Set.of("ods.a.id")));
        edges.put("default.dwd.detail.id", new LinkedHashSet<>(Set.of("ods.b.id")));

        Map<String, Set<String>> out = TableNameNormalizer.normalizeEdges(edges, CAT);

        assertEquals("两个 key 应当合并成一个", 1, out.size());
        assertEquals(Set.of("default.ods.a.id", "default.ods.b.id"),
                out.get("default.dwd.detail.id"));
    }

    @Test
    public void edgesUntouchedWhenNoDefaultCatalog() {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        edges.put("dwd.detail.id", new LinkedHashSet<>(Set.of("ods.orders.id")));

        assertSame("没有默认目录时应原样返回同一个对象，不做无谓的复制",
                edges, TableNameNormalizer.normalizeEdges(edges, null));
    }
}
