package parser;

//import io.github.melin.sqlflow.tree.statement.Statement;

import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.common.relational.common.AddResourceStatement;
import io.github.melin.superior.common.relational.create.CreateCatalog;
import io.github.melin.superior.common.relational.dml.InsertMultiTable;
import io.github.melin.superior.common.relational.dml.InsertTable;
import io.github.melin.superior.common.relational.dml.QueryStmt;
import io.github.melin.superior.parser.flink.FlinkSqlHelper;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class FlinkSqlParserDmlTest {

    @Test
    public void selectSqlTest() {
        String sql = "WITH orders_with_total AS (\n" +
                "                    SELECT order_id, price + tax AS total\n" +
                "                    FROM Orders\n" +
                "                )\n" +
                "                SELECT order_id, SUM(total)\n" +
                "                FROM orders_with_total\n" +
                "                GROUP BY order_id\n" +
                "                limit 10;\n" +
                "                \n" +
                "                SELECT order_id, price FROM (VALUES (1, 2.0), (2, 3.1))  AS t (order_id, price);\n" +
                "                \n" +
                "                SELECT * FROM TABLE(TUMBLE(TABLE Bid, DESCRIPTOR(bidtime), INTERVAL '10' MINUTES));\n" +
                "                \n" +
                "                SELECT * FROM TABLE(\n" +
                "                   TUMBLE(\n" +
                "                     DATA => TABLE Bid,\n" +
                "                     TIMECOL => DESCRIPTOR(bidtime),\n" +
                "                     SIZE => INTERVAL '10' MINUTES));\n" +
                "                     \n" +
                "                SELECT * FROM TABLE(\n" +
                "                    HOP(\n" +
                "                      DATA => TABLE Bid,\n" +
                "                      TIMECOL => DESCRIPTOR(bidtime),\n" +
                "                      SLIDE => INTERVAL '5' MINUTES,\n" +
                "                      SIZE => INTERVAL '10' MINUTES));\n" +
                "                      \n" +
                "                SELECT * FROM TABLE(\n" +
                "                CUMULATE(\n" +
                "                  DATA => TABLE Bid,\n" +
                "                  TIMECOL => DESCRIPTOR(bidtime),\n" +
                "                  STEP => INTERVAL '2' MINUTES,\n" +
                "                  SIZE => INTERVAL '10' MINUTES));\n" +
                "                  \n" +
                "                SELECT window_start, window_end, SUM(price)\n" +
                "                  FROM TABLE(\n" +
                "                    TUMBLE(TABLE Bid, DESCRIPTOR(bidtime), INTERVAL '10' MINUTES, INTERVAL '1' MINUTES))\n" +
                "                  GROUP BY window_start, window_end;  \n" +
                "                  \n" +
                "                SELECT *\n" +
                "                FROM (\n" +
                "                  SELECT *,\n" +
                "                    ROW_NUMBER() OVER (PARTITION BY category ORDER BY sales DESC) AS row_num\n" +
                "                  FROM ShopSales)\n" +
                "                WHERE row_num <= 5;\n" +
                "                \n" +
                "                SELECT *\n" +
                "                FROM Ticker\n" +
                "                    MATCH_RECOGNIZE (\n" +
                "                        PARTITION BY symbol\n" +
                "                        ORDER BY rowtime\n" +
                "                        MEASURES\n" +
                "                            START_ROW.rowtime AS start_tstamp,\n" +
                "                            LAST(PRICE_DOWN.rowtime) AS bottom_tstamp,\n" +
                "                            LAST(PRICE_UP.rowtime) AS end_tstamp\n" +
                "                        ONE ROW PER MATCH\n" +
                "                        AFTER MATCH SKIP TO LAST PRICE_UP\n" +
                "                        PATTERN (START_ROW PRICE_DOWN+ PRICE_UP)\n" +
                "                        DEFINE\n" +
                "                            PRICE_DOWN AS\n" +
                "                                (LAST(PRICE_DOWN.price, 1) IS NULL AND PRICE_DOWN.price < START_ROW.price) OR\n" +
                "                                    PRICE_DOWN.price < LAST(PRICE_DOWN.price, 1),\n" +
                "                            PRICE_UP AS\n" +
                "                                PRICE_UP.price > LAST(PRICE_DOWN.price, 1)\n" +
                "                    ) MR;\n" +
                "                    \n" +
                "                    set sfdf_1 = 'adf';\n" +
                "                    set sfdf_2 = true;".trim();

        List<Statement> statements = FlinkSqlHelper.parseMultiStatement(sql);
        Statement queryStmt = statements.get(0);
        if (queryStmt instanceof QueryStmt) {
            Assert.assertEquals("Orders", ((QueryStmt) queryStmt).getInputTables().get(0).getTableName());
            Assert.assertEquals(Integer.valueOf(10), ((QueryStmt) queryStmt).getLimit());
        } else {
            Assert.fail();
        }

    }

    @Test
    public void selectSqlTest1() {
        String sql = "add jar \"flink-connector-jdbc-3.1.1-1.17.jar\";\n" +
                "    \n" +
                "                CREATE TABLE flink_meta_role (\n" +
                "                  id INT,\n" +
                "                  name STRING,\n" +
                "                  code STRING,\n" +
                "                  PRIMARY KEY (id) NOT ENFORCED\n" +
                "                ) WITH (\n" +
                "                    'connector' = 'jdbc',\n" +
                "                    'url' = 'jdbc:mysql://172.18.5.44:3306/superior',\n" +
                "                    'table-name' = 'meta_role',\n" +
                "                    'username' = 'root',\n" +
                "                    'password' = 'root2023'\n" +
                "                );\n" +
                "    \n" +
                "                set 'execution.checkpointing.checkpoints-after-tasks-finish.enabled' = false;\n" +
                "                SELECT * FROM flink_meta_role;"
                        .trim();

        List<Statement> statements = FlinkSqlHelper.parseMultiStatement(sql);
        Assert.assertEquals(4, statements.size());
        Statement addStmt = statements.get(0);

        if (addStmt instanceof AddResourceStatement) {
            Assert.assertEquals("flink-connector-jdbc-3.1.1-1.17.jar", ((AddResourceStatement) addStmt).first());
        } else {
            Assert.fail();
        }

    }

    @Test
    public void selectSqlTest2() {
        String sql = "CREATE CATALOG my_catalog WITH (\n" +
                "                    'type' = 'jdbc',\n" +
                "                    'default-database' = 'demos',\n" +
                "                    'username' = 'root',\n" +
                "                    'password' = 'root2023',\n" +
                "                    'base-url' = 'jdbc:mysql://172.18.5.44:3306'\n" +
                "                );\n" +
                "                \n" +
                "                USE CATALOG my_catalog;\n" +
                "                \n" +
                "                DROP CATALOG IF EXISTS my_catalog\n" +
                "                \n" +
                "                EXPLAIN PLAN FOR select * from my_catalog.demos.orders;\n" +
                "                EXPLAIN ESTIMATED_COST, CHANGELOG_MODE, PLAN_ADVICE, JSON_EXECUTION_PLAN\n" +
                "                 select * from my_catalog.demos.orders;"
                        .trim();

        List<Statement> statements = FlinkSqlHelper.parseMultiStatement(sql);
        Assert.assertEquals(5, statements.size());

        Statement createCatalog = statements.get(0);
        if (createCatalog instanceof CreateCatalog) {
            Assert.assertEquals("my_catalog", ((CreateCatalog) createCatalog).getCatalogName());
        } else {
            Assert.fail();
        }
    }

    @Test
    public void splitSqlTest() {
        String sql = " CREATE TABLE pageviews (\n" +
                "                  user_id BIGINT,\n" +
                "                  page_id BIGINT,\n" +
                "                  viewtime TIMESTAMP,\n" +
                "                  proctime AS PROCTIME()\n" +
                "                ) WITH (\n" +
                "                  'connector' = 'kafka',\n" +
                "                  'topic' = 'pageviews',\n" +
                "                  'properties.bootstrap.servers' = '...',\n" +
                "                  'format' = 'avro'\n" +
                "                );\n" +
                "                \n" +
                "                CREATE TABLE pageview (\n" +
                "                  page_id BIGINT,\n" +
                "                  cnt BIGINT\n" +
                "                ) WITH (\n" +
                "                  'connector' = 'jdbc',\n" +
                "                  'url' = 'jdbc:mysql://localhost:3306/mydatabase',\n" +
                "                  'table-name' = 'pageview'\n" +
                "                );\n" +
                "                \n" +
                "                CREATE TABLE uniqueview (\n" +
                "                  page_id BIGINT,\n" +
                "                  cnt BIGINT\n" +
                "                ) WITH (\n" +
                "                  'connector' = 'jdbc',\n" +
                "                  'url' = 'jdbc:mysql://localhost:3306/mydatabase',\n" +
                "                  'table-name' = 'uniqueview'\n" +
                "                );\n" +
                "                \n" +
                "                EXECUTE STATEMENT SET\n" +
                "                BEGIN\n" +
                "                \n" +
                "                INSERT INTO pageview\n" +
                "                SELECT page_id, count(1)\n" +
                "                FROM pageviews\n" +
                "                GROUP BY page_id;\n" +
                "                \n" +
                "                INSERT INTO uniqueview\n" +
                "                SELECT page_id, count(distinct user_id)\n" +
                "                FROM pageviews\n" +
                "                GROUP BY page_id;\n" +
                "                \n" +
                "               END;"
                        .trim();

        List<String> strings = FlinkSqlHelper.splitSql(sql);
        strings.forEach(s -> {
            System.out.println("==========================");
            System.out.println(s);
        });
    }

    @Test
    public void multiInsertTest() {
        String sql = " CREATE TABLE pageviews (\n" +
                "                  user_id BIGINT,\n" +
                "                  page_id BIGINT,\n" +
                "                  viewtime TIMESTAMP,\n" +
                "                  proctime AS PROCTIME()\n" +
                "                ) WITH (\n" +
                "                  'connector' = 'kafka',\n" +
                "                  'topic' = 'pageviews',\n" +
                "                  'properties.bootstrap.servers' = '...',\n" +
                "                  'format' = 'avro'\n" +
                "                );\n" +
                "                \n" +
                "                CREATE TABLE pageview (\n" +
                "                  page_id BIGINT,\n" +
                "                  cnt BIGINT\n" +
                "                ) WITH (\n" +
                "                  'connector' = 'jdbc',\n" +
                "                  'url' = 'jdbc:mysql://localhost:3306/mydatabase',\n" +
                "                  'table-name' = 'pageview'\n" +
                "                );\n" +
                "                \n" +
                "                CREATE TABLE uniqueview (\n" +
                "                  page_id BIGINT,\n" +
                "                  cnt BIGINT\n" +
                "                ) WITH (\n" +
                "                  'connector' = 'jdbc',\n" +
                "                  'url' = 'jdbc:mysql://localhost:3306/mydatabase',\n" +
                "                  'table-name' = 'uniqueview'\n" +
                "                );\n" +
                "                \n" +
                "                EXECUTE STATEMENT SET\n" +
                "                BEGIN\n" +
                "                \n" +
                "                INSERT INTO pageview\n" +
                "                SELECT page_id, count(1)\n" +
                "                FROM pageviews\n" +
                "                GROUP BY page_id;\n" +
                "                \n" +
                "                INSERT INTO uniqueview\n" +
                "                SELECT page_id, count(distinct user_id)\n" +
                "                FROM pageviews\n" +
                "                GROUP BY page_id;\n" +
                "                \n" +
                "               END;"
                        .trim();

        List<Statement> statements = FlinkSqlHelper.parseMultiStatement(sql);
        Assert.assertEquals(4, statements.size());

        Statement statement = statements.get(3);
        if (statement instanceof InsertMultiTable) {
            Assert.assertEquals(2, ((InsertMultiTable) statement).getInsertTables().size());
            InsertTable insertTable = ((InsertMultiTable) statement).getInsertTables().get(0);
            String insertSql = "INSERT INTO pageview\n" +
                    "                SELECT page_id, count(1)\n" +
                    "                FROM pageviews\n" +
                    "                GROUP BY page_id";
            Assert.assertEquals(
                    insertSql,
                    insertTable.getSql()
            );
        } else {
            Assert.fail();
        }
    }
}
