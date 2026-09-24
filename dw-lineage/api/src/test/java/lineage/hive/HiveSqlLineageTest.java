package lineage.hive;


import io.github.melin.sqlflow.analyzer.Analysis;
import io.github.melin.sqlflow.analyzer.StatementAnalyzer;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.sqlflow.parser.SqlFlowParser;
import io.github.melin.sqlflow.tree.statement.EmptyStatement;
import io.github.melin.sqlflow.tree.statement.Statement;
import io.github.melin.sqlflow.util.JsonUtils;
import io.github.melin.superior.common.relational.create.CreateTable;
import io.github.melin.superior.common.relational.create.CreateTableAsSelect;
import io.github.melin.superior.common.relational.dml.InsertTable;
import io.github.melin.superior.common.relational.dml.MergeTable;
import io.github.melin.superior.parser.spark.SparkSqlHelper;
import lineage.AbstractSqlLineageTest;
import org.apache.commons.lang3.StringUtils;
import org.junit.Assert;
import org.junit.Test;
import com.dwai.lineage.service.metadata.JdbcQueryMetadataService;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.util.FileUtils;
import com.google.common.collect.Lists;
import com.dwai.lineage.util.SQLLineageMerger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static java.util.Collections.emptyMap;

/**
 * @description:
 * @projectName:demo
 * @see:lineage.hive
 * @author:wang
 * @createTime:2025/3/27 11:31
 * @version:1.0
 */
public class HiveSqlLineageTest extends AbstractSqlLineageTest {
    protected static final SqlFlowParser SQL_PARSER = new SqlFlowParser();
    @Test
    public void testInsert() throws Exception {
        String sql = "insert overwrite table dws.dws_dq_evt_record_info_da partition (pt='20230416000000')\n" +
                "select    \n" +
                "    database_name\n" +
                "    ,t1.table_id\n" +
                "    ,table_name\n" +
                "    ,pid\n" +
                "    ,dp\n" +
                "    ,evt_id\n" +
                "    ,evt_records\n" +
                "    ,round(cast(t1.evt_records as double)/t2.table_records,6) as evt_rate\n" +
                "    ,table_records day_rise_records\n" +
                "    ,day_rise_size\n" +
                "    ,day_rise_size_desc\n" +
                "    ,total_records\n" +
                "    ,total_size\n" +
                "    ,total_size_desc\n" +
                "from\n" +
                "(\n" +
                "    select\n" +
                "        database_name\n" +
                "        ,table_id\n" +
                "        ,table_name\n" +
                "        ,pid\n" +
                "        ,dp\n" +
                "        ,evt_id\n" +
                "        ,evt_records\n" +
                "        ,day_rise_records\n" +
                "        ,day_rise_size\n" +
                "        ,day_rise_size_desc\n" +
                "        ,total_records\n" +
                "        ,total_size\n" +
                "        ,total_size_desc\n" +
                "    from tmp.tmp_record_info_sql\n" +
                ") t1\n" +
                "left join \n" +
                "(\n" +
                "    select \n" +
                "        table_id\n" +
                "        ,sum(evt_records) table_records\n" +
                "    from tmp.tmp_record_info_sql\n" +
                "    group by table_id\n" +
                ") t2 \n" +
                "on t1.table_id=t2.table_id";
        Statement statement = SQL_PARSER.createStatement(sql);

        Analysis analysis = new Analysis(statement, emptyMap());
        StatementAnalyzer statementAnalyzer = new StatementAnalyzer(analysis, new HiveMetadataService(), SQL_PARSER);

        statementAnalyzer.analyze(statement, Optional.empty());

        System.out.println(JsonUtils.toJSONString(analysis.getTarget().get()));
        System.out.println("=========================");
        System.out.println(JsonUtils.toJSONString(analysis.getTarget().get()));
    }


    /**
     * 多语句脚本必须先用 superior-sql-parser 拆分、过滤出 DML，再交给 sqlflow。
     *
     * <p>此前这里直接把整个脚本（含 {@code set hive.exec.orc.split.strategy=BI;}、
     * {@code drop table} 等）传给只能解析单条语句的 {@code createStatement()}，
     * 必然在 {@code set} 处报 mismatched input —— 是测试用错了 API，
     * 不是 sqlflow 不支持这类脚本。生产路径 {@code MetadataServiceFactory} 走的就是下面这条链路。
     */
    @Test
    public void testMultiStatement() throws Exception {
        String script = FileUtils.readClasspathFileSafely("hive_test.sql");

        List<io.github.melin.superior.common.relational.Statement> statements =
                SparkSqlHelper.parseMultiStatement(script);

        List<String> dmlSqls = new ArrayList<>();
        for (io.github.melin.superior.common.relational.Statement statement : statements) {
            if (statement instanceof InsertTable
                    || statement instanceof CreateTableAsSelect
                    || statement instanceof MergeTable) {
                if (StringUtils.isNotBlank(statement.getSql())) {
                    dmlSqls.add(statement.getSql());
                }
            }
        }
        Assert.assertFalse("脚本中应能识别出可解析血缘的 DML 语句", dmlSqls.isEmpty());

        // set / drop 等非 DML 语句已被过滤，不会再进入 sqlflow
        for (String sql : dmlSqls) {
            Assert.assertFalse("过滤后不应残留 set 语句",
                    sql.trim().toLowerCase().startsWith("set "));
        }

        // 走生产入口做端到端验证：表结构由 SQL 内的 DDL + SqlMetadataExtractor 推断补齐
        List<String> lineages = new MetadataServiceFactory()
                .getLineageStrList("hive", true, script);
        Assert.assertFalse("含 set 的多语句脚本应能解析出血缘", lineages.isEmpty());

        String merged = SQLLineageMerger.mergeSQLLineage(lineages, null);
        Assert.assertNotNull(merged);
        Assert.assertTrue("合并结果应包含目标表",
                merged.contains("tmp.tmp_record_info_sql"));
    }

