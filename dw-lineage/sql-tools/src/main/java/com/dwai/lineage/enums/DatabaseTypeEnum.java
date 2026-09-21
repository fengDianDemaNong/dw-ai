package com.dwai.lineage.enums;

import com.github.melin.superior.sql.parser.mysql.MySqlHelper;
import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.parser.clickhouse.ClickHouseHelper;
import io.github.melin.superior.parser.doris.DorisHelper;
import io.github.melin.superior.parser.flink.FlinkSqlHelper;
import io.github.melin.superior.parser.oracle.OracleSqlHelper;
import io.github.melin.superior.parser.postgre.PostgreSqlHelper;
import io.github.melin.superior.parser.presto.PrestoSqlHelper;
// Redshift 的 helper 挂在 postgre 包下，不在 redshift 包下 —— 上游 4.1.0 起
// 把它并进了 PostgreSQL 那套语法（Redshift 本来就是 PG 的分支），只有 antlr4
// 生成的类还留在 io.github.melin.superior.parser.redshift.antlr4 里。
// 看着像笔误，实际是对的，别顺手「修」回去
import io.github.melin.superior.parser.redshift.RedshiftSqlHelper;
import io.github.melin.superior.parser.spark.SparkSqlHelper;
import io.github.melin.superior.parser.sqlserver.SqlServerHelper;
import io.github.melin.superior.parser.starrocks.StarRocksHelper;
import io.github.melin.superior.parser.trino.TrinoSqlHelper;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 方言注册表：每种方言对应的解析器，以及它的能力边界。
 *
 * <p>新增方言只需在此加一行，不必再改动解析主流程。
 *
 * <h3>关于 {@code ddlMetadataSupported}</h3>
 * 字段级血缘需要表结构。表结构有两个来源：SQL 里的 {@code CREATE TABLE}，或外部元数据服务。
 * 实测（见 {@code DialectCapabilityTest}）表明：
 * <ul>
 *   <li><b>列级血缘分析本身</b>：13 种方言在拿到表结构后<b>全部可用</b>
 *       —— sqlflow 的 trino 派生语法覆盖到了</li>
 *   <li><b>从 SQL 内 DDL 提取表结构</b>：trino / presto / sqlserver <b>不支持</b>，
 *       它们的 {@code CREATE TABLE} 会被解析成 {@code DefaultStatement} 而拿不到列</li>
 * </ul>
 * 因此这三种方言必须依赖外部元数据（如 Gravitino），仅靠 SQL 内 DDL 时
 * {@code select *}、聚合、列重命名等场景会缺列。前端据此提示用户。
 */
public enum DatabaseTypeEnum {

    PRESTO("presto", PrestoSqlHelper::parseMultiStatement, false,
            PrestoSqlHelper::sqlKeywords, PrestoSqlHelper::checkSqlSyntax),
    TRINO("trino", TrinoSqlHelper::parseMultiStatement, false,
            TrinoSqlHelper::sqlKeywords, TrinoSqlHelper::checkSqlSyntax),
    FLINK("flink", FlinkSqlHelper::parseMultiStatement, true,
            FlinkSqlHelper::sqlKeywords, FlinkSqlHelper::checkSqlSyntax),
    SPARK("spark", SparkSqlHelper::parseMultiStatement, true,
            SparkSqlHelper::sqlKeywords, SparkSqlHelper::checkSqlSyntax),
    HIVE("hive", SparkSqlHelper::parseMultiStatement, true,
            SparkSqlHelper::sqlKeywords, SparkSqlHelper::checkSqlSyntax),
    STARROCKS("starrocks", StarRocksHelper::parseMultiStatement, true,
            StarRocksHelper::sqlKeywords, StarRocksHelper::checkSqlSyntax),
    DORIS("doris", DorisHelper::parseMultiStatement, true,
            DorisHelper::sqlKeywords, DorisHelper::checkSqlSyntax),
    REDSHIFT("redshift", RedshiftSqlHelper::parseMultiStatement, true,
            RedshiftSqlHelper::sqlKeywords, RedshiftSqlHelper::checkSqlSyntax),
    MYSQL("mysql", MySqlHelper::parseMultiStatement, true,
            MySqlHelper::sqlKeywords, MySqlHelper::checkSqlSyntax),
    ORACLE("oracle", OracleSqlHelper::parseMultiStatement, true,
            OracleSqlHelper::sqlKeywords, OracleSqlHelper::checkSqlSyntax),
    POSTGRES("postgres", PostgreSqlHelper::parseMultiStatement, true,
            PostgreSqlHelper::sqlKeywords, PostgreSqlHelper::checkSqlSyntax),
    SQLSERVER("sqlserver", SqlServerHelper::parseMultiStatement, false,
            SqlServerHelper::sqlKeywords, SqlServerHelper::checkSqlSyntax),
    CK("ck", ClickHouseHelper::parseMultiStatement, true,
            ClickHouseHelper::sqlKeywords, ClickHouseHelper::checkSqlSyntax);

    private final String type;
    private final Function<String, List<Statement>> parser;
    private final boolean ddlMetadataSupported;
    private final Supplier<List<String>> keywords;
    private final Consumer<String> syntaxChecker;

    DatabaseTypeEnum(String type,
                     Function<String, List<Statement>> parser,
                     boolean ddlMetadataSupported,
                     Supplier<List<String>> keywords,
                     Consumer<String> syntaxChecker) {
        this.type = type;
        this.parser = parser;
        this.ddlMetadataSupported = ddlMetadataSupported;
        this.keywords = keywords;
        this.syntaxChecker = syntaxChecker;
    }

    public String getType() {
        return type;
    }

    /** 能否从该方言的 {@code CREATE TABLE} 中提取到表结构。 */
    public boolean isDdlMetadataSupported() {
        return ddlMetadataSupported;
    }

    /** 按该方言拆分并解析多条语句。 */
    public List<Statement> parse(String sql) {
        return parser.apply(sql);
    }

    /** 该方言的关键字，供编辑器做补全提示。 */
    public List<String> keywords() {
        return keywords.get();
    }

    /** 校验语法，不合法时抛异常。 */
    public void checkSyntax(String sql) {
        syntaxChecker.accept(sql);
    }

    public static DatabaseTypeEnum fromString(String type) {
        if (type == null) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        for (DatabaseTypeEnum value : values()) {
            if (value.type.equalsIgnoreCase(type.trim())) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unsupported database type: " + type);
    }

    public static List<String> getAllSupportedDatabases() {
        return Arrays.stream(values())
                .map(DatabaseTypeEnum::getType)
                .collect(Collectors.toList());
    }
}
