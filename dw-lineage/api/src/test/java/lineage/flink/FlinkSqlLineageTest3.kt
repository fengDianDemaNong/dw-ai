package io.github.melin.sqlflow.parser.flink

import io.github.melin.sqlflow.analyzer.Analysis
import io.github.melin.sqlflow.analyzer.StatementAnalyzer
import io.github.melin.sqlflow.parser.SqlFlowParser
import io.github.melin.sqlflow.util.JsonUtils
import lineage.flink.FlinkMetadataService
import org.junit.Test
import java.util.*

class FlinkSqlLineageTest3 {

    protected val SQL_PARSER = SqlFlowParser()

    @Test
    @Throws(Exception::class)
    fun testInsertInto() {
        val script = """              
           INSERT overwrite TABLE ads_trans_elecric_w
           SELECT  t1.prov,
                current_timestamp() data_store_time,
                regexp_replace(t2.eltct_desc, 'sdf','') eltct_desc
           from dws_eltct_pro_w t1
           LEFT JOIN dwd_pub_fill_w t2
               t2 on t1.pro_id = t2.pro_id
           and t1.week_end = t2.week_end

        """.trimIndent()

        val statement = SQL_PARSER.createStatement(script)
        val analysis = Analysis(statement, emptyMap())
        val statementAnalyzer = StatementAnalyzer(
            analysis,
            FlinkMetadataService(), SQL_PARSER
        )
        statementAnalyzer.analyze(statement, Optional.empty())

        System.out.println(JsonUtils.toJSONString(analysis.target.get()))
    }
}