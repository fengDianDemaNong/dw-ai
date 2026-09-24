package com.dwai.lineage.service.metadata;

import io.github.melin.sqlflow.analyzer.Analysis;
import io.github.melin.sqlflow.analyzer.Output;
import io.github.melin.sqlflow.analyzer.OutputColumn;
import io.github.melin.sqlflow.analyzer.StatementAnalyzer;
import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.sqlflow.metadata.SimpleMetadataService;
import io.github.melin.sqlflow.parser.SqlFlowParser;
import io.github.melin.sqlflow.util.JsonUtils;
import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.common.relational.create.CreateTableAsSelect;
import io.github.melin.superior.common.relational.dml.InsertTable;
import io.github.melin.superior.common.relational.dml.MergeTable;
import org.apache.commons.lang3.StringUtils;
import com.dwai.lineage.service.metadata.provider.CompositeMetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataResolution;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Collections.emptyMap;

/**
 * 血缘解析主流程，与元数据来源解耦。
 *
 * <pre>
 * SQL 文本
 *   → superior-sql-parser 按方言拆分语句
 *   → 挑出 insert / CTAS / merge
 *   → sqlflow 解析成 AST
 *   → MetadataProvider 批量补全表结构
 *   → StatementAnalyzer 产出列级血缘
 * </pre>
 *
 * <p>此前每种元数据来源都复制一份该流程，新增来源要改主流程。
 * 现在来源由 {@link MetadataProvider} 注入，流程只有这一份。
 */
public class LineageAnalysisPipeline {

    private static final Logger logger = LoggerFactory.getLogger(LineageAnalysisPipeline.class);

    private static final String DEFAULT_SCHEMA = "default";

    /**
     * 解析器装上可中断的检查点。
     *
     * <p>{@code SqlFlowParser} 的这个构造参数是「每次建好 lexer/parser 后的初始化钩子」，
     * 正好用来挂 {@link ParseCancellation} —— 否则解析超时后线程会一直空转下去，
     * 见那个类的说明。
     */
    private final SqlFlowParser sqlFlowParser =
            new SqlFlowParser((lexer, parser) -> ParseCancellation.install(parser));

    /**
     * @param statements       superior-sql-parser 拆分出的语句
     * @param providerFactory  由已解析语句构造元数据来源（DDL provider 需要看到 CreateTable）
     */
    public LineageResult analyze(List<Statement> statements, ProviderFactory providerFactory) {
        List<String> dmlSqls = extractDmlSqls(statements);
        if (dmlSqls.isEmpty()) {
            return new LineageResult(List.of(), Set.of(), List.of());
        }

        List<io.github.melin.sqlflow.tree.statement.Statement> flowStatements =
                sqlFlowParser.createStatements(dmlSqls);

        MetadataProvider provider = providerFactory.create(statements, flowStatements);
        Set<QualifiedObjectName> referenced = collectReferencedTables(flowStatements);
        // CTAS 的目标表是这条语句现建的，预先给它结构会让 sqlflow 判定「表已存在」而整条丢弃
        referenced.removeAll(createTableAsSelectTargets(statements));
        MetadataResolution resolution = provider.resolve(referenced);

        SimpleMetadataService metadataService = new SimpleMetadataService(DEFAULT_SCHEMA);
        for (SchemaTable table : resolution.resolved().values()) {
            metadataService.addTableMetadata(table);
        }

        List<String> warnings = new ArrayList<>(resolution.warnings());
        List<StatementFailure> failures = new ArrayList<>();
        List<String> lineages = analyzeSequentially(
                flowStatements, dmlSqls, metadataService, failures);

        if (!resolution.unresolved().isEmpty()) {
            logger.info("以下表结构未能解析，select * 等场景可能缺列: {}", resolution.unresolved());
        }

        return new LineageResult(lineages, resolution.unresolved(), warnings, failures,
                resolution.descriptors());
    }

