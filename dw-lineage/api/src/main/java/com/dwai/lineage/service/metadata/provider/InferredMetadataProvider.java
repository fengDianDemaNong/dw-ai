package com.dwai.lineage.service.metadata.provider;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.sqlflow.metadata.SqlMetadataExtractor;
import io.github.melin.sqlflow.tree.statement.Statement;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从 SQL 语句本身推断表结构：SQL 里显式引用了哪些列，就认为表至少有这些列。
 *
 * <p>兜底来源，优先级最低。推断不出 {@code select *} 的完整列清单，
 * 但在没有任何外部元数据时仍能给出部分血缘，好过整条语句失败。
 */
public class InferredMetadataProvider implements MetadataProvider {

    private static final String DEFAULT_SCHEMA = "default";

    private final Map<QualifiedObjectName, SchemaTable> inferred;

    public InferredMetadataProvider(List<Statement> flowStatements) {
        this.inferred = extract(flowStatements);
    }

    private static Map<QualifiedObjectName, SchemaTable> extract(List<Statement> flowStatements) {
        Map<QualifiedObjectName, SchemaTable> result = new LinkedHashMap<>();
        if (flowStatements == null) {
            return result;
        }

        for (Statement statement : flowStatements) {
            SqlMetadataExtractor extractor = new SqlMetadataExtractor();
            try {
                // 清理上下文，确保开始处理前状态干净
                extractor.clearContext();
                extractor.process(statement);
                for (SchemaTable table : extractor.getTables()) {
                    result.putIfAbsent(
                            DdlMetadataProvider.key(table.getCatalogName(), table.getSchemaName(), table.getTableName()),
                            table);
                }
            } catch (Exception ignored) {
                // 推断失败不影响其它语句，这本就是尽力而为的兜底来源
            } finally {
                // 清理线程本地变量，避免内存泄漏
                extractor.removeContext();
            }
        }
        return result;
    }

    @Override
    public String name() {
        return "SQL结构推断";
    }

    @Override
    public MetadataResolution resolve(Set<QualifiedObjectName> tables) {
        Map<QualifiedObjectName, SchemaTable> resolved = new LinkedHashMap<>();
        Set<QualifiedObjectName> unresolved = new LinkedHashSet<>();

        for (QualifiedObjectName wanted : tables) {
            SchemaTable found = lookup(wanted);
            if (found != null) {
                resolved.put(wanted, found);
            } else {
                unresolved.add(wanted);
            }
        }
        return MetadataResolution.of(resolved, unresolved);
    }

    private SchemaTable lookup(QualifiedObjectName wanted) {
        SchemaTable direct = inferred.get(wanted);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<QualifiedObjectName, SchemaTable> entry : inferred.entrySet()) {
            QualifiedObjectName candidate = entry.getKey();
            String wantedSchema = wanted.getSchemaName() == null ? DEFAULT_SCHEMA : wanted.getSchemaName();
            if (candidate.getSchemaName().equalsIgnoreCase(wantedSchema)
                    && candidate.getObjectName().equalsIgnoreCase(wanted.getObjectName())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public Map<QualifiedObjectName, SchemaTable> allTables() {
        return inferred;
    }
}
