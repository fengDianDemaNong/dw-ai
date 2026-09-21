package com.dwai.lineage.service;

import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.common.relational.TableId;
import io.github.melin.superior.common.relational.create.CreateTableAsSelect;
import io.github.melin.superior.common.relational.dml.InsertTable;
import io.github.melin.superior.common.relational.dml.MergeTable;
import io.github.melin.superior.common.relational.dml.QueryStmt;
import com.dwai.lineage.dto.TableLineageResponse;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 表级血缘：只看「哪张表写入、来自哪些表」，不下钻到字段。
 *
 * <p>与列级血缘的区别是<b>不需要表结构元数据</b>，因此更快、更稳，
 * 在元数据服务不可用或方言不支持 DDL 提取时仍可给出结果。
 */
@Service
public class TableLineageService {

    private static final Logger logger = LoggerFactory.getLogger(TableLineageService.class);

    private final MetadataServiceFactory metadataServiceFactory;
    private final SqlParseExecutor parseExecutor;

    public TableLineageService(MetadataServiceFactory metadataServiceFactory,
                               SqlParseExecutor parseExecutor) {
        this.metadataServiceFactory = metadataServiceFactory;
        this.parseExecutor = parseExecutor;
    }

    public TableLineageResponse analyze(String databaseType, String sql) {
        long startedAt = System.currentTimeMillis();
        try {
            List<Statement> statements = parseExecutor.call(
                    "表级血缘(" + databaseType + ")",
                    () -> metadataServiceFactory.statements(databaseType, sql));

            List<TableLineageResponse.StatementLineage> perStatement = new ArrayList<>();
            Set<String> nodes = new LinkedHashSet<>();
            Set<TableLineageResponse.Edge> edges = new LinkedHashSet<>();

            int index = 0;
            for (Statement statement : statements) {
                index++;
                List<String> outputs = outputsOf(statement);
                List<String> inputs = inputsOf(statement);
                if (outputs.isEmpty() && inputs.isEmpty()) {
                    continue;
                }

                perStatement.add(new TableLineageResponse.StatementLineage(
                        index, statement.getStatementType().name(), outputs, inputs));

                nodes.addAll(outputs);
                nodes.addAll(inputs);
                for (String target : outputs) {
                    for (String source : inputs) {
                        edges.add(new TableLineageResponse.Edge(source, target));
                    }
                }
            }

            logger.info("表级血缘解析完成, dbType={}, 语句={}, 表={}, 边={}, costMs={}",
                    databaseType, perStatement.size(), nodes.size(), edges.size(),
                    System.currentTimeMillis() - startedAt);

            return new TableLineageResponse(perStatement, new ArrayList<>(nodes), new ArrayList<>(edges));
        } catch (IllegalArgumentException e) {
            throw new SqlParseException("不支持的数据库类型: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.debug("表级血缘解析失败的 SQL (dbType={}):\n{}", databaseType, sql);
            throw new SqlParseException("SQL解析失败: " + e.getMessage(), e);
        }
    }

    /** 写入的目标表。 */
    private static List<String> outputsOf(Statement statement) {
        List<String> outputs = new ArrayList<>();
        if (statement instanceof InsertTable insert) {
            add(outputs, insert.getTableId());
        } else if (statement instanceof CreateTableAsSelect ctas) {
            add(outputs, ctas.getTableId());
        } else if (statement instanceof MergeTable merge) {
            add(outputs, merge.getTargetTable());
        }
        return outputs;
    }

    /** 读取的来源表。 */
    private static List<String> inputsOf(Statement statement) {
        List<String> inputs = new ArrayList<>();
        if (statement instanceof InsertTable insert) {
            addAll(inputs, insert.getQueryStmt());
        } else if (statement instanceof CreateTableAsSelect ctas) {
            addAll(inputs, ctas.getQueryStmt());
        } else if (statement instanceof QueryStmt query) {
            addAll(inputs, query);
        } else if (statement instanceof MergeTable merge) {
            for (TableId t : merge.getInputTables()) {
                add(inputs, t);
            }
        }
        return inputs;
    }

    private static void addAll(List<String> target, QueryStmt query) {
        if (query == null || query.getInputTables() == null) {
            return;
        }
        for (TableId t : query.getInputTables()) {
            add(target, t);
        }
    }

    private static void add(List<String> target, TableId tableId) {
        if (tableId == null) {
            return;
        }
        String name = fullName(tableId);
        if (!name.isBlank() && !target.contains(name)) {
            target.add(name);
        }
    }

    /** 拼成 {@code [catalog.]schema.table}，缺失的层级直接省略。 */
    private static String fullName(TableId tableId) {
        StringBuilder sb = new StringBuilder();
        if (tableId.getCatalogName() != null && !tableId.getCatalogName().isBlank()) {
            sb.append(tableId.getCatalogName()).append('.');
        }
        if (tableId.getSchemaName() != null && !tableId.getSchemaName().isBlank()) {
            sb.append(tableId.getSchemaName()).append('.');
        }
        if (tableId.getTableName() != null) {
            sb.append(tableId.getTableName().trim());
        }
        return sb.toString();
    }
}
