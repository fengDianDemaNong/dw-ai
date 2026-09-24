package metadata;

import io.github.melin.superior.common.relational.Statement;
import org.junit.Test;
import com.dwai.lineage.enums.MetaSource;
import com.dwai.lineage.persistence.MetaColumnRow;
import com.dwai.lineage.service.metadata.DdlCatalogExtractor;
import com.dwai.lineage.service.metadata.MetadataServiceFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 从建表语句抽取元数据目录条目。
 *
 * <p>核心是验证它<b>确实拿到了类型与中文名</b> —— 这正是血缘用的
 * {@code DdlMetadataProvider} 丢掉的信息（它只取列名），也是当初决定
 * 另写一个抽取器而不是复用它的原因。
 */
public class DdlCatalogExtractorTest {

    private final MetadataServiceFactory factory = new MetadataServiceFactory();

    @Test
    public void extractsColumnTypesAndComments() {
        String ddl = """
                CREATE TABLE ods.user (
                    id     BIGINT  COMMENT '用户主键',
                    name   STRING  COMMENT '姓名',
                    age    INT
                ) COMMENT '用户表';
                """;

        DdlCatalogExtractor.ExtractedTable entry = extractOne(ddl);

        assertEquals("ods", entry.table().schemaName());
        assertEquals("user", entry.table().tableName());
        assertEquals("ods.user", entry.table().fullName());
        assertEquals("表注释应作为中文名", "用户表", entry.table().comment());
        assertEquals(MetaSource.DDL, entry.table().source());

        Map<String, MetaColumnRow> columns = byName(entry.columns());
        assertEquals(3, columns.size());
        assertEquals("BIGINT", columns.get("id").dataType().toUpperCase());
        assertEquals("用户主键", columns.get("id").comment());
        assertEquals("姓名", columns.get("name").comment());
        assertNull("没写注释的字段应为 null 而不是字面量 null", columns.get("age").comment());
    }

    /** 顺序要保留，页面上按 ordinal 展示。 */
    @Test
    public void keepsColumnOrder() {
        DdlCatalogExtractor.ExtractedTable entry = extractOne(
                "CREATE TABLE ods.t (a INT, b INT, c INT);");

        assertEquals(List.of("a", "b", "c"),
                entry.columns().stream().map(MetaColumnRow::columnName).toList());
        assertEquals(List.of(1, 2, 3),
                entry.columns().stream().map(MetaColumnRow::ordinal).toList());
    }

    /**
     * 分区列要标出来。
     *
     * <p>Hive 的 {@code PARTITIONED BY} 独立于列定义，分区列不在 columnRels 里，
     * 漏掉的话解析分区表会报 {@code Column 'pt' not found}（一期踩过）。
     */
    @Test
    public void marksPartitionColumns() {
        String ddl = """
                CREATE TABLE ods.log (
                    id   BIGINT COMMENT '主键',
                    msg  STRING
                )
                PARTITIONED BY (pt STRING COMMENT '日期分区');
                """;

        DdlCatalogExtractor.ExtractedTable entry = extractOne(ddl);
        Map<String, MetaColumnRow> columns = byName(entry.columns());

        assertTrue("pt 应标记为分区列", columns.get("pt").partition());
        assertFalse(columns.get("id").partition());
        assertEquals("分区列排在普通列之后", "pt",
                entry.columns().get(entry.columns().size() - 1).columnName());
    }

    /** Doris 的 AUTO PARTITION 列已在列清单里，仍应标成分区。 */
    @Test
    public void marksDorisAutoPartitionColumnAlreadyInColumnList() {
        String ddl = """
                CREATE TABLE dw.dws_adx_dsp_device_tag_1d (
                    pt DATE NOT NULL COMMENT '日期',
                    pid VARCHAR(255) NOT NULL,
                    bid_count BIGINT NOT NULL
                )
                UNIQUE KEY (pt, pid)
                AUTO PARTITION BY RANGE (DATE_TRUNC(pt, 'DAY')) ()
                DISTRIBUTED BY HASH(pid) BUCKETS 16
                """;

        List<Statement> statements = factory.statements("doris", ddl);
        DdlCatalogExtractor.ExtractedTable entry =
                DdlCatalogExtractor.extract(statements, "doris").get(0);
        Map<String, MetaColumnRow> columns = byName(entry.columns());

        assertTrue("pt 应标记为分区列", columns.get("pt").partition());
        assertFalse(columns.get("pid").partition());
        assertEquals("日期", columns.get("pt").comment());
    }

    /** 没写库名时落到 default，与 sqlflow 的行为一致。 */
    @Test
    public void defaultsSchemaWhenAbsent() {
        assertEquals("default.t", extractOne("CREATE TABLE t (a INT);").table().fullName());
    }

    /** full_name 恒为小写，展示名保留原始大小写。 */
    @Test
    public void normalizesFullNameCase() {
        DdlCatalogExtractor.ExtractedTable entry = extractOne("CREATE TABLE ODS.User (a INT);");

        assertEquals("ods.user", entry.table().fullName());
        assertEquals("ODS", entry.table().schemaName());
        assertEquals("User", entry.table().tableName());
    }

    /**
     * 表类型刻意不推断。
     *
     * <p>{@code CreateTable.tableType} 是解析器的概念（HIVE / ICEBERG…），
     * 与我们的数仓表类型（全量表 / 拉链表…）不是一回事，硬映射会得到错的值。
     */
    @Test
    public void doesNotGuessWarehouseTableType() {
        assertNull(extractOne("CREATE TABLE ods.t (a INT);").table().tableType());
    }

    /** 多条建表语句一次全抽出来。 */
    @Test
    public void extractsMultipleTables() {
        List<DdlCatalogExtractor.ExtractedTable> tables = extract(
                "CREATE TABLE ods.a (x INT); CREATE TABLE ods.b (y INT);");

        assertEquals(List.of("ods.a", "ods.b"),
                tables.stream().map(t -> t.table().fullName()).toList());
    }

    /** 非建表语句忽略掉，方便用户直接把整个脚本粘进来。 */
    @Test
    public void ignoresNonDdlStatements() {
        List<DdlCatalogExtractor.ExtractedTable> tables = extract(
                "CREATE TABLE ods.a (x INT); INSERT INTO dwd.b SELECT x FROM ods.a;");

        assertEquals(1, tables.size());
        assertEquals("ods.a", tables.get(0).table().fullName());
    }

    // ------------------------------------------------------------------

    private DdlCatalogExtractor.ExtractedTable extractOne(String ddl) {
        List<DdlCatalogExtractor.ExtractedTable> tables = extract(ddl);
        assertEquals("这条用例期望只抽出一张表", 1, tables.size());
        return tables.get(0);
    }

    private List<DdlCatalogExtractor.ExtractedTable> extract(String ddl) {
        List<Statement> statements = factory.statements("hive", ddl);
        return DdlCatalogExtractor.extract(statements, "hive");
    }

    private static Map<String, MetaColumnRow> byName(List<MetaColumnRow> columns) {
        return columns.stream().collect(Collectors.toMap(
                c -> c.columnName().toLowerCase(), Function.identity()));
    }
}
