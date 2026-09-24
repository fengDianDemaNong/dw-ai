package lineage.hive;
import com.google.common.collect.Lists;
import io.github.melin.sqlflow.analyzer.Analysis;
import io.github.melin.sqlflow.analyzer.StatementAnalyzer;
import io.github.melin.sqlflow.metadata.MetadataService;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.sqlflow.metadata.SimpleMetadataService;
import io.github.melin.sqlflow.metadata.SqlMetadataExtractor;
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
import org.junit.Test;
import com.dwai.lineage.service.metadata.JdbcQueryMetadataService;
import com.dwai.lineage.util.FileUtils;
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
public class HiveSqlLineageTest2 extends AbstractSqlLineageTest {
    protected static final SqlFlowParser SQL_PARSER = new SqlFlowParser();
    @Test
    public void testInsert() throws Exception {
        String sql = "insert overwrite table dws.dws_dq_evt_record_info_da partition (pt='20230416000000')\n" +
                "select    \n" +
                "    database_name as db_name\n" +
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
        // 2. 使用访问者提取元数据
        SqlMetadataExtractor extractor = new SqlMetadataExtractor();
        extractor.process(statement);

        // 3. 创建 SimpleMetadataService 并添加元数据
        SimpleMetadataService metadataService = new SimpleMetadataService("default");

        // 所有表和字段信息
        for (SchemaTable table : extractor.getTables()) {
            metadataService.addTableMetadata(table);
        }
        Analysis analysis = new Analysis(statement, emptyMap());
        StatementAnalyzer statementAnalyzer = new StatementAnalyzer(analysis, metadataService, SQL_PARSER);
        statementAnalyzer.analyze(statement, Optional.empty());
        System.out.println("=========================");
        System.out.println(JsonUtils.toJSONString(analysis.getTarget().get()));

    }

    @Test
    public void test2() throws Exception {
        String sql = "insert overwrite table dws.dws_dq_evt_record_info_da partition (pt='20230416000000')\n" +
                "select    \n" +
                "    t1.*"+
                "    ,table_records\n" +
                "from\n" +
                "(\n" +
                "    select\n" +
                "        *"+
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
        // 2. 使用访问者提取元数据
        SqlMetadataExtractor extractor = new SqlMetadataExtractor();
        // 清理上下文，确保开始处理前状态干净
        extractor.clearContext();
        extractor.process(statement);
        // 3. 创建 SimpleMetadataService 并添加元数据
        MetadataService metadataService = extractor.getTableMetadata();
        //清理线程本地变量，避免内存泄漏
        extractor.removeContext();
        Analysis analysis = new Analysis(statement, emptyMap());
        StatementAnalyzer statementAnalyzer = new StatementAnalyzer(analysis, metadataService, SQL_PARSER);
        statementAnalyzer.analyze(statement, Optional.empty());
        System.out.println("=========================");
        System.out.println(JsonUtils.toJSONString(analysis.getTarget().get()));
        String jsonString = JsonUtils.toJSONString(analysis.getTarget().get());
        System.out.println("=========mergeSQLLineage========");
        String result = SQLLineageMerger.mergeSQLLineage(List.of(jsonString));

        System.out.println(result);
    }
    @Test
    public void test3() throws IOException {
        String script = FileUtils.readClasspathFileSafely("hive_sql01.sql").trim();
        final List<io.github.melin.superior.common.relational.Statement> statements = SparkSqlHelper.parseMultiStatement(script);
        List<SchemaTable> tables = Lists.newArrayList();
        List<String> sqlList=Lists.newArrayList();
        for (io.github.melin.superior.common.relational.Statement statement : statements) {
            if(statement instanceof InsertTable
                    || statement instanceof CreateTableAsSelect
                    ||statement instanceof MergeTable){
                if(StringUtils.isNotBlank(statement.getSql())){
                    sqlList.add(statement.getSql());
                }
            }
        }
        SimpleMetadataService metadataService = new SimpleMetadataService("default");
        // 使用新方法解析多个SQL语句
        List<Statement> multipleStatement = SQL_PARSER.createStatements(sqlList);
        for (Statement statement : multipleStatement) {
            SqlMetadataExtractor extractor = new SqlMetadataExtractor();
            // 清理上下文，确保开始处理前状态干净
            extractor.clearContext();
            extractor.process(statement);
            // 3. 创建 SimpleMetadataService 并添加元数据
            // 所有表和字段信息
            for (SchemaTable table : extractor.getTables()) {
                metadataService.addTableMetadata(table);
            }
            //清理线程本地变量，避免内存泄漏
            extractor.removeContext();
        }

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


    @Test
    public void test4() throws IOException {
        String script = FileUtils.readClasspathFileSafely("hive_sql01.sql").trim();
        final List<io.github.melin.superior.common.relational.Statement> statements = SparkSqlHelper.parseMultiStatement(script);
        List<String> sqlList=Lists.newArrayList();
        for (io.github.melin.superior.common.relational.Statement statement : statements) {
            if(statement instanceof InsertTable
                    || statement instanceof CreateTableAsSelect
                    ||statement instanceof MergeTable){
                if(StringUtils.isNotBlank(statement.getSql())){
                    sqlList.add(statement.getSql());
                }
            }
        }
        SimpleMetadataService metadataService = new SimpleMetadataService("default");
        // 使用新方法解析多个SQL语句
        List<Statement> multipleStatement = SQL_PARSER.createStatements(sqlList);
        for (Statement statement : multipleStatement) {
            SqlMetadataExtractor extractor = new SqlMetadataExtractor();
            // 清理上下文，确保开始处理前状态干净
            extractor.clearContext();
            extractor.process(statement);
            // 3. 创建 SimpleMetadataService 并添加元数据
            // 所有表和字段信息
            for (SchemaTable table : extractor.getTables()) {
                metadataService.addTableMetadata(table);
            }
            //清理线程本地变量，避免内存泄漏
            extractor.removeContext();
        }

        // 创建StatementAnalyzer实例
        StatementAnalyzer statementAnalyzer = new StatementAnalyzer(
                new Analysis(new EmptyStatement(), emptyMap()), // 创建一个临时Analysis对象
                metadataService,
                SQL_PARSER
        );

        // 使用analyzeMultiple方法处理多个语句
        List<String> lineageStr = statementAnalyzer.analyzeMultiple(multipleStatement, Optional.empty());
        for (String result : lineageStr) {
            System.out.println("=========================");
            System.out.println(result);

        }
    }

}