    /**
     * 逐条分析，并把每条 CTAS 现建出来的表<b>登记进元数据服务</b>再分析下一条。
     *
     * <p>不用 {@code analyzeMultipleDetailed} 一次跑完，就是为了插进这一步登记。
     * sqlflow 分析完 CTAS 不会把新表加回元数据服务，于是
     *
     * <pre>
     * create table tmp.bb as select * from a;      -- 通过
     * insert into sink_b select * from tmp.bb;     -- table tmp.bb metadata not exists
     * </pre>
     *
     * 第二条必然失败。而先把 {@code tmp.bb} 喂进去也不行 ——
     * 那样第一条会报 {@code Destination table already exists}（见
     * {@link #createTableAsSelectTargets}）。两头堵死，只能按语句顺序边分析边登记，
     * 这也正是这段 SQL 真实的执行顺序。
     *
     * <p>逐条构造 {@code StatementAnalyzer} 与 {@code analyzeMultipleDetailed} 内部的做法一致
     * （它本来就是每条新建一个），所以没有额外开销。
     */
    private List<String> analyzeSequentially(
            List<io.github.melin.sqlflow.tree.statement.Statement> flowStatements,
            List<String> dmlSqls,
            SimpleMetadataService metadataService,
            List<StatementFailure> failures) {

        List<String> lineages = new ArrayList<>();
        for (int i = 0; i < flowStatements.size(); i++) {
            io.github.melin.sqlflow.tree.statement.Statement statement = flowStatements.get(i);
            String sql = i < dmlSqls.size() ? dmlSqls.get(i) : "";
            try {
                Analysis statementAnalysis = new Analysis(statement, emptyMap());
                new StatementAnalyzer(statementAnalysis, metadataService, sqlFlowParser)
                        .analyze(statement, Optional.empty());

                Optional<Output> target = statementAnalysis.getTarget();
                if (target.isPresent()) {
                    lineages.add(JsonUtils.toJSONString(target.get()));
                    registerCreatedTable(metadataService, statement, target.get());
                }
            } catch (Exception e) {
                // 单条失败不影响其余语句，但要把原因带回去
                failures.add(new StatementFailure(i, sql, e.getMessage()));
            }
        }
        return lineages;
    }

    /**
     * 把 CTAS 现建出来的表登记进元数据服务，供后续语句查询。
     *
     * <p>只登记 CTAS：{@code insert into} 的目标表本来就已存在，重复登记会用
     * 「本次写入的列」覆盖掉它真实的表结构 —— 只写了部分列的 insert 会把其余列抹掉。
     */
    private static void registerCreatedTable(
            SimpleMetadataService metadataService,
            io.github.melin.sqlflow.tree.statement.Statement statement,
            Output output) {
        if (!(statement instanceof io.github.melin.sqlflow.tree.statement.CreateTableAsSelect)
                || output.getColumns().isEmpty()) {
            return;
        }
        List<String> columns = output.getColumns().get().stream()
                .map(OutputColumn::getColumn)
                .toList();
        // catalog 必须保持 null 而不是空串，否则表名会多出一个前导点而永远匹配不上，
        // 见 SchemaTables 的类注释
        String catalog = StringUtils.isBlank(output.getCatalogName()) ? null : output.getCatalogName();
        metadataService.addTableMetadata(
                new SchemaTable(catalog, output.getSchema(), output.getTable(), columns));
    }

    /**
     * CTAS 现建出来的目标表。
     *
     * <p>{@code SqlMetadataExtractor} 把它也算作「引用到的表」，而链路末端的
     * {@link com.dwai.lineage.service.metadata.provider.InferredMetadataProvider} 是<b>无条件</b>兜底的 ——
     * 它会照着 select 列表给这张还不存在的表推断出一份结构。结构一旦进了
     * {@code SimpleMetadataService}，sqlflow 分析这条 CTAS 时就报
     * {@code Destination table 'x' already exists} 并把<b>整条语句丢掉</b>。
     *
     * <p>后果是 CTAS 的血缘一条也拿不到。对
     * {@code create table tmp.bb as select * from a; insert into b select * from tmp.bb;}
     * 这种 ETL 写法尤其致命：{@code a → tmp.bb} 那一段没了，
     * 过滤临时表时想桥接也无从接起，图会直接断成空。
     *
     * <p>排除之后 {@code tmp.bb} 的结构由 sqlflow 分析 CTAS 时自己登记，
     * 后面的 {@code insert ... from tmp.bb} 照样查得到。
     */
    private static Set<QualifiedObjectName> createTableAsSelectTargets(List<Statement> statements) {
        Set<QualifiedObjectName> targets = new LinkedHashSet<>();
        for (Statement statement : statements) {
            if (!(statement instanceof CreateTableAsSelect ctas)) {
                continue;
            }
            String schema = ctas.getTableId().getSchemaName();
            targets.add(new QualifiedObjectName(
                    ctas.getTableId().getCatalogName() == null
                            ? "" : ctas.getTableId().getCatalogName(),
                    schema == null || schema.isBlank() ? DEFAULT_SCHEMA : schema,
                    ctas.getTableId().getTableName()));
        }
        return targets;
    }

