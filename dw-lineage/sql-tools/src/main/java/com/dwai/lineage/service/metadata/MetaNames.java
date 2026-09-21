package com.dwai.lineage.service.metadata;

import java.util.Locale;

/**
 * 元数据目录里表/字段全名的规范化。
 *
 * <p><b>格式：{@code [catalog.]schema.table} 与 {@code [catalog.]schema.table.column}，恒为小写。</b>
 * catalog 是可选段 —— 没有数据目录概念的来源（贴建表语句、单库 dbx）省略它，
 * 全名退化成 {@code schema.table}。
 *
 * <p>为什么 catalog 要进全名：唯一键建在 {@code full_name} 上，
 * catalog 不进去的话，{@code hive_prod.ods.orders} 与 {@code hive_test.ods.orders}
 * 会撞同一个 key，后同步的把先同步的覆盖掉。
 * （{@code full_name} 的格式在最初的建表语句注释里写的就是 {@code [catalog.]schema.table}，
 * 只是一直没实现 catalog 段。）
 *
 * <p>为什么要规范化大小写：SQL 里的标识符大小写随手写（{@code ODS.T} / {@code ods.t} 指同一张表），
 * 而 {@code full_name} 既是唯一键又是解析时的点查键。不统一的话，
 * 同一张表会因为大小写不同存成两行，解析时还查不到。
 *
 * <p>展示用的 {@code catalog_name} / {@code schema_name} / {@code table_name} / {@code column_name}
 * <b>保留原始大小写</b>，只有当作 key 的 {@code full_name} 转小写。
 *
 * <p>血缘侧的 {@code lineage_table.full_name} 不做小写转换 —— 那边的全名直接来自血缘图、
 * 是要显示在图上的原文。两边关联时对血缘侧加 {@code lower()}。
 */
public final class MetaNames {

    /** 库名缺省值，与 sqlflow 的 SimpleMetadataService 保持一致。 */
    public static final String DEFAULT_SCHEMA = "default";

    private MetaNames() {
    }

    /**
     * 表的规范化全名：{@code [catalog.]schema.table}，全小写。
     *
     * @param catalog 数据目录，为空时省略该段
     */
    public static String tableFullName(String catalog, String schema, String table) {
        String s = blank(schema) ? DEFAULT_SCHEMA : schema.trim();
        String base = s + "." + table.trim();
        String full = blank(catalog) ? base : catalog.trim() + "." + base;
        return full.toLowerCase(Locale.ROOT);
    }

    /**
     * 不带数据目录的表全名。
     *
     * <p>保留这个重载而不是让调用方传 null：绝大多数来源（贴建表语句、单库 dbx）
     * 本来就没有 catalog 概念，写 {@code tableFullName(null, s, t)} 只是噪音。
     */
    public static String tableFullName(String schema, String table) {
        return tableFullName(null, schema, table);
    }

    /** 字段的规范化全名：在表全名后面接列名，全小写。 */
    public static String columnFullName(String tableFullName, String column) {
        return (tableFullName + "." + column.trim()).toLowerCase(Locale.ROOT);
    }

    /**
     * 从表全名里取出数据目录段，没有则返回 null。
     *
     * <p>靠段数判断：3 段是 {@code catalog.schema.table}，2 段是 {@code schema.table}。
     * 这也意味着 <b>schema 与 table 名里不能含点号</b> —— 本来也不允许。
     */
    public static String catalogOf(String tableFullName) {
        if (tableFullName == null) {
            return null;
        }
        String[] parts = tableFullName.split("\\.");
        return parts.length >= 3 ? parts[0] : null;
    }

    /** 去掉数据目录段，得到 {@code schema.table}。用于「SQL 没写 catalog 时」的回退查找。 */
    public static String withoutCatalog(String tableFullName) {
        String catalog = catalogOf(tableFullName);
        return catalog == null ? tableFullName : tableFullName.substring(catalog.length() + 1);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
