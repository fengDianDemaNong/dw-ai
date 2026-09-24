package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.common.relational.create.CreateTable;
import io.github.melin.superior.common.relational.table.ColumnRel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 从 SQL 脚本自带的 {@code CREATE TABLE} 语句提取表结构。
 *
 * <p>优先级最高：脚本里既然显式写了建表语句，就以它为准，无需再查外部服务。
 * 对应接口参数 {@code isCreateTable=true}。
 */
public class DdlMetadataProvider implements MetadataProvider {

    /** 建表语句里没写 schema 时使用的默认值，与 sqlflow 的 SimpleMetadataService 保持一致。 */
    private static final String DEFAULT_SCHEMA = "default";

    private final Map<QualifiedObjectName, SchemaTable> tablesFromDdl;

    /**
     * 与 {@link #tablesFromDdl} 平行的描述属性。
     *
     * <p>建表语句里本来就写着字段类型和注释（{@code ColumnRel} 有
     * {@code typeName} / {@code comment}，{@code CreateTable} 有表注释），
     * 过去只取了列名。这里一并留下，随血缘落库。
     */
    private final Map<QualifiedObjectName, TableDescriptor> descriptorsFromDdl =
            new LinkedHashMap<>();

    public DdlMetadataProvider(List<Statement> statements) {
        this.tablesFromDdl = extract(statements, descriptorsFromDdl);
    }

    private static Map<QualifiedObjectName, SchemaTable> extract(
            List<Statement> statements,
            Map<QualifiedObjectName, TableDescriptor> descriptors) {
        Map<QualifiedObjectName, SchemaTable> result = new LinkedHashMap<>();
        if (statements == null) {
            return result;
        }

        for (Statement statement : statements) {
            if (!(statement instanceof CreateTable createTable)) {
                continue;
            }
            List<String> columns = createTable.getColumnRels().stream()
                    .map(column -> column.getColumnName().trim())
                    .collect(Collectors.toList());

            String catalog = createTable.getTableId().getCatalogName();
            String schema = createTable.getTableId().getSchemaName() == null
                    ? DEFAULT_SCHEMA : createTable.getTableId().getSchemaName();
            String table = createTable.getTableId().getTableName().trim();

            // 走 SchemaTables 而不是直接 new：sqlflow 没有「带 catalog 且带分区列」的构造器，
            // 那个组合只能靠 setter 补，规则统一收在 SchemaTables 里
            SchemaTable schemaTable = SchemaTables.of(
                    catalog, schema, table, columns, createTable.getPartitionColumnNames());
            QualifiedObjectName key = key(catalog, schema, table);
            result.put(key, schemaTable);
            descriptors.put(key, describe(createTable));
        }
        return result;
    }

    /**
     * 从建表语句取描述。
     *
     * <p><b>表类型留空</b>：{@code CreateTable.tableType} 是解析器的概念
     * （HIVE / ICEBERG 之类），与数仓表类型（全量表 / 拉链表…）不是一回事，
     * 硬映射只会得到错的值。与 {@code DdlCatalogExtractor} 的处理保持一致。
     */
    private static TableDescriptor describe(CreateTable createTable) {
        Map<String, ColumnDescriptor> columns = new LinkedHashMap<>();
        collectColumns(createTable.getColumnRels(), columns);
        // 分区列独立于列定义，要单独取，否则分区字段没有类型
        collectColumns(createTable.getPartitionColumnRels(), columns);

        return new TableDescriptor(
                blankToNull(createTable.getComment()), null, null, columns);
    }

    private static void collectColumns(List<ColumnRel> source,
                                       Map<String, ColumnDescriptor> target) {
        if (source == null) {
            return;
        }
        for (ColumnRel c : source) {
            if (c.getColumnName() == null) {
                continue;
            }
            // putIfAbsent：分区列有时会在两处都出现，先来的那份定义更完整
            target.putIfAbsent(
                    TableDescriptor.normalizeColumnKey(c.getColumnName()),
                    ColumnDescriptor.of(blankToNull(c.getTypeName()),
                            blankToNull(c.getComment())));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static QualifiedObjectName key(String catalog, String schema, String table) {
        return new QualifiedObjectName(
                catalog == null ? "" : catalog,
                schema == null ? DEFAULT_SCHEMA : schema,
                table);
    }

    @Override
    public String name() {
        return "SQL内DDL";
    }

    @Override
    public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
        Map<QualifiedObjectName, SchemaTable> resolved = new LinkedHashMap<>();
        Map<QualifiedObjectName, TableDescriptor> descriptors = new LinkedHashMap<>();
        Set<QualifiedObjectName> unresolved = new LinkedHashSet<>();

        for (QualifiedObjectName wanted : tables) {
            // 命中的是 DDL 里的哪个 key —— 描述要按它去取，不能按 wanted，
            // 两者可能只是大小写或 catalog 缺省上的差别
            QualifiedObjectName hit = lookupKey(wanted);
            if (hit != null) {
                resolved.put(wanted, tablesFromDdl.get(hit));
                TableDescriptor descriptor = descriptorsFromDdl.get(hit);
                if (descriptor != null && !descriptor.isEmpty()) {
                    descriptors.put(wanted, descriptor);
                }
            } else {
                unresolved.add(wanted);
            }
        }
        return MetadataResolution.of(resolved, unresolved, descriptors);
    }

    /** catalog 常常缺省，因此按 schema + table 做不区分大小写的匹配。 */
    private QualifiedObjectName lookupKey(QualifiedObjectName wanted) {
        if (tablesFromDdl.containsKey(wanted)) {
            return wanted;
        }
        for (QualifiedObjectName candidate : tablesFromDdl.keySet()) {
            if (candidate.getSchemaName().equalsIgnoreCase(wanted.getSchemaName())
                    && candidate.getObjectName().equalsIgnoreCase(wanted.getObjectName())) {
                return candidate;
            }
        }
        return null;
    }

    /** 供调用方直接取全部 DDL 表，用于喂给 sqlflow 的 MetadataService。 */
    public Map<QualifiedObjectName, SchemaTable> allTables() {
        return tablesFromDdl;
    }
}
