package dialect;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import io.github.melin.superior.common.relational.Statement;
import io.github.melin.superior.common.relational.create.CreateTable;
import org.junit.Test;
import com.dwai.lineage.dto.DialectInfo;
import com.dwai.lineage.enums.DatabaseTypeEnum;
import com.dwai.lineage.service.metadata.LineageAnalysisPipeline;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataResolution;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 方言能力矩阵的回归测试。
 *
 * <p>{@link DatabaseTypeEnum} 上标注的能力位不是拍脑袋写的，而是由这里的实测锁定：
 * <ul>
 *   <li><b>列级血缘</b>：13 种方言在拿到表结构后全部可用</li>
 *   <li><b>DDL 元数据提取</b>：trino / presto / sqlserver 的 CREATE TABLE
 *       会被解析成 DefaultStatement，拿不到列，必须依赖外部元数据服务</li>
 * </ul>
 * 若某个方言的解析器日后增强或退化，这里会第一时间失败，提醒同步更新能力位。
 */
public class DialectCapabilityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MetadataServiceFactory factory = new MetadataServiceFactory();
    private final LineageAnalysisPipeline pipeline = new LineageAnalysisPipeline();

    /** 各方言的建表语句，类型写法按方言调整。 */
    private static final Map<String, String> CREATE_TABLE_SQL = Map.ofEntries(
            Map.entry("hive", "create table ods.s(a int, b string)"),
            Map.entry("spark", "create table ods.s(a int, b string)"),
            Map.entry("flink", "create table ods_s(a int, b string) with ('connector'='datagen')"),
            Map.entry("trino", "create table ods.s(a int, b varchar)"),
            Map.entry("presto", "create table ods.s(a int, b varchar)"),
            Map.entry("mysql", "create table ods.s(a int, b varchar(50))"),
            Map.entry("doris", "create table ods.s(a int, b varchar(50)) distributed by hash(a) buckets 1"),
            Map.entry("starrocks", "create table ods.s(a int, b varchar(50)) distributed by hash(a) buckets 1"),
            Map.entry("postgres", "create table ods.s(a integer, b varchar(50))"),
            Map.entry("redshift", "create table ods.s(a integer, b varchar(50))"),
            Map.entry("sqlserver", "create table ods.s(a int, b nvarchar(50))"),
            Map.entry("oracle", "create table ods_s(a number, b varchar2(50))"),
            Map.entry("ck", "create table ods.s(a Int32, b String) engine=Memory"));

    // ------------------------------------------------------------------
    // 1. DDL 元数据提取能力，必须与枚举上的能力位一致
    // ------------------------------------------------------------------

    @Test
    public void ddlCapabilityFlagMatchesActualBehaviour() {
        for (DatabaseTypeEnum dialect : DatabaseTypeEnum.values()) {
            String ddl = CREATE_TABLE_SQL.get(dialect.getType());
            assertTrue("缺少 " + dialect.getType() + " 的建表用例", ddl != null);

            boolean actuallyExtracts = extractsColumns(dialect.getType(), ddl);

            assertEquals(dialect.getType() + " 的 ddlMetadataSupported 与实测不符"
                            + "（实测能否从 CREATE TABLE 拿到列: " + actuallyExtracts + "）",
                    dialect.isDdlMetadataSupported(), actuallyExtracts);
        }
    }

    /** 已知不支持 DDL 提取的方言，锁定为这三种；若日后被修复，此用例会提醒更新。 */
    @Test
    public void dialectsRequiringExternalMetadataAreExactlyKnownOnes() {
        Set<String> requiring = new LinkedHashSet<>();
        for (DatabaseTypeEnum d : DatabaseTypeEnum.values()) {
            if (!d.isDdlMetadataSupported()) {
                requiring.add(d.getType());
            }
        }
        assertEquals("需要外部元数据的方言集合发生变化，请同步更新文档与前端提示",
                Set.of("trino", "presto", "sqlserver"), requiring);
    }

    // ------------------------------------------------------------------
    // 2. 列级血缘能力：拿到表结构后，全部方言都应可用
    // ------------------------------------------------------------------

    @Test
    public void allDialectsSupportColumnLineageGivenExternalMetadata() {
        for (DatabaseTypeEnum dialect : DatabaseTypeEnum.values()) {
            String type = dialect.getType();
            // flink / oracle 的表名不带 schema 前缀写法差异较大，统一用不带点的表名；
            // 此时 sqlflow 会补上默认 schema，断言需带上 default. 前缀
            boolean flat = "flink".equals(type) || "oracle".equals(type);
            String src = flat ? "ods_s" : "ods.s";
            String dst = flat ? "dws_t" : "dws.t";
            String srcField = (flat ? "default." + src : src) + ".id";
            String dstField = (flat ? "default." + dst : dst) + ".user_id";

            List<Statement> statements =
                    factory.statements(type, "insert into " + dst + " select id from " + src);
            LineageAnalysisPipeline.LineageResult r =
                    pipeline.analyze(statements, (a, b) -> staticMetadata(flat));

            assertFalse(type + " 在有外部元数据时应能产出血缘", r.lineages().isEmpty());
            assertTrue(type + " 应把 user_id 的上游解析为 id，实际: " + r.lineages(),
                    hasEdge(r.lineages(), dstField, srcField));
        }
    }

    // ------------------------------------------------------------------
    // 3. 接口暴露的能力位
    // ------------------------------------------------------------------

    @Test
    public void dialectInfoCarriesNoteForLimitedDialects() {
        DialectInfo trino = DialectInfo.of(DatabaseTypeEnum.TRINO);
        assertFalse(trino.ddlMetadataSupported());
        assertFalse("受限方言应带说明文案", trino.note().isBlank());

        DialectInfo hive = DialectInfo.of(DatabaseTypeEnum.HIVE);
        assertTrue(hive.ddlMetadataSupported());
        assertTrue("无限制方言不应有说明文案", hive.note().isBlank());

        assertTrue("当前所有方言在有表结构时都支持列级血缘", trino.columnLevelLineage());
    }

    /** doris 应使用独立的 Doris 解析器，而不是借用 StarRocks。 */
    @Test
    public void dorisUsesItsOwnParser() {
        List<Statement> statements = factory.statements("doris",
                "create table ods.s(a int) distributed by hash(a) buckets 1;\n"
                        + "insert into dws.t select a from ods.s;");
        assertFalse("doris 建表语句应能被解析", statements.isEmpty());
        assertTrue("doris 应能识别出 CreateTable",
                statements.stream().anyMatch(s -> s instanceof CreateTable));
    }

    // ------------------------------------------------------------------

    private boolean extractsColumns(String dialect, String ddl) {
        try {
            for (Statement s : factory.statements(dialect, ddl)) {
                if (s instanceof CreateTable ct
                        && ct.getColumnRels() != null && !ct.getColumnRels().isEmpty()) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    /** 预置表结构，模拟外部元数据服务。 */
    private static MetadataProvider staticMetadata(boolean flat) {
        Map<QualifiedObjectName, SchemaTable> tables = new LinkedHashMap<>();
        if (flat) {
            tables.put(new QualifiedObjectName("", "default", "ods_s"),
                    new SchemaTable("default", "ods_s", List.of("id")));
            tables.put(new QualifiedObjectName("", "default", "dws_t"),
                    new SchemaTable("default", "dws_t", List.of("user_id")));
        } else {
            tables.put(new QualifiedObjectName("", "ods", "s"),
                    new SchemaTable("ods", "s", List.of("id")));
            tables.put(new QualifiedObjectName("", "dws", "t"),
                    new SchemaTable("dws", "t", List.of("user_id")));
        }

        return new MetadataProvider() {
            @Override
            public String name() {
                return "static";
            }

            @Override
            public MetadataResolution resolve(Set<QualifiedObjectName> wanted) {
                Map<QualifiedObjectName, SchemaTable> resolved = new LinkedHashMap<>();
                Set<QualifiedObjectName> unresolved = new LinkedHashSet<>();
                for (QualifiedObjectName q : wanted) {
                    SchemaTable hit = tables.entrySet().stream()
                            .filter(e -> e.getKey().getObjectName().equalsIgnoreCase(q.getObjectName()))
                            .map(Map.Entry::getValue)
                            .findFirst().orElse(null);
                    if (hit != null) {
                        resolved.put(q, hit);
                    } else {
                        unresolved.add(q);
                    }
                }
                return MetadataResolution.of(resolved, unresolved);
            }
        };
    }

    private static boolean hasEdge(List<String> lineages, String target, String source) {
        for (String lineage : lineages) {
            try {
                JsonNode root = MAPPER.readTree(lineage);
                String prefix = root.path("schema").asText() + "." + root.path("table").asText() + ".";
                for (JsonNode col : root.path("columns")) {
                    if (!(prefix + col.path("column").asText()).equalsIgnoreCase(target)) {
                        continue;
                    }
                    for (JsonNode sc : col.path("sourceColumns")) {
                        if ((sc.path("tableName").asText() + "." + sc.path("columnName").asText())
                                .equalsIgnoreCase(source)) {
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {
                // 忽略无法解析的结果
            }
        }
        return false;
    }
}