    @Test
    public void test02() throws IOException {
        String script = FileUtils.readClasspathFileSafely("hive_sql01.sql").trim();

        // sqlflow 只支持 insert as select 语句血缘解析，create table语句需要先解析出元数据信息
        JdbcQueryMetadataService metadataService = new JdbcQueryMetadataService();
        final List<io.github.melin.superior.common.relational.Statement> statements = SparkSqlHelper.parseMultiStatement(script);
        List<SchemaTable> tables = Lists.newArrayList();

        for (io.github.melin.superior.common.relational.Statement statement : statements) {
            if (statement instanceof CreateTable) {
                CreateTable createTable = (CreateTable) statement;
                List<String> columns = createTable.getColumnRels().stream()
                        .map(column -> column.getColumnName())
                        .collect(Collectors.toList());
                SchemaTable table = new SchemaTable(
                        createTable.getTableId().getCatalogName(),
                        createTable.getTableId().getSchemaName(),
                        createTable.getTableId().getTableName(),
                        columns
                );
                tables.add(table);
            }
            if (statement instanceof CreateTableAsSelect) {
                CreateTableAsSelect createTable = (CreateTableAsSelect) statement;
                List<String> columns = new ArrayList<>();
                SchemaTable table = new SchemaTable(
                        createTable.getTableId().getSchemaName(),
                        createTable.getTableId().getSchemaName(),
                        createTable.getTableId().getTableName(),
                        columns
                );
                tables.add(table);
            }
        }
        metadataService.addTableMetadata(tables);

        List<String> inputs =new ArrayList<String>();
        for (io.github.melin.superior.common.relational.Statement statement : statements) {
            if (statement instanceof InsertTable ) {
                InsertTable insertTable = (InsertTable) statement;
                String sql = insertTable.getSql();
                Statement stat = SQL_PARSER.createStatement(sql);
                Analysis analysis = new Analysis(stat, emptyMap());
                StatementAnalyzer statementAnalyzer = new StatementAnalyzer(
                        analysis,
                        metadataService,
                        SQL_PARSER
                );
                statementAnalyzer.analyze(stat, Optional.empty());

//                System.out.println(JsonUtils.toJSONString(analysis.getTarget().get()));
                inputs.add(JsonUtils.toJSONString(analysis.getTarget().get()));
            }
            if (statement instanceof CreateTableAsSelect ) {
                CreateTableAsSelect insertTable = (CreateTableAsSelect) statement;
                String sql = insertTable.getSql();
                Statement stat = SQL_PARSER.createStatement(sql);
                Analysis analysis = new Analysis(stat, emptyMap());
                StatementAnalyzer statementAnalyzer = new StatementAnalyzer(
                        analysis,
                        metadataService,
                        SQL_PARSER
                );
                statementAnalyzer.analyze(stat, Optional.empty());

//                System.out.println(JsonUtils.toJSONString(analysis.getTarget().get()));
                System.out.println("==========CreateTableAsSelect===============");
                System.out.println(JsonUtils.toJSONString(analysis.getTarget().get()));
                inputs.add(JsonUtils.toJSONString(analysis.getTarget().get()));
            }


        }
        System.out.println("===================");
//        System.out.println(inputs);
//        System.out.println(merged);
    }
    @Test
    public void testParseMultipleStatements() throws IOException {
        String script = FileUtils.readClasspathFileSafely("hive_sql01.sql").trim();

        // sqlflow 只支持 insert as select 语句血缘解析，create table语句需要先解析出元数据信息
        JdbcQueryMetadataService metadataService = new JdbcQueryMetadataService();
        final List<io.github.melin.superior.common.relational.Statement> statements = SparkSqlHelper.parseMultiStatement(script);
        List<SchemaTable> tables = Lists.newArrayList();
        List<String> sqlList=Lists.newArrayList();

        for (io.github.melin.superior.common.relational.Statement statement : statements) {
            if (statement instanceof CreateTable) {
                CreateTable createTable = (CreateTable) statement;
                List<String> columns = createTable.getColumnRels().stream()
                        .map(column -> column.getColumnName())
                        .collect(Collectors.toList());
                SchemaTable table = new SchemaTable(
                        createTable.getTableId().getCatalogName(),
                        createTable.getTableId().getSchemaName(),
                        createTable.getTableId().getTableName(),
                        columns
                );
                tables.add(table);
            }
            if(statement instanceof InsertTable
                    || statement instanceof CreateTableAsSelect
                    ||statement instanceof MergeTable){
                if(StringUtils.isNotBlank(statement.getSql())){
                    sqlList.add(statement.getSql());
                }
            }
        }
        metadataService.addTableMetadata(tables);

        // 使用新方法解析多个SQL语句
        List<Statement> multipleStatement = SQL_PARSER.createStatements(sqlList);

        // 创建StatementAnalyzer实例
        StatementAnalyzer statementAnalyzer = new StatementAnalyzer(
                new Analysis(new EmptyStatement(), emptyMap()), // 创建一个临时Analysis对象
                metadataService,
                SQL_PARSER
        );

        // 使用analyzeMultiple方法处理多个语句
        List<String> lineageStr = statementAnalyzer.analyzeMultiple(multipleStatement, Optional.empty());
        // 处理分析结果
//        System.out.println("++++++++++合并后的结果+++++++++++");
        String result = SQLLineageMerger.mergeSQLLineage(lineageStr,"evt_id");
        System.out.println(result);
    }
}
