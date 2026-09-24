package com.dwai.lineage.service.impl;

import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import com.dwai.lineage.service.AnalyzedLineage;
import com.dwai.lineage.service.metadata.TableNameNormalizer;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.annotation.PostConstruct;
import com.dwai.lineage.dto.DialectInfo;
import com.dwai.lineage.dto.FailedStatement;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.enums.DatabaseTypeEnum;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.service.LineageService;
import com.dwai.lineage.service.SqlParseExecutor;
import com.dwai.lineage.service.metadata.LineageAnalysisPipeline;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import com.dwai.lineage.util.SQLLineageMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LineageServiceImpl implements LineageService {

    private static final Logger logger = LoggerFactory.getLogger(LineageServiceImpl.class);

    private final MetadataServiceFactory metadataServiceFactory;
    private final SqlParseExecutor parseExecutor;
    private List<String> supportedDatabases;

    public LineageServiceImpl(MetadataServiceFactory metadataServiceFactory,
                              SqlParseExecutor parseExecutor) {
        this.metadataServiceFactory = metadataServiceFactory;
        this.parseExecutor = parseExecutor;
    }

    @PostConstruct
    public void init() {
        supportedDatabases = DatabaseTypeEnum.getAllSupportedDatabases();
        logger.info("Supported databases initialized: {}", supportedDatabases);
    }

    @Override
    public List<String> getSupportedDatabases() {
        return supportedDatabases;
    }

    @Override
    public List<DialectInfo> getDialects() {
        return java.util.Arrays.stream(DatabaseTypeEnum.values())
                .map(DialectInfo::of)
                .toList();
    }

    @Override
    public LineageGraph analyzeSqlLineage(String databaseType, boolean useCreateTable, String columnName, String sql) {
        return analyzeSqlLineage(databaseType, useCreateTable, columnName, sql, null);
    }

    @Override
    public LineageGraph analyzeSqlLineage(String databaseType, boolean useCreateTable, String columnName,
                                          String sql, MetadataProvider external) {
        return analyzeSqlLineage(databaseType, useCreateTable, columnName, sql, external, null);
    }

    @Override
    public LineageGraph analyzeSqlLineage(String databaseType, boolean useCreateTable, String columnName,
                                          String sql, MetadataProvider external,
                                          com.dwai.lineage.service.metadata.CatalogPolicy policy) {
        return analyzeDetailed(databaseType, useCreateTable, columnName, sql, external, policy)
                .graph();
    }

    @Override
    public AnalyzedLineage analyzeDetailed(String databaseType, boolean useCreateTable, String columnName,
                                           String sql, MetadataProvider external,
                                           com.dwai.lineage.service.metadata.CatalogPolicy policy) {
        long startedAt = System.currentTimeMillis();
        logger.info("开始解析血缘, dbType={}, useCreateTable={}, 外部元数据={}, columnFilter={}, sqlLength={}",
                databaseType, useCreateTable, external == null ? "无" : external.name(),
                columnName, sql == null ? 0 : sql.length());

        try {
            // 放到专用线程执行：限制超时，并隔离深嵌套 SQL 可能引发的栈溢出。
            // external 在进入该线程前就已构造完毕，内部不读 ThreadLocal，故无需 wrap 租户上下文
            LineageAnalysisPipeline.LineageResult result = parseExecutor.call(
                    "列级血缘(" + databaseType + ")",
                    () -> metadataServiceFactory.analyze(databaseType, useCreateTable, sql, external));

            SQLLineageMerger.Diagnostics diagnostics = toDiagnostics(result);
            LineageGraph graph = SQLLineageMerger.buildGraph(
                    result.lineages(), columnName, diagnostics, policy);

            logger.info("血缘解析完成, dbType={}, 成功={}, 失败={}, 未解析表={}, costMs={}",
                    databaseType, result.lineages().size(), result.failures().size(),
                    result.unresolvedTables().size(), System.currentTimeMillis() - startedAt);
            return new AnalyzedLineage(graph, normalizeDescriptorKeys(result, policy));
        } catch (IllegalArgumentException e) {
            throw new SqlParseException("不支持的数据库类型: " + e.getMessage(), e);
        } catch (Exception e) {
            // SQL 原文可能含敏感数据，只在 debug 级别记录
            logger.debug("解析失败的 SQL (dbType={}):\n{}", databaseType, sql);
            logger.error("SQL 解析失败, dbType={}, costMs={}, cause={}",
                    databaseType, System.currentTimeMillis() - startedAt, e.getMessage(), e);
            throw new SqlParseException("SQL解析失败: " + e.getMessage(), e);
        }
    }

    /**
     * 把描述的 key 从「解析器眼里的表名」换成「最终落库的表全名」。
     *
     * <p>这一步不能省：provider 的 key 是 SQL 原文写法，可能只有两段
     * （{@code ods.orders}），而 {@link SQLLineageMerger} 会用
     * {@link TableNameNormalizer} 把图上的名字补成三段。两边不对齐的话，
     * 保存时按图上的名字一个描述也查不到。
     *
     * <p>用的是<b>同一个</b> {@code policy.defaultCatalog()} 和同一个规范化函数，
     * 所以不会漂。key 统一转小写，理由见 {@link AnalyzedLineage#descriptors}。
     */
    private static Map<String, TableDescriptor> normalizeDescriptorKeys(
            LineageAnalysisPipeline.LineageResult result,
            com.dwai.lineage.service.metadata.CatalogPolicy policy) {
        if (result.descriptors().isEmpty()) {
            return Map.of();
        }
        String defaultCatalog = policy == null ? null : policy.defaultCatalog();
        Map<String, TableDescriptor> out = new LinkedHashMap<>();
        result.descriptors().forEach((qualified, descriptor) -> {
            if (descriptor == null || descriptor.isEmpty()) {
                return;
            }
            String raw = tableNameOf(qualified);
            if (raw == null) {
                return;
            }
            out.put(AnalyzedLineage.normalizeKey(
                            TableNameNormalizer.normalizeTable(raw, defaultCatalog)),
                    descriptor);
        });
        return out;
    }

    /** {@code QualifiedObjectName} → {@code [catalog.]schema.table}，catalog 缺省时省略那一段。 */
    private static String tableNameOf(QualifiedObjectName qualified) {
        if (qualified == null || qualified.getObjectName() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (qualified.getCatalogName() != null && !qualified.getCatalogName().isBlank()) {
            sb.append(qualified.getCatalogName()).append('.');
        }
        if (qualified.getSchemaName() != null && !qualified.getSchemaName().isBlank()) {
            sb.append(qualified.getSchemaName()).append('.');
        }
        return sb.append(qualified.getObjectName()).toString();
    }

    /** 把流水线的诊断信息转成随响应返回的结构。 */
    static SQLLineageMerger.Diagnostics toDiagnostics(LineageAnalysisPipeline.LineageResult result) {
        List<FailedStatement> failures = result.failures().stream()
                .map(f -> FailedStatement.of(f.index(), f.sql(), f.message()))
                .toList();
        List<String> unresolved = result.unresolvedTables().stream()
                .map(Object::toString)
                .sorted()
                .toList();
        return new SQLLineageMerger.Diagnostics(failures, unresolved, result.warnings());
    }
}
