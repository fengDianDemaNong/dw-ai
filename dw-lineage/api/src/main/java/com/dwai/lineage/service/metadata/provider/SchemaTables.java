package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;

import java.util.List;

/**
 * 构造 {@link SchemaTable} 的公共入口。
 *
 * <h2>为什么需要它：空 catalog 必须是 null，不能是空串</h2>
 *
 * sqlflow 的 {@code SimpleMetadataService} 是按 {@code toString()} 做字符串匹配来找表的，
 * 而 {@code SchemaTable.toString()} / {@code QualifiedObjectName.toString()} 都是这么写的：
 *
 * <pre>
 * catalogName != null ? catalog + "." + schema + "." + table
 *                     : schema + "." + table
 * </pre>
 *
 * 判的是 {@code != null} 而<b>不是</b>空判断。偏偏
 * {@code LineageAnalysisPipeline.collectReferencedTables()} 在收集待解析表时，
 * 把 null catalog 规范成了空串：
 *
 * <pre>
 * table.getCatalogName() == null ? "" : table.getCatalogName()
 * </pre>
 *
 * 于是 provider 若直接把 {@code wanted.getCatalogName()} 传给 SchemaTable，
 * 就会得到 {@code ".ods.user_log"}（前面多一个点），而分析器查的是
 * {@code "ods.user_log"} —— <b>永远匹配不上</b>，表面现象是
 * 「provider 明明 resolved 了，解析却报 table metadata not exists」。
 *
 * <p>{@code DdlMetadataProvider} 恰好躲过了这个坑：它传的是解析器给的原始 catalog（null）。
 * 其余三个 provider（Gravitino / dbx / 本地目录）都踩了，且因为外部服务不易联调而长期未被发现。
 */
final class SchemaTables {

    private SchemaTables() {
    }

    /**
     * 按待解析表构造 SchemaTable，自动把空 catalog 归一成 null。
     *
     * @param wanted 分析器要找的表，catalog 可能是空串
     */
    static SchemaTable of(QualifiedObjectName wanted, String schemaName, String tableName,
                          List<String> columns, List<String> partitionColumns) {
        return withPartitions(new SchemaTable(normalizeCatalog(wanted.getCatalogName()),
                schemaName, tableName, columns), partitionColumns);
    }

    /**
     * 分区列走 setter 而不是构造器。
     *
     * <p>sqlflow 1.0.7 的 {@code SchemaTable} <b>没有</b>
     * {@code (catalog, schema, table, columns, partitionColumns)} 这个五参构造器 ——
     * 它只有带 catalog 的四参版和不带 catalog 的
     * {@code (schema, table, columns, partitionColumns)}，后者会把 catalog 丢掉，
     * 正是本类整个存在的理由所要避免的那件事。
     *
     * <p>所以先按四参构造、再 set 分区列。看着绕，但这是在不改上游的前提下
     * 唯一能同时保住 catalog 和分区列的写法。
     */
    private static SchemaTable withPartitions(SchemaTable table, List<String> partitionColumns) {
        if (partitionColumns != null && !partitionColumns.isEmpty()) {
            table.setPartitionColumns(partitionColumns);
        }
        return table;
    }

    /** 已经有 catalog/schema/table/columns 的场景：只补分区列。 */
    static SchemaTable of(String catalogName, String schemaName, String tableName,
                          List<String> columns, List<String> partitionColumns) {
        return withPartitions(
                new SchemaTable(normalizeCatalog(catalogName), schemaName, tableName, columns),
                partitionColumns);
    }

    /** 空串等同于「没有 catalog」，必须转成 null，否则 toString() 会多拼一段。 */
    static String normalizeCatalog(String catalog) {
        return (catalog == null || catalog.isBlank()) ? null : catalog;
    }
}
