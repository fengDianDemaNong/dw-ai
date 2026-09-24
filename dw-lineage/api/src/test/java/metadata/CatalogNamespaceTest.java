package metadata;

import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.enums.MetaSource;
import com.dwai.lineage.persistence.JdbcMetaCatalogRepository;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.persistence.MetaColumnRow;
import com.dwai.lineage.persistence.MetaTableRow;
import com.dwai.lineage.service.metadata.MetaNames;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import persistence.TestDataSources;
import persistence.TestSchema;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 数据目录（catalog）参与表的唯一标识。
 *
 * <p>此前 {@code full_name} 只拼 {@code schema.table}，而唯一键建在它上面 ——
 * {@code hive_prod.ods.orders} 与 {@code hive_test.ods.orders} 会撞同一个 key，
 * 后同步的把先同步的整张覆盖掉，页面上完全看不出来。
 */
public class CatalogNamespaceTest {

    private static final LineageContext CTX = new LineageContext(1, 1);

    private static DataSource dataSource;
    private MetaCatalogRepository repository;

    @Before
    public void setUp() {
        if (dataSource == null) {
            DataSource ds = TestDataSources.h2("catalog_namespace_h2");
            TestSchema.apply(ds, "h2");
            dataSource = ds;
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcMetaCatalogRepository(jdbc);
        jdbc.update("delete from meta_column");
        jdbc.update("delete from meta_table");
    }

    @Test
    public void fullNameIncludesCatalogWhenPresent() {
        assertEquals("hive_prod.ods.orders",
                MetaNames.tableFullName("hive_prod", "ods", "orders"));
        assertEquals("没有数据目录时应退化成两段", "ods.orders",
                MetaNames.tableFullName(null, "ods", "orders"));
        assertEquals("大小写要归一", "hive_prod.ods.orders",
                MetaNames.tableFullName("Hive_Prod", "ODS", "Orders"));
    }

    @Test
    public void catalogOfAndWithoutCatalog() {
        assertEquals("hive_prod", MetaNames.catalogOf("hive_prod.ods.orders"));
        assertNull("两段式没有数据目录", MetaNames.catalogOf("ods.orders"));
        assertEquals("ods.orders", MetaNames.withoutCatalog("hive_prod.ods.orders"));
        assertEquals("没有目录段时原样返回", "ods.orders", MetaNames.withoutCatalog("ods.orders"));
    }

    /** 核心用例：两个数据目录下的同名表必须是两行，互不覆盖。 */
    @Test
    public void sameSchemaTableInDifferentCatalogsAreDistinctRows() {
        long prod = upsert("hive_prod", "ods", "orders", "生产订单表", List.of("id", "amount"));
        long test = upsert("hive_test", "ods", "orders", "测试订单表", List.of("id"));

        assertNotEquals("两个数据目录下的同名表被存成了同一行", prod, test);
        assertEquals("生产订单表", repository.findById(CTX, prod).orElseThrow().comment());
        assertEquals("测试订单表", repository.findById(CTX, test).orElseThrow().comment());

        assertEquals("字段不该串", 2, repository.listColumns(CTX, prod).size());
        assertEquals("字段不该串", 1, repository.listColumns(CTX, test).size());
    }

    /**
     * 无数据目录的表<b>写不进去</b>。
     *
     * <p>1.0.4 之前这里是「无目录的表是第三张独立的表」。那种表是个麻烦：
     * 它的 {@code full_name} 只有两段，与元数据侧恒为三段的全名关联不上，
     * 页面上取不到中文名和表类型。现在 {@code catalog_name} 是 NOT NULL，
     * 这条用例钉住的是约束本身还在。
     */
    @Test
    public void tableWithoutCatalogIsRejected() {
        upsert("hive_prod", "ods", "orders", "生产", List.of("id"));

        assertThrows("catalog_name 为空应当被数据库拒绝",
                DataAccessException.class,
                () -> upsert(null, "ods", "orders", "无目录", List.of("id")));
    }

    /** 同一个数据目录下重复同步，仍然是 upsert 而不是新增。 */
    @Test
    public void sameCatalogUpsertsInPlace() {
        long first = upsert("hive_prod", "ods", "orders", "第一次", List.of("id"));
        long second = upsert("hive_prod", "ods", "orders", "第二次", List.of("id", "amount"));

        assertEquals("同目录同名表应当就地更新", first, second);
        assertEquals("第二次", repository.findById(CTX, first).orElseThrow().comment());
        assertEquals(2, repository.listColumns(CTX, first).size());
    }

    /**
     * 回退查找：SQL 里只写了 {@code ods.orders} 时，按 schema.table 要能把各个目录下的
     * 同名表都捞出来，交给上层去挑（多张同名时 {@code CatalogMetadataProvider} 不猜）。
     */
    @Test
    public void findBySchemaAndTableMatchesAcrossCatalogs() {
        upsert("hive_prod", "ods", "orders", "生产", List.of("id"));
        upsert("hive_test", "ods", "orders", "测试", List.of("id"));
        upsert("hive_prod", "ods", "users", "用户", List.of("id"));

        List<MetaTableRow> hits = repository.findBySchemaAndTable(CTX, List.of("ods.orders"));
        assertEquals("应当捞到 2 张 ods.orders", 2, hits.size());
        assertTrue("不该把 ods.users 也捞进来",
                hits.stream().noneMatch(r -> r.fullName().endsWith(".users")));
    }

    // ------------------------------------------------------------------

    private long upsert(String catalog, String schema, String table, String comment,
                        List<String> columns) {
        String fullName = MetaNames.tableFullName(catalog, schema, table);
        List<MetaColumnRow> cols = new java.util.ArrayList<>();
        int ordinal = 1;
        for (String c : columns) {
            cols.add(MetaColumnRow.of(c, "string", null, ordinal++, false, true, false,
                    MetaSource.DDL));
        }
        return repository.upsert(CTX,
                MetaTableRow.of(catalog, schema, table, fullName, null, comment, null,
                        "hive", MetaSource.DDL, null),
                cols, false).tableId();
    }
}