    /** 只有写入类语句才产生血缘。 */
    private static List<String> extractDmlSqls(List<Statement> statements) {
        List<String> sqls = new ArrayList<>();
        for (Statement statement : statements) {
            if (statement instanceof InsertTable
                    || statement instanceof CreateTableAsSelect
                    || statement instanceof MergeTable) {
                if (StringUtils.isNotBlank(statement.getSql())) {
                    sqls.add(statement.getSql());
                }
            }
        }
        return sqls;
    }

    /**
     * 收集语句中引用到的全部表，供元数据来源一次性批量解析。
     *
     * <p>复用 sqlflow 的 {@code SqlMetadataExtractor} 做表名收集：它遍历 AST 得到
     * 每条语句涉及的表，这里只取表名，列信息由各 provider 提供。
     */
    private static Set<QualifiedObjectName> collectReferencedTables(
            List<io.github.melin.sqlflow.tree.statement.Statement> flowStatements) {
        Set<QualifiedObjectName> tables = new LinkedHashSet<>();
        for (io.github.melin.sqlflow.tree.statement.Statement statement : flowStatements) {
            io.github.melin.sqlflow.metadata.SqlMetadataExtractor extractor =
                    new io.github.melin.sqlflow.metadata.SqlMetadataExtractor();
            try {
                extractor.clearContext();
                extractor.process(statement);
                for (SchemaTable table : extractor.getTables()) {
                    tables.add(new QualifiedObjectName(
                            table.getCatalogName() == null ? "" : table.getCatalogName(),
                            table.getSchemaName() == null ? DEFAULT_SCHEMA : table.getSchemaName(),
                            table.getTableName()));
                }
            } catch (Exception e) {
                logger.debug("收集引用表失败，跳过该语句: {}", e.getMessage());
            } finally {
                extractor.removeContext();
            }
        }
        return tables;
    }

    /** 由已解析的语句构造元数据来源。 */
    @FunctionalInterface
    public interface ProviderFactory {
        MetadataProvider create(List<Statement> statements,
                                List<io.github.melin.sqlflow.tree.statement.Statement> flowStatements);
    }

    /** 便捷方法：把多个 provider 按优先级串起来。 */
    public static MetadataProvider chain(MetadataProvider... providers) {
        List<MetadataProvider> list = new ArrayList<>();
        for (MetadataProvider p : providers) {
            if (p != null) {
                list.add(p);
            }
        }
        return new CompositeMetadataProvider(list);
    }

    /** 单条语句解析失败的信息。 */
    public record StatementFailure(int index, String sql, String message) {}

    /**
     * 血缘解析结果。
     *
     * @param descriptors 各表的描述属性（中文名、字段类型…），由元数据来源顺带带出来。
     *                    <b>血缘本身用不到它</b>，纯粹是搭便车传给保存那一步随血缘落库，
     *                    理由见 {@link com.dwai.lineage.service.metadata.provider.TableDescriptor}。
     *                    key 是解析器眼里的表名，<b>可能只有两段</b>，
     *                    落库前要与图上的名字一起规范化 —— 见 {@code LineageServiceImpl}
     */
    public record LineageResult(List<String> lineages,
                                Set<QualifiedObjectName> unresolvedTables,
                                List<String> warnings,
                                List<StatementFailure> failures,
                                Map<QualifiedObjectName, TableDescriptor> descriptors) {

        public LineageResult {
            descriptors = descriptors == null ? Map.of() : descriptors;
        }

        public LineageResult(List<String> lineages,
                             Set<QualifiedObjectName> unresolvedTables,
                             List<String> warnings) {
            this(lineages, unresolvedTables, warnings, List.of(), Map.of());
        }

        public LineageResult(List<String> lineages,
                             Set<QualifiedObjectName> unresolvedTables,
                             List<String> warnings,
                             List<StatementFailure> failures) {
            this(lineages, unresolvedTables, warnings, failures, Map.of());
        }
    }
}
