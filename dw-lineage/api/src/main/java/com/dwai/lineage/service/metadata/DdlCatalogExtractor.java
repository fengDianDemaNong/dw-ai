package com.dwai.lineage.service.metadata;

import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.common.relational.create.CreateTable;
import io.github.melin.superior.common.relational.table.ColumnRel;
import com.dwai.lineage.enums.MetaSource;
import com.dwai.lineage.persistence.MetaColumnRow;
import com.dwai.lineage.persistence.MetaTableRow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 从建表语句抽取元数据目录条目。
 *
 * <h2>为什么不复用 {@code DdlMetadataProvider}</h2>
 * 那个类服务的是<b>血缘解析</b>，只需要列名，因此它
 * {@code extract()} 里只取了 {@code column.getColumnName()}，
 * <b>把字段类型和注释都丢掉了</b>。而元数据目录恰恰要这两样 ——
 * 中文名是用户最关心的信息之一。
 *
 * <p>解析器本身给得很全：{@code CreateTable} 带表注释，
 * {@code ColumnRel} 带 {@code typeName} / {@code comment} / {@code nullable} / {@code primaryKey}。
 * 所以这里单独抽一份，两个类各司其职，互不影响。
 *
 * <p><b>不推断表类型</b>：{@code CreateTable.tableType} 是解析器的概念（HIVE / ICEBERG 之类），
 * 与我们的数仓表类型（全量表 / 增量表 / 拉链表…）不是一回事，硬映射只会得到错的值。
 * 留空，由用户在页面上选。
 */
public final class DdlCatalogExtractor {

    private DdlCatalogExtractor() {
    }

    /** 一张从 DDL 抽出来的表及其字段。 */
    public record ExtractedTable(MetaTableRow table, List<MetaColumnRow> columns) {
    }

    /**
     * 从已解析的语句里挑出全部 {@code CREATE TABLE} 并转成目录条目。
     *
     * @param statements 由 {@code MetadataServiceFactory.statements(dbType, sql)} 拆分而来
     * @param dbType     方言，记进 {@code meta_table.db_type}
     */
    public static List<ExtractedTable> extract(List<Statement> statements, String dbType) {
        return extract(statements, dbType, null);
    }

    /**
     * @param defaultCatalog 建表语句里没写数据目录时用它兜底。
     *                       本地元数据要求每张表都归属一个目录，否则
     *                       {@code hive_prod.ods.orders} 与 {@code ods.orders}
     *                       会是两条互不相干的记录，页面上按目录一筛就漏
     */
    public static List<ExtractedTable> extract(List<Statement> statements, String dbType,
                                               String defaultCatalog) {
        List<ExtractedTable> result = new ArrayList<>();
        if (statements == null) {
            return result;
        }

        for (Statement statement : statements) {
            if (statement instanceof CreateTable createTable) {
                result.add(toEntry(createTable, dbType, defaultCatalog));
            }
        }
        return result;
    }

    private static ExtractedTable toEntry(CreateTable createTable, String dbType,
                                          String defaultCatalog) {
        String schema = createTable.getTableId().getSchemaName() == null
                ? MetaNames.DEFAULT_SCHEMA : createTable.getTableId().getSchemaName().trim();
        String tableName = createTable.getTableId().getTableName().trim();
        // 三段式 `create table cat.db.tbl` 的第一段。解析器一直给着，此前没取。
        // 语句里写了就以它为准，没写才用导入时选的目录兜底
        String catalogName = blankToNull(createTable.getTableId().getCatalogName());
        if (catalogName == null) {
            catalogName = blankToNull(defaultCatalog);
        }
        // full_name 是唯一键与解析时的点查键，统一小写；展示用的目录/库/表名保留原始大小写
        String fullName = MetaNames.tableFullName(catalogName, schema, tableName);

        MetaTableRow table = MetaTableRow.of(
                catalogName, schema, tableName, fullName,
                // 表类型留空，见类注释
                null,
                blankToNull(createTable.getComment()),
                null,
                dbType,
                MetaSource.DDL,
                null);

        return new ExtractedTable(table, columnsOf(createTable));
    }

    /**
     * 普通字段 + 分区字段。
     *
     * <p>Hive / Spark 的 {@code PARTITIONED BY} 是独立于列定义的，分区列不在
     * {@code columnRels} 里，要单独取。Doris 相反：分区列已经写在列清单里，
     * 还要对照 {@code partitionColumnNames} 打标，否则导入目录后看不出谁是分区。
     */
    private static List<MetaColumnRow> columnsOf(CreateTable createTable) {
        List<MetaColumnRow> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int ordinal = 1;

        Set<String> partitionNames = new LinkedHashSet<>();
        for (String name : createTable.getPartitionColumnNames()) {
            if (name != null && !name.isBlank()) {
                partitionNames.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }

        List<ColumnRel> normal = createTable.getColumnRels();
        if (normal != null) {
            for (ColumnRel c : normal) {
                String name = c.getColumnName().trim();
                if (seen.add(name.toLowerCase(Locale.ROOT))) {
                    // Doris 分区列写在列清单里，不能只靠后面补行来打标
                    columns.add(toColumn(c, name, ordinal++,
                            partitionNames.contains(name.toLowerCase(Locale.ROOT))));
                }
            }
        }

        List<ColumnRel> partitions = createTable.getPartitionColumnRels();
        if (partitions != null) {
            for (ColumnRel c : partitions) {
                String name = c.getColumnName().trim();
                if (seen.add(name.toLowerCase(Locale.ROOT))) {
                    columns.add(toColumn(c, name, ordinal++, true));
                }
            }
        }

        // 有的方言只给分区列名、不给完整定义，补上以免漏掉分区字段
        for (String name : createTable.getPartitionColumnNames()) {
            String trimmed = name.trim();
            if (seen.add(trimmed.toLowerCase(Locale.ROOT))) {
                columns.add(new MetaColumnRow(0, 0, 0, 0, trimmed, null, null, null, null,
                        ordinal++, true, true, false, MetaSource.DDL, null, null));
            }
        }

        return columns;
    }

    private static MetaColumnRow toColumn(ColumnRel c, String name, int ordinal, boolean partition) {
        return MetaColumnRow.of(
                name,
                blankToNull(c.getTypeName()),
                blankToNull(c.getComment()),
                ordinal,
                partition,
                c.getNullable(),
                c.getPrimaryKey(),
                MetaSource.DDL);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
