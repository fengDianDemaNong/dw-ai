package com.dwai.lineage.service.metadata;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把解析出来的表名 / 字段名补齐成带数据目录的全名。
 *
 * <h2>为什么需要</h2>
 * 用户的 SQL 里写的是 {@code from ods.orders} 这样的<b>两段名</b>，解析器原样吐出来。
 * 而本地元数据侧走 {@code DataCatalogService.resolveCatalogName}，恒为三段
 * {@code default.ods.orders}。两边名字对不上，直接后果有三个：
 *
 * <ul>
 *   <li>{@code lineage_table.catalog_name} 落 null，与「不存在无目录的表」这条约束冲突</li>
 *   <li>血缘侧与元数据侧靠 {@code full_name} 关联，对不上就取不到中文名 / 表类型 / 备注</li>
 *   <li>绑定了具体数据目录的临时表规则永远不生效 —— 两段名里根本没有目录段可比</li>
 * </ul>
 *
 * <p>所以在解析产出之后、入库与过滤之前，统一在这里补齐。
 *
 * <h2>表名和字段名必须分开处理</h2>
 * 光看段数无法区分二者：{@code a.b.c} 作为表名是完整的
 * {@code catalog.schema.table}，作为字段名却是缺目录的 {@code schema.table.column}。
 * 混用一个方法必然补错一半，所以拆成 {@link #normalizeTable} 与 {@link #normalizeField}，
 * 由调用方按上下文选。
 *
 * <p><b>段数不够的不猜</b>：裸列名 {@code id}、单段表名 {@code orders} 原样返回。
 * 补一个目录进去只会造出一个查无此表的假全名，不如留着让上游的告警机制去提示。
 */
public final class TableNameNormalizer {

    /** 表全名的完整段数：{@code catalog.schema.table}。 */
    private static final int TABLE_PARTS = 3;

    /** 字段全名的完整段数：{@code catalog.schema.table.column}。 */
    private static final int FIELD_PARTS = 4;

    private TableNameNormalizer() {
    }

    /**
     * 表全名补目录：{@code ods.orders} → {@code default.ods.orders}。
     *
     * @param defaultCatalog 默认目录名；为空时原样返回，不做任何处理
     */
    public static String normalizeTable(String tableFullName, String defaultCatalog) {
        return normalize(tableFullName, defaultCatalog, TABLE_PARTS);
    }

    /**
     * 字段全名补目录：{@code ods.orders.id} → {@code default.ods.orders.id}。
     *
     * @param defaultCatalog 默认目录名；为空时原样返回，不做任何处理
     */
    public static String normalizeField(String columnFullName, String defaultCatalog) {
        return normalize(columnFullName, defaultCatalog, FIELD_PARTS);
    }

    /**
     * 只补<b>正好差一段</b>的名字。
     *
     * <p>段数已经够（甚至更多）说明目录写全了，原样返回；差两段以上是残缺输入，
     * 补一段也凑不出可用的全名，同样原样返回。
     */
    private static String normalize(String fullName, String defaultCatalog, int expectedParts) {
        if (fullName == null || fullName.isBlank()
                || defaultCatalog == null || defaultCatalog.isBlank()) {
            return fullName;
        }
        int parts = countParts(fullName);
        return parts == expectedParts - 1 ? defaultCatalog + "." + fullName : fullName;
    }

    /** 段数 = 点的个数 + 1。不用 split：它会丢掉尾部空串，{@code a.b.} 会被算成 2 段。 */
    private static int countParts(String fullName) {
        int count = 1;
        for (int i = 0; i < fullName.length(); i++) {
            if (fullName.charAt(i) == '.') {
                count++;
            }
        }
        return count;
    }

    /**
     * 整张边表的字段名一次补齐。
     *
     * <p>key 与 value 都是字段全名，两侧都要补 —— 只补一侧会让同一张表的
     * 上下游节点变成两个不同的名字，图直接断开。
     *
     * @param edges 下游字段 → 上游字段集合
     * @return 补齐后的新边表；{@code defaultCatalog} 为空时原样返回
     */
    public static Map<String, Set<String>> normalizeEdges(Map<String, Set<String>> edges,
                                                          String defaultCatalog) {
        if (edges == null || edges.isEmpty()
                || defaultCatalog == null || defaultCatalog.isBlank()) {
            return edges;
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        edges.forEach((target, sources) -> {
            Set<String> normalized = new LinkedHashSet<>();
            if (sources != null) {
                sources.forEach(s -> normalized.add(normalizeField(s, defaultCatalog)));
            }
            // merge 而不是 put：两个原本不同的 key（ods.orders.id 与 default.ods.orders.id）
            // 补齐后会撞成同一个，直接 put 会丢掉先来的那份上游
            result.merge(normalizeField(target, defaultCatalog), normalized, (a, b) -> {
                Set<String> merged = new LinkedHashSet<>(a);
                merged.addAll(b);
                return merged;
            });
        });
        return result;
    }
}
