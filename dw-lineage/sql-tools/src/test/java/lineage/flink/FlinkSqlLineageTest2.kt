package io.github.melin.sqlflow.parser.flink

import io.github.melin.sqlflow.analyzer.Analysis
import io.github.melin.sqlflow.analyzer.StatementAnalyzer
import io.github.melin.sqlflow.metadata.SchemaTable
import io.github.melin.sqlflow.metadata.SimpleMetadataService
import io.github.melin.sqlflow.metadata.SqlMetadataExtractor
import io.github.melin.sqlflow.parser.SqlFlowParser
import io.github.melin.sqlflow.tree.statement.EmptyStatement
import io.github.melin.sqlflow.util.JsonUtils
import io.github.melin.superior.common.relational.create.CreateTable
import io.github.melin.superior.common.relational.create.CreateTableAsSelect
import io.github.melin.superior.common.relational.dml.InsertTable
import io.github.melin.superior.common.relational.dml.MergeTable
import io.github.melin.superior.parser.flink.FlinkSqlHelper
import org.apache.commons.lang3.StringUtils
import org.assertj.core.util.Lists
import org.junit.Assert
import org.junit.Test
import com.dwai.lineage.util.FileUtils
import java.io.IOException
import java.util.*

class FlinkSqlLineageTest2 {

    protected val SQL_PARSER = SqlFlowParser()

    @Test
    @Throws(Exception::class)
    fun testInsertInto() {
        val script = FileUtils.readClasspathFileSafely("flink_sql.sql").trimIndent()

        // sqlflow 只支持 insert as select 语句血缘解析，create table语句需要先解析出元数据信息
        val metadataService = SimpleMetadataService("fengchao_sync")
        val statements = FlinkSqlHelper.parseMultiStatement(script)
        val tables = Lists.newArrayList<SchemaTable>()
        for (statement in statements) {
            if (statement is CreateTable) {
                val columns = statement.columnRels?.map { it.columnName }
                val table = SchemaTable(
                    "fengchao_sync",
                    statement.tableId.getFullTableName(),
                    columns
                )
                tables.add(table)
            }
        }
        metadataService.addTableMetadata(tables)

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

                System.out.println(JsonUtils.toJSONString(analysis.target.get()))
            }
        }
    }


    @Test
    @Throws(IOException::class)
    fun test4() {
        val script = FileUtils.readClasspathFileSafely("flink_sql1.sql").trim { it <= ' ' }
        val statements = FlinkSqlHelper.parseMultiStatement(script)
        val sqlList: MutableList<String> = com.google.common.collect.Lists.newArrayList()
        for (statement in statements) {
            if (statement is InsertTable
                || statement is CreateTableAsSelect
                || statement is MergeTable
            ) {
                if (StringUtils.isNotBlank(statement.getSql())) {
                    sqlList.add(statement.getSql())
                }
            }
        }
        val metadataService = SimpleMetadataService("default")
        // 使用新方法解析多个SQL语句
        val multipleStatement = SQL_PARSER.createStatements(sqlList)
        for (statement in multipleStatement) {
            val extractor = SqlMetadataExtractor()
            // 清理上下文，确保开始处理前状态干净
            extractor.clearContext()
            extractor.process(statement)
            // 3. 创建 SimpleMetadataService 并添加元数据
            // 所有表和字段信息
            for (table in extractor.tables) {
                metadataService.addTableMetadata(table)
            }
            //清理线程本地变量，避免内存泄漏
            extractor.removeContext()
        }

        val statementAnalyzer = StatementAnalyzer(
            Analysis(EmptyStatement(), emptyMap()),  // 创建一个临时Analysis对象
            metadataService,
            SQL_PARSER
        )

        // 使用analyzeMultiple方法处理多个语句
        val lineageStr = statementAnalyzer.analyzeMultiple(multipleStatement, Optional.empty())
        for (result in lineageStr) {
            println("=========================")
            println(result)
        }
    }
}