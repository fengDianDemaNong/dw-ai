package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.persistence.MetaColumnRow;
import com.dwai.lineage.persistence.MetaTableRow;
import com.dwai.lineage.service.metadata.MetaNames;
import com.dwai.lineage.tenant.LineageContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从我们自己维护的元数据目录（{@code meta_table} / {@code meta_column}）读表结构。
 *
 * <p>与 {@link GravitinoMetadataProvider} / {@link DbxMetadataProvider} 并列的一种来源，
 * 区别是它查的是本地库而不是外部服务：不受外部服务可用性影响、没有网络往返，
 * 而且内容可以被人工修正过。
 *
 * <p>优先级不写死在代码里，而是作为一条 {@code CATALOG} 类型的
 * {@code metadata_source} 参与已有的 priority 编排，用户能在「元数据服务」页面上
 * 把它和 Gravitino / dbx 一起排序、启停。
 *
 * <p>遵守 {@link MetadataProvider} 的既有约定：一次批量查完，查不到的表进
 * {@code unresolved} 而不抛异常。
 */
public class CatalogMetadataProvider implements MetadataProvider {

    private final MetaCatalogRepository repository;
    private final LineageContext ctx;

    public CatalogMetadataProvider(MetaCatalogRepository repository, LineageContext ctx) {
        this.repository = repository;
        this.ctx = ctx;
    }

    @Override
    public String name() {
        return "本地元数据目录";
    }

    @Override
    public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
        if (tables == null || tables.isEmpty()) {
            return MetadataResolution.empty();
        }

        // 一次把候选行全捞回来，避免逐表往返。
        // 查的 key 是「不带数据目录的 schema.table」，因此一次查询同时覆盖两种情况：
        // 目录下的同名表（full_name 以 .schema.table 结尾）与无目录的表（恰好相等）
        Map<QualifiedObjectName, String> schemaTableKeys = new LinkedHashMap<>();
        for (QualifiedObjectName wanted : tables) {
            schemaTableKeys.put(wanted,
                    MetaNames.tableFullName(wanted.getSchemaName(), wanted.getObjectName()));
        }

        List<MetaTableRow> candidates =
                repository.findBySchemaAndTable(ctx, new LinkedHashSet<>(schemaTableKeys.values()));
        Map<Long, List<MetaColumnRow>> columnsByTable = candidates.isEmpty()
                ? Map.of()
                : repository.listColumnsByTableIds(ctx,
                        candidates.stream().map(MetaTableRow::id).toList());

        Map<QualifiedObjectName, SchemaTable> resolved = new LinkedHashMap<>();
        Map<QualifiedObjectName, TableDescriptor> descriptors = new LinkedHashMap<>();
        for (Map.Entry<QualifiedObjectName, String> entry : schemaTableKeys.entrySet()) {
            QualifiedObjectName wanted = entry.getKey();
            MetaTableRow table = pick(wanted, entry.getValue(), candidates);
            if (table == null) {
                continue;
            }
            List<MetaColumnRow> columns = columnsByTable.getOrDefault(table.id(), List.of());
            if (columns.isEmpty()) {
                // 有表无字段，对血缘解析毫无用处，当作没查到，让后面的来源接手
                continue;
            }

            List<String> columnNames = new ArrayList<>();
            List<String> partitionColumns = new ArrayList<>();
            Map<String, ColumnDescriptor> columnDescriptors = new LinkedHashMap<>();
            for (MetaColumnRow c : columns) {
                columnNames.add(c.columnName());
                if (c.partition()) {
                    partitionColumns.add(c.columnName());
                }
                columnDescriptors.put(TableDescriptor.normalizeColumnKey(c.columnName()),
                        new ColumnDescriptor(c.dataType(), c.comment(), c.remark()));
            }
            // 必须走 SchemaTables：空 catalog 要归一成 null，否则分析器按字符串匹配时找不到
            resolved.put(wanted, SchemaTables.of(
                    wanted, table.schemaName(), table.tableName(), columnNames, partitionColumns));

            // 描述搭便车带出去，随血缘一起落库。这些字段本来就在手上，
            // 过去只是没被取用，见 TableDescriptor 的类注释
            descriptors.put(wanted, new TableDescriptor(
                    table.comment(), table.tableType(), table.remark(), columnDescriptors));
        }

        Set<QualifiedObjectName> unresolved = new LinkedHashSet<>(tables);
        unresolved.removeAll(resolved.keySet());
        return MetadataResolution.of(resolved, unresolved, descriptors);
    }

    /**
     * 在候选行里挑出该表对应的那一行。
     *
     * <p>目录里的 {@code full_name} 形如 {@code [catalog.]schema.table} 且恒为小写
     * （见 {@link MetaNames}）。分三种情况：
     *
     * <ol>
     *   <li><b>SQL 写了数据目录</b>（{@code select * from hive_prod.ods.orders}）——
     *       精确匹配 {@code hive_prod.ods.orders}；匹配不上再退到无目录的 {@code ods.orders}，
     *       因为目录信息可能只存在于 SQL 里而元数据是贴建表语句导入的</li>
     *   <li><b>SQL 没写数据目录，且目录里只有一张同名表</b> —— 用它。
     *       这条回退很重要：从 Gravitino 同步来的表带着目录，而人写 SQL 时基本不写全三段，
     *       没有它就会大面积查不到表结构</li>
     *   <li><b>SQL 没写数据目录，但多个目录下都有同名表</b> —— <b>不猜</b>，返回 null 让它进
     *       {@code unresolved}。随便挑一个会把血缘接到错误的表上，
     *       而这种错误在图上看不出来</li>
     * </ol>
     */
    private static MetaTableRow pick(QualifiedObjectName wanted, String schemaTableKey,
                                     List<MetaTableRow> candidates) {
        List<MetaTableRow> matches = candidates.stream()
                .filter(row -> schemaTableKey.equals(MetaNames.withoutCatalog(row.fullName())))
                .toList();
        if (matches.isEmpty()) {
            return null;
        }

        String wantedCatalog = SchemaTables.normalizeCatalog(wanted.getCatalogName());
        if (wantedCatalog != null) {
            String exact = MetaNames.tableFullName(wantedCatalog,
                    wanted.getSchemaName(), wanted.getObjectName());
            for (MetaTableRow row : matches) {
                if (exact.equals(row.fullName())) {
                    return row;
                }
            }
        }

        // 这里原本还有一档「无目录的那一行优先」。1.0.4 起 catalog_name 是 NOT NULL、
        // full_name 恒为三段，不存在 full_name 恰好等于 schema.table 的行，那一档永远走不到，
        // 留着只会让人以为还有这种数据。
        return matches.size() == 1 ? matches.get(0) : null;
    }
}
