package lineage;

import org.junit.Test;
import com.dwai.lineage.dto.SqlValidateResponse;
import com.dwai.lineage.dto.TableLineageResponse;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.service.SqlSyntaxService;
import com.dwai.lineage.service.TableLineageService;
import com.dwai.lineage.service.SqlParseExecutor;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 表级血缘（T8）与 SQL 语法校验 / 关键字（T9）。
 */
public class TableLineageAndSyntaxTest {

    private final TableLineageService tableLineage = new TableLineageService(new MetadataServiceFactory(), new SqlParseExecutor());
    private final SqlSyntaxService syntax = new SqlSyntaxService();

    // ---------------- 表级血缘 ----------------

    /** 表级血缘不依赖任何表结构元数据。 */
    @Test
    public void tableLineageWorksWithoutAnyMetadata() {
        TableLineageResponse r = tableLineage.analyze("hive",
                "insert into dws.t select a from ods.s");

        assertEquals(Set.of("dws.t", "ods.s"), Set.copyOf(r.nodes()));
        assertEquals(List.of(new TableLineageResponse.Edge("ods.s", "dws.t")), r.edges());
    }

    /** join 的多个来源表都应成为上游。 */
    @Test
    public void joinProducesEdgeFromEverySourceTable() {
        TableLineageResponse r = tableLineage.analyze("hive",
                "insert into dws.j select u.id, o.amt from ods.u u join ods.o o on u.id=o.uid");

        Set<String> sources = r.edges().stream()
                .map(TableLineageResponse.Edge::source).collect(Collectors.toSet());
        assertEquals(Set.of("ods.u", "ods.o"), sources);
        assertTrue(r.edges().stream()
                .allMatch(e -> e.target().equals("dws.j")));
    }

    /** Doris {@code USE @cg1} 不能拖垮后面的 INSERT。 */
    @Test
    public void dorisUseComputeGroupIsIgnoredForTableLineage() {
        TableLineageResponse r = tableLineage.analyze("doris",
                "USE @cg1;\ninsert into dws.t select a from ods.s");

        assertEquals(Set.of("dws.t", "ods.s"), Set.copyOf(r.nodes()));
        assertEquals(List.of(new TableLineageResponse.Edge("ods.s", "dws.t")), r.edges());
    }

    /** 多条语句串成链路。 */
    @Test
    public void multiStatementFormsChain() {
        TableLineageResponse r = tableLineage.analyze("hive",
                "insert into dwd.d select c from ods.o;\n"
                        + "insert into dws.s select c from dwd.d;");

        assertEquals(2, r.statements().size());
        assertTrue(r.edges().contains(new TableLineageResponse.Edge("ods.o", "dwd.d")));
        assertTrue(r.edges().contains(new TableLineageResponse.Edge("dwd.d", "dws.s")));
    }

    /** CTAS 的目标表也应被识别为输出。 */
    @Test
    public void ctasTargetIsRecognisedAsOutput() {
        TableLineageResponse r = tableLineage.analyze("hive",
                "create table dws.t as select a from ods.s");

        assertTrue(r.nodes().contains("dws.t"));
        assertTrue(r.edges().contains(new TableLineageResponse.Edge("ods.s", "dws.t")));
    }

    /**
     * 关键价值：trino 的 CREATE TABLE 提取不了表结构（列级血缘会缺列），
     * 但表级血缘不受影响，可作为降级方案。
     */
    @Test
    public void tableLineageWorksForDialectsLackingDdlMetadata() {
        TableLineageResponse r = tableLineage.analyze("trino",
                "insert into dws.t select * from ods.s");

        assertTrue(r.edges().contains(new TableLineageResponse.Edge("ods.s", "dws.t")));
    }

    /** set / drop 等非血缘语句应被忽略，不产生噪声节点。 */
    @Test
    public void nonLineageStatementsAreIgnored() {
        TableLineageResponse r = tableLineage.analyze("hive",
                "set hive.exec.dynamic.partition=true;\n"
                        + "drop table if exists dws.t;\n"
                        + "insert into dws.t select a from ods.s;");

        assertEquals(Set.of("dws.t", "ods.s"), Set.copyOf(r.nodes()));
    }

    @Test
    public void unsupportedDialectRaisesSqlParseException() {
        try {
            tableLineage.analyze("not_a_db", "select 1");
            fail("应抛出 SqlParseException");
        } catch (SqlParseException expected) {
            assertTrue(expected.getMessage().contains("不支持")
                    || expected.getMessage().contains("Unsupported"));
        }
    }

    // ---------------- 语法校验 ----------------

    @Test
    public void validSqlPassesValidation() {
        SqlValidateResponse r = syntax.validate("hive", "select a from ods.t");

        assertTrue("合法 SQL 应通过校验，实际错误: " + r.errors(), r.valid());
        assertTrue(r.errors().isEmpty());
    }

    @Test
    public void invalidSqlReportsErrorWithPosition() {
        SqlValidateResponse r = syntax.validate("hive", "selct a form ods.t");

        assertFalse("非法 SQL 应校验失败", r.valid());
        assertFalse(r.errors().isEmpty());

        SqlValidateResponse.SyntaxError first = r.errors().get(0);
        assertTrue("行号应从 1 开始", first.line() >= 1);
        assertTrue("列号应从 1 开始", first.column() >= 1);
        assertFalse("应带错误描述", first.message().isBlank());
    }

    /**
     * 多行 SQL 的错误行号要指向出错那一行。
     *
     * <p>superior-sql-parser 的报错格式是 {@code (line 3, pos 0)} 而非 ANTLR 原生的
     * {@code line 3:0}，位置解析必须同时兼容两者，否则行号会全部退化成 1。
     */
    @Test
    public void errorPositionPointsToOffendingLine() {
        SqlValidateResponse r = syntax.validate("hive",
                "select a from t1\nunion all\nselct b from t2");

        assertFalse(r.valid());
        assertEquals("错误在第 3 行，行号不能退化成 1", 3, r.errors().get(0).line());
    }

    // ---------------- 关键字 ----------------

    @Test
    public void keywordsAreAvailableForEveryDialect() {
        // ck 除外：ClickHouseHelper.sqlKeywords() 未实现，只返回一个占位符
        for (String dialect : List.of("hive", "spark", "trino", "presto", "mysql", "doris",
                "starrocks", "postgres", "redshift", "sqlserver", "oracle", "flink")) {
            List<String> keywords = syntax.keywords(dialect);
            assertFalse(dialect + " 应返回关键字列表", keywords.isEmpty());
            assertTrue(dialect + " 关键字应包含 SELECT",
                    keywords.stream().anyMatch(k -> k.equalsIgnoreCase("SELECT")));
        }
    }

    /**
     * 已知限制：ClickHouse 的关键字列表在 superior-sql-parser 中未实现。
     * 此用例锁定现状；一旦上游补齐，它会失败并提醒把 ck 并入上面的通用用例。
     */
    @Test
    public void clickhouseKeywordsAreKnownToBeUnimplemented() {
        List<String> keywords = syntax.keywords("ck");
        assertTrue("ck 关键字若已被上游实现，请把它并入 keywordsAreAvailableForEveryDialect",
                keywords.size() <= 1);
    }
}
