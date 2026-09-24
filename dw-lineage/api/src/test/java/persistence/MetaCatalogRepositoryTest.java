package persistence;

import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.enums.MetaSource;
import com.dwai.lineage.persistence.JdbcMetaCatalogRepository;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.persistence.MetaColumnRow;
import com.dwai.lineage.persistence.MetaTableRow;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 元数据目录的读写，重点是<b>手工内容的保护</b>与<b>跨租户隔离</b>。
 */
public class MetaCatalogRepositoryTest {

    private static final LineageContext TENANT_A = new LineageContext(1, 10);
    private static final LineageContext TENANT_B = new LineageContext(2, 20);

    private static DataSource dataSource;

    private MetaCatalogRepository repository;
    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        if (dataSource == null) {
            DataSource ds = TestDataSources.h2("meta_catalog_h2");
            TestSchema.apply(ds, "h2");
            dataSource = ds;
        }
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcMetaCatalogRepository(jdbc);
        jdbc.update("delete from meta_column");
        jdbc.update("delete from meta_table");
    }

    // ---------------- 基本读写 ----------------

    @Test
    public void insertsTableWithColumns() {
        MetaCatalogRepository.UpsertResult result = repository.upsert(TENANT_A,
                table("ods", "user", MetaSource.DDL),
                List.of(column("id", "bigint", "主键", 1, false),
                        column("pt", "string", "分区", 2, true)),
                false);

        assertTrue(result.created());
        assertFalse(result.skipped());

        MetaTableRow saved = repository.findByFullName(TENANT_A, "cat.ods.user").orElseThrow();
        assertEquals("ods", saved.schemaName());
        assertEquals(MetaSource.DDL, saved.source());

        List<MetaColumnRow> columns = repository.listColumns(TENANT_A, result.tableId());
        assertEquals(2, columns.size());
        // 分区列排在后面
        assertEquals(List.of("id", "pt"),
                columns.stream().map(MetaColumnRow::columnName).toList());
        assertEquals("bigint", columns.get(0).dataType());
        assertEquals("主键", columns.get(0).comment());
        assertTrue("pt 应被标记为分区列", columns.get(1).partition());
    }

    /** full_name 恒为小写：ODS.User 与 ods.user 必须落到同一行，否则解析时按小写查不到。 */
    @Test
    public void fullNameIsCaseNormalized() {
        repository.upsert(TENANT_A, table("ODS", "User", MetaSource.DDL),
                List.of(column("id", "bigint", null, 1, false)), false);

        assertTrue("按小写全名应能查到", repository.findByFullName(TENANT_A, "cat.ods.user").isPresent());
        MetaTableRow saved = repository.findByFullName(TENANT_A, "cat.ods.user").orElseThrow();
        assertEquals("展示用的库名应保留原始大小写", "ODS", saved.schemaName());
        assertEquals("展示用的表名应保留原始大小写", "User", saved.tableName());
    }

    @Test
    public void upsertIsIdempotent() {
        repository.upsert(TENANT_A, table("ods", "user", MetaSource.DDL),
                List.of(column("id", "bigint", null, 1, false)), false);
        MetaCatalogRepository.UpsertResult second = repository.upsert(TENANT_A,
                table("ods", "user", MetaSource.DDL),
                List.of(column("id", "bigint", null, 1, false)), false);

        assertFalse("第二次是更新不是新增", second.created());
        assertEquals(1, count("meta_table"));
        assertEquals(1, count("meta_column"));
    }

    /** 上游删掉的字段应从目录里消失，否则会拿着不存在的列去解析血缘。 */
    @Test
    public void columnsRemovedUpstreamAreDeleted() {
        long tableId = repository.upsert(TENANT_A, table("ods", "user", MetaSource.DBX),
                List.of(column("id", "bigint", null, 1, false),
                        column("dropped", "string", null, 2, false)), false).tableId();

        repository.upsert(TENANT_A, table("ods", "user", MetaSource.DBX),
                List.of(column("id", "bigint", null, 1, false)), false);

        assertEquals(List.of("id"), repository.listColumns(TENANT_A, tableId).stream()
                .map(MetaColumnRow::columnName).toList());
    }

    // ---------------- 手工内容的保护 ----------------

    /** 人手工改过的表，同步时整张跳过 —— 刚补好的中文名不该被下一次同步冲掉。 */
    @Test
    public void manualTableIsSkippedBySync() {
        long tableId = repository.upsert(TENANT_A, table("ods", "user", MetaSource.DBX),
                List.of(column("id", "bigint", null, 1, false)), false).tableId();
        repository.updateTableInfo(TENANT_A, tableId, "FULL", "用户表", "人工整理");

        MetaCatalogRepository.UpsertResult result = repository.upsert(TENANT_A,
                table("ods", "user", MetaSource.DBX),
                List.of(column("id", "bigint", "被覆盖的注释", 1, false)), false);

        assertTrue("手工维护过的表应被跳过", result.skipped());
        MetaTableRow after = repository.findByFullName(TENANT_A, "cat.ods.user").orElseThrow();
        assertEquals("用户表", after.comment());
        assertEquals(MetaSource.MANUAL, after.source());
    }

    /** 显式声明覆盖时才动它。 */
    @Test
    public void manualTableIsOverwrittenWhenExplicitlyRequested() {
        long tableId = repository.upsert(TENANT_A, table("ods", "user", MetaSource.DBX),
                List.of(column("id", "bigint", null, 1, false)), false).tableId();
        repository.updateTableInfo(TENANT_A, tableId, "FULL", "用户表", null);

        MetaCatalogRepository.UpsertResult result = repository.upsert(TENANT_A,
                table("ods", "user", MetaSource.DBX),
                List.of(column("id", "bigint", null, 1, false)), true);

        assertFalse("显式覆盖时不应跳过", result.skipped());
        assertEquals(MetaSource.DBX,
                repository.findByFullName(TENANT_A, "cat.ods.user").orElseThrow().source());
    }

    /**
     * 字段级保护：手工写的中文名保住，但结构（类型/顺序/分区）仍以上游为准。
     *
     * <p>类型是客观事实，上游改了就该跟着改；中文名是人的产出，不该被覆盖。
     */
    @Test
    public void manualColumnKeepsCommentButFollowsUpstreamStructure() {
        long tableId = repository.upsert(TENANT_A, table("ods", "user", MetaSource.DBX),
                List.of(column("id", "int", null, 1, false)), false).tableId();
        long columnId = repository.listColumns(TENANT_A, tableId).get(0).id();
        repository.updateColumnInfo(TENANT_A, columnId, "int", "用户主键", null);

        repository.upsert(TENANT_A, table("ods", "user", MetaSource.DBX),
                List.of(column("id", "bigint", "同步来的注释", 1, false)), false);

        MetaColumnRow after = repository.listColumns(TENANT_A, tableId).get(0);
        assertEquals("人写的中文名要保住", "用户主键", after.comment());
        assertEquals("类型是客观事实，应跟随上游", "bigint", after.dataType());
    }

    /** 同步来的注释为空时，不要把已有的注释清掉。 */
    @Test
    public void syncWithEmptyCommentDoesNotWipeExistingOne() {
        long tableId = repository.upsert(TENANT_A, table("ods", "user", MetaSource.DDL),
                List.of(column("id", "bigint", "主键", 1, false)), false).tableId();

        repository.upsert(TENANT_A, table("ods", "user", MetaSource.DBX),
                List.of(column("id", "bigint", null, 1, false)), false);

        assertEquals("主键", repository.listColumns(TENANT_A, tableId).get(0).comment());
    }

    // ---------------- 手工修改 ----------------

    @Test
    public void manualEditMarksSourceAsManual() {
        long tableId = repository.upsert(TENANT_A, table("ods", "user", MetaSource.GRAVITINO),
                List.of(column("id", "bigint", null, 1, false)), false).tableId();

        assertTrue(repository.updateTableInfo(TENANT_A, tableId, "ZIPPER", "用户拉链表", "备注"));

        MetaTableRow after = repository.findById(TENANT_A, tableId).orElseThrow();
        assertEquals(MetaSource.MANUAL, after.source());
        assertEquals("ZIPPER", after.tableType());
        assertEquals("用户拉链表", after.comment());
    }

    // ---------------- 查询与分页 ----------------

    @Test
    public void listFiltersBySchemaAndKeywordCaseInsensitively() {
        repository.upsert(TENANT_A, table("ods", "user", MetaSource.DDL), List.of(), false);
        repository.upsert(TENANT_A, table("ods", "order", MetaSource.DDL), List.of(), false);
        repository.upsert(TENANT_A, table("dwd", "user", MetaSource.DDL), List.of(), false);

        assertEquals(2, repository.count(TENANT_A, null, "ods", null));
        assertEquals("关键字应大小写不敏感", 2, repository.count(TENANT_A, null, null, "USER"));
        assertEquals(1, repository.count(TENANT_A, null, "ods", "user"));
    }

    @Test
    public void listPaginates() {
        for (int i = 0; i < 5; i++) {
            repository.upsert(TENANT_A, table("ods", "t" + i, MetaSource.DDL), List.of(), false);
        }

        assertEquals(2, repository.list(TENANT_A, null, null, null, 0, 2).size());
        assertEquals(1, repository.list(TENANT_A, null, null, null, 4, 2).size());
        assertEquals(5, repository.count(TENANT_A, null, null, null));
    }

    @Test
    public void findByFullNamesFetchesInBatch() {
        repository.upsert(TENANT_A, table("ods", "a", MetaSource.DDL), List.of(), false);
        repository.upsert(TENANT_A, table("ods", "b", MetaSource.DDL), List.of(), false);

        assertEquals(2, repository.findByFullNames(TENANT_A, List.of("cat.ods.a", "cat.ods.b", "cat.ods.missing")).size());
    }

    // ---------------- 跨租户隔离 ----------------

    @Test
    public void otherTenantCannotSeeOrModify() {
        long tableId = repository.upsert(TENANT_A, table("ods", "user", MetaSource.DDL),
                List.of(column("id", "bigint", null, 1, false)), false).tableId();

        assertEquals(Optional.empty(), repository.findById(TENANT_B, tableId));
        assertEquals(Optional.empty(), repository.findByFullName(TENANT_B, "cat.ods.user"));
        assertTrue(repository.listSchemas(TENANT_B).isEmpty());
        assertTrue(repository.listColumns(TENANT_B, tableId).isEmpty());
        assertFalse(repository.updateTableInfo(TENANT_B, tableId, "FULL", "劫持", null));
        assertFalse(repository.deleteTable(TENANT_B, tableId));

        assertNull("A 的数据应完好", repository.findById(TENANT_A, tableId).orElseThrow().comment());
    }

    /** 同名表在不同租户下互不干扰。 */
    @Test
    public void sameTableNameCoexistsAcrossTenants() {
        repository.upsert(TENANT_A, table("ods", "user", MetaSource.DDL), List.of(), false);
        repository.upsert(TENANT_B, table("ods", "user", MetaSource.DDL), List.of(), false);

        assertEquals(2, count("meta_table"));
        assertEquals(1, repository.count(TENANT_A, null, null, null));
        assertEquals(1, repository.count(TENANT_B, null, null, null));
    }

    // ---------------- 删除 ----------------

    @Test
    public void deleteTableRemovesItsColumns() {
        long tableId = repository.upsert(TENANT_A, table("ods", "user", MetaSource.DDL),
                List.of(column("id", "bigint", null, 1, false)), false).tableId();

        assertTrue(repository.deleteTable(TENANT_A, tableId));
        assertEquals(0, count("meta_table"));
        assertEquals(0, count("meta_column"));
    }

    // ------------------------------------------------------------------

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    /** 表全名恒为三段，所以固定装置也得带上数据目录 —— catalog_name 是 NOT NULL。 */
    private static final String CAT = "cat";

    private static MetaTableRow table(String schema, String name, MetaSource source) {
        return MetaTableRow.of(CAT, schema, name,
                com.dwai.lineage.service.metadata.MetaNames.tableFullName(CAT, schema, name),
                null, null, null, "hive", source, null);
    }

    private static MetaColumnRow column(String name, String type, String comment,
                                        int ordinal, boolean partition) {
        return MetaColumnRow.of(name, type, comment, ordinal, partition, true, false, MetaSource.DDL);
    }
}
