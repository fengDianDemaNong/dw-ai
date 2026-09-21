package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableCatalog;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 从 Gravitino 读取表结构。
 *
 * <p>相较于原先「循环里逐表同步 loadTable」的写法，这里做了两件事：
 * <ul>
 *   <li><b>批量并发</b>：一条 SQL 涉及几十张表时，串行往返的耗时不可接受</li>
 *   <li><b>单表降级</b>：某张表在 Gravitino 中不存在（写错表名、临时表、跨 catalog）
 *       只标记为未解析，不再让整个请求失败</li>
 * </ul>
 */
public class GravitinoMetadataProvider implements MetadataProvider {

    private static final Logger logger = LoggerFactory.getLogger(GravitinoMetadataProvider.class);

    private static final String DEFAULT_SCHEMA = "default";

    private final TableCatalog tableCatalog;
    private final ExecutorService executor;
    private final long perTableTimeoutSeconds;

    public GravitinoMetadataProvider(Catalog catalog, ExecutorService executor, long perTableTimeoutSeconds) {
        this.tableCatalog = catalog.asTableCatalog();
        this.executor = executor;
        this.perTableTimeoutSeconds = perTableTimeoutSeconds;
    }

    @Override
    public String name() {
        return "Gravitino";
    }

    @Override
    public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
        if (tables == null || tables.isEmpty()) {
            return MetadataResolution.empty();
        }

        Map<QualifiedObjectName, CompletableFuture<Loaded>> futures = new LinkedHashMap<>();
        for (QualifiedObjectName wanted : tables) {
            futures.put(wanted, CompletableFuture.supplyAsync(() -> loadOne(wanted), executor));
        }

        Map<QualifiedObjectName, SchemaTable> resolved = new LinkedHashMap<>();
        Map<QualifiedObjectName, TableDescriptor> descriptors = new LinkedHashMap<>();
        Set<QualifiedObjectName> unresolved = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        futures.forEach((wanted, future) -> {
            try {
                Loaded loaded = future.get(perTableTimeoutSeconds, TimeUnit.SECONDS);
                if (loaded != null) {
                    resolved.put(wanted, loaded.table());
                    if (loaded.descriptor() != null && !loaded.descriptor().isEmpty()) {
                        descriptors.put(wanted, loaded.descriptor());
                    }
                } else {
                    unresolved.add(wanted);
                }
            } catch (Exception e) {
                future.cancel(true);
                unresolved.add(wanted);
                String reason = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                warnings.add("Gravitino 未能获取表结构 " + wanted + ": " + reason);
                logger.warn("Gravitino 获取表结构失败: {}", wanted, e);
            }
        });

        return new MetadataResolution(resolved, unresolved, warnings, descriptors);
    }

    /**
     * 一次 loadTable 的产出：血缘要的结构 + 顺带带出来的描述。
     *
     * <p>{@code loadTable} 返回的 {@code Table} 本来就带着表注释和每列的类型/注释，
     * 过去只取了列名。这里一并留下，不产生额外的远程调用。
     */
    private record Loaded(SchemaTable table, TableDescriptor descriptor) {
    }

    /** 单张表加载失败返回 null，由调用方归入 unresolved。 */
    private Loaded loadOne(QualifiedObjectName wanted) {
        String schema = wanted.getSchemaName() == null || wanted.getSchemaName().isEmpty()
                ? DEFAULT_SCHEMA : wanted.getSchemaName();
        String tableName = wanted.getObjectName();

        try {
            Table table = tableCatalog.loadTable(NameIdentifier.of(schema, tableName));

            List<String> columns = new ArrayList<>();
            Map<String, ColumnDescriptor> columnDescriptors = new LinkedHashMap<>();
            for (org.apache.gravitino.rel.Column column : table.columns()) {
                columns.add(column.name());
                columnDescriptors.put(TableDescriptor.normalizeColumnKey(column.name()),
                        ColumnDescriptor.of(
                                column.dataType() == null ? null : column.dataType().simpleString(),
                                column.comment()));
            }

            List<String> partitionColumns = new ArrayList<>();
            Transform[] partitioning = table.partitioning();
            if (partitioning != null) {
                for (Transform transform : partitioning) {
                    partitionColumns.add(transform.name());
                }
            }

            // 必须走 SchemaTables：空 catalog 要归一成 null，否则分析器按字符串匹配时找不到
            return new Loaded(
                    SchemaTables.of(wanted, schema, tableName, columns, partitionColumns),
                    // 表类型留空：Gravitino 没有数仓表类型（全量/拉链）这个概念
                    new TableDescriptor(table.comment(), null, null, columnDescriptors));
        } catch (Exception e) {
            logger.debug("Gravitino 中不存在表 {}.{}: {}", schema, tableName, e.getMessage());
            return null;
        }
    }
}
