package io.github.melin.sqlflow.parser.flink

import io.github.melin.sqlflow.analyzer.Analysis
import io.github.melin.sqlflow.analyzer.StatementAnalyzer
import io.github.melin.sqlflow.metadata.SchemaTable
import io.github.melin.sqlflow.metadata.SimpleMetadataService
import io.github.melin.sqlflow.parser.SqlFlowParser
import io.github.melin.sqlflow.tree.statement.EmptyStatement
import io.github.melin.sqlflow.util.JsonUtils
import io.github.melin.superior.common.relational.create.CreateTable
import io.github.melin.superior.common.relational.create.CreateTableAsSelect
import io.github.melin.superior.common.relational.dml.InsertTable
import io.github.melin.superior.common.relational.dml.MergeTable
import io.github.melin.superior.common.relational.table.ColumnRel
import io.github.melin.superior.parser.flink.FlinkSqlHelper
import io.github.melin.superior.parser.spark.SparkSqlHelper.parseMultiStatement
import lineage.hive.HiveSqlLineageTest
import org.apache.commons.lang3.StringUtils
import org.assertj.core.util.Lists
import org.junit.Test
import com.dwai.lineage.service.metadata.JdbcQueryMetadataService
import com.dwai.lineage.util.FileUtils
import com.dwai.lineage.util.SQLLineageMerger
import java.io.IOException
import java.util.*
import java.util.stream.Collectors

class FlinkSqlLineageTest1 {

    protected val SQL_PARSER = SqlFlowParser()

    @Test
    @Throws(Exception::class)
    fun testInsertInto() {
        val script = FileUtils.readClasspathFileSafely("flink_sql1.sql").trimIndent()

        // sqlflow 只支持 insert as select 语句血缘解析，create table语句需要先解析出元数据信息
        val metadataService = SimpleMetadataService("default")
        val statements = FlinkSqlHelper.parseMultiStatement(script)
        val tables = Lists.newArrayList<SchemaTable>()
        for (statement in statements) {
            if (statement is CreateTable) {
                val columns = statement.columnRels?.map { it.columnName }
                val table = SchemaTable(
                    "default",
                    statement.tableId.getFullTableName(),
                    columns
                )
                tables.add(table)
            }
        }
        metadataService.addTableMetadata(tables)
        // 实例演示，设置设置表元数据，生产环境定制MetadataService，从元数据系统查询表字段信息。
        val columns = Lists.newArrayList<String>()
        columns.add("hour")
        columns.add("orders_num")
        val table =
            SchemaTable("default", "buy_cnt_per_hour", columns)
        metadataService.addTableMetadata(table)


        for (statement in statements) {
            if (statement is InsertTable) {
                val stat = SQL_PARSER.createStatement(statement.getSql())
                val analysis = Analysis(stat, emptyMap())
                val statementAnalyzer = StatementAnalyzer(
                    analysis,
                    metadataService,
                    SQL_PARSER
                )
                statementAnalyzer.analyze(stat, Optional.empty())
//
                System.out.println(JsonUtils.toJSONString(analysis.target.get()))
                println("=========================")
                System.out.println(JsonUtils.toJSONString(analysis.target.get()))

            }
        }
    }


    @Test
    @Throws(IOException::class)
    fun testParseMultipleStatements() {
        val script = FileUtils.readClasspathFileSafely("flink_sql1.sql").trim { it <= ' ' }

        // sqlflow 只支持 insert as select 语句血缘解析，create table语句需要先解析出元数据信息
        val metadataService = SimpleMetadataService("default")
        val statements = FlinkSqlHelper.parseMultiStatement(script)
        val tables: MutableList<SchemaTable> = com.google.common.collect.Lists.newArrayList()
        val sqlList: MutableList<String> = com.google.common.collect.Lists.newArrayList()

        for (statement in statements) {
            if (statement is CreateTable) {
                val createTable = statement
                val columns = createTable.columnRels!!.stream()
                    .map { column: ColumnRel -> column.columnName }
                    .collect(Collectors.toList())
                var schemaName=createTable.tableId.schemaName
                if(schemaName==null){
                    schemaName="default"
                }
                val table = SchemaTable(
                    createTable.tableId.catalogName,
                    schemaName,
                    createTable.tableId.tableName,
                    columns
                )
                tables.add(table)
            }
            if (statement is InsertTable
                || statement is CreateTableAsSelect
                || statement is MergeTable
            ) {
                if (StringUtils.isNotBlank(statement.getSql())) {
                    sqlList.add(statement.getSql())
                }
            }
        }
        metadataService.addTableMetadata(tables)

        // 使用新方法解析多个SQL语句
        val multipleStatement = SQL_PARSER.createStatements(sqlList)

        // 创建StatementAnalyzer实例
        val statementAnalyzer = StatementAnalyzer(
            Analysis(EmptyStatement(), emptyMap()),  // 创建一个临时Analysis对象
            metadataService,
            SQL_PARSER
        )

        // 使用analyzeMultiple方法处理多个语句
        val lineageStr = statementAnalyzer.analyzeMultiple(multipleStatement, Optional.empty())
        // 处理分析结果
//        System.out.println("++++++++++合并后的结果+++++++++++");
        val result = SQLLineageMerger.mergeSQLLineage(lineageStr, null)
        println(result)
    }
}