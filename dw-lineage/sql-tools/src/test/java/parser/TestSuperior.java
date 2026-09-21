package parser;

import io.github.melin.superior.common.StatementType;
import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.parser.spark.SparkSqlHelper;
import com.github.melin.superior.sql.parser.mysql.MySqlHelper;
import io.github.melin.superior.parser.postgre.PostgreSqlHelper;
import io.github.melin.superior.common.relational.dml.InsertTable;
import io.github.melin.superior.common.relational.dml.QueryStmt;
import io.github.melin.superior.parser.appjar.AppJarHelper;
import io.github.melin.superior.parser.appjar.AppJarInfo;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TestSuperior {
    @Test
    public void testSparkSQL() {
        String sql = "select \n" +
                " bzdys, bzhyyh, bzdy, week, round((bzdy-bzdys)*100/bzdys, 2) \n" +
                "from (\n" +
                " select \n" +
                "  lag(bzdy) over (order by week) bzdys, bzhyyh, bzdy, week \n" +
                " from (\n" +
                "  select \n" +
                "   count(distinct partner_code) bzhyyh, count(1) bzdy, week \n" +
                "  from tdl_dt2x_table\n" +
                " ) a\n" +
                ") b limit 111";

        System.out.println(sql);
        Statement statement = SparkSqlHelper.parseStatement(sql);
        if (statement instanceof QueryStmt) {
            QueryStmt queryStmt = (QueryStmt) statement;
            assertEquals(StatementType.SELECT, queryStmt.getStatementType());
            assertEquals(1, queryStmt.getInputTables().size());
            assertEquals("tdl_dt2x_table", queryStmt.getInputTables().get(0).getTableName());
            assertEquals(111, queryStmt.getLimit().longValue());
        } else {
            fail();
        }
    }


    @Test
    public void setConfigTest5() {
        String sql =
                "set spark.shuffle.compress=true; " +
                        "set spark.rdd.compress=true; " +
                        "set spark.driver.maxResultSize=3g; " +
                        "set spark.serializer=org.apache.spark.serializer.KryoSerializer; " +
                        "set spark.kryoserializer.buffer.max=1024m; " +
                        "set spark.kryoserializer.buffer=256m; " +
                        "set spark.network.timeout=300s; " +
                        "createHfile-1.2-SNAPSHOT-jar-with-dependencies.jar " +
                        "imei_test.euSaveHBase " +
                        "gaea_offline:account_mobile " +
                        "sh " +
                        "md " +
                        "shda.interest_radar_mobile_score_dt " +
                        "20180318 " +
                        "/xiaoyong.fu/sh/mobile/loan " +
                        "400 " +
                        "'%7B%22job_type%22=' " +
                        "--jar";

        List<Statement> statementDatas = AppJarHelper.parseStatement(sql);
        assertEquals(8, statementDatas.size());

        Statement statement = statementDatas.get(7);
        if (statement instanceof AppJarInfo) {
            AppJarInfo appJarInfo = (AppJarInfo) statement;
            assertEquals(StatementType.APP_JAR, appJarInfo.getStatementType());
            assertEquals("createHfile-1.2-SNAPSHOT-jar-with-dependencies.jar", appJarInfo.getResourceName());
            assertEquals("imei_test.euSaveHBase", appJarInfo.getClassName());
            assertEquals("/xiaoyong.fu/sh/mobile/loan", appJarInfo.getParams().get(5));
            assertEquals("400", appJarInfo.getParams().get(6));
            assertEquals("%7B%22job_type%22=", appJarInfo.getParams().get(7));
            assertEquals("--jar", appJarInfo.getParams().get(8));
        } else {
            fail("Expected statement to be of type AppJarInfo");
        }
    }

    /**
     * insert ... select 在 MySQL 方言下返回 {@link InsertTable}，而非 {@link QueryStmt}。
     *
     * <p>原用例把它当 QueryStmt 判断因而直接 fail，且对同一个 statementType
     * 先断言 INSERT 又断言 SELECT，自相矛盾 —— 是用例写错，不是解析器有问题。
     */
    @Test
    public void testMySQL() {
        String sql = "insert into bigdata.user select * from users a left outer join address b on a.address_id = b.id";
        Statement statement = MySqlHelper.parseStatement(sql);

        assertTrue("insert ... select 应解析为 InsertTable，实际: " + statement.getClass().getName(),
                statement instanceof InsertTable);
        InsertTable insertTable = (InsertTable) statement;
        assertEquals(StatementType.INSERT, insertTable.getStatementType());
        assertEquals("bigdata", insertTable.getTableId().getSchemaName());
        assertEquals("user", insertTable.getTableId().getTableName());
    }

    /** 嵌套子查询：纯 select，输入表只有内层的 tdl_dt2x_table。 */
    @Test
    public void testMySQL2() {
        String sql = "select week from(select \n" +
                "   week \n" +
                "  from tdl_dt2x_table) a";
        Statement statement = MySqlHelper.parseStatement(sql);

        assertTrue("应解析为 QueryStmt", statement instanceof QueryStmt);
        QueryStmt queryStmt = (QueryStmt) statement;
        assertEquals(StatementType.SELECT, queryStmt.getStatementType());
        assertEquals(1, queryStmt.getInputTables().size());
        assertEquals("tdl_dt2x_table", queryStmt.getInputTables().get(0).getTableName());
    }


    @Test
    public void testPostgres() {
        String sql = "select a.* from datacompute1.datacompute.dc_job a left join datacompute1.datacompute.dc_job_scheduler b on a.id=b.job_id";
        Statement statement = PostgreSqlHelper.parseStatement(sql);
        if (statement instanceof QueryStmt) {
            QueryStmt queryStmt = (QueryStmt) statement;
            assertEquals(StatementType.SELECT, queryStmt.getStatementType());
            assertEquals(2, queryStmt.getInputTables().size());
        } else {
            fail();
        }
    }
}