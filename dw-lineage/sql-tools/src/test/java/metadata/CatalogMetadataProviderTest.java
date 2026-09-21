package metadata;

import persistence.TestSchema;
import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import io.github.melin.sqlflow.metadata.SchemaTable;
import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.enums.MetaSource;
import com.dwai.lineage.persistence.JdbcLineageCatalogRepository;
import com.dwai.lineage.persistence.JdbcLineageRepository;
import com.dwai.lineage.persistence.JdbcMetaCatalogRepository;
import com.dwai.lineage.persistence.LineageRepository;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.persistence.MetaColumnRow;
import com.dwai.lineage.persistence.MetaTableRow;
import com.dwai.lineage.service.metadata.MetaNames;
import com.dwai.lineage.service.metadata.provider.CatalogMetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataResolution;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;
import persistence.TestDataSources;

import javax.sql.DataSource;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 本地元数据目录作为解析来源，以及<b>元数据与血缘的隔离</b>。
 *
 * <p>隔离那两条是本次模型改造的核心约束，单独验证：血缘里推断出来的表绝不能进元数据目录，
 * 反过来删掉一张元数据表也不该影响已存的血缘。
 */
public class CatalogMetadataProviderTest {

    private static final LineageContext TENANT_A = new LineageContext(1, 10);
    private static final LineageContext TENANT_B = new LineageContext(2, 20);

    private static DataSource dataSource;

    private MetaCatalogRepository meta;
    private LineageRepository lineage;
    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        if (dataSource == null) {
            DataSource ds = TestDataSources.h2("catalog_provider_h2");
            TestSchema.apply(ds, "h2");
            dataSource = ds;
        }
        jdbc = new JdbcTemplate(dataSource);
        meta = new JdbcMetaCatalogRepository(jdbc);
        lineage = new JdbcLineageRepository(jdbc, new JdbcLineageCatalogRepository(jdbc));
        for (String t : List.of("meta_column", "meta_table",
                "lineage_edge", "lineage_column", "lineage_table", "lineage_version")) {
            jdbc.update("delete from " + t);
        }
    }

    // ---------------- 作为解析来源 ----------------

    @Test
    public void resolvesTableStructureFromCatalog() {
        seed("ods", "user", List.of(
                column("id", "bigint", 1, false),
                column("name", "string", 2, false),
                column("pt", "string", 3, true)));

        MetadataResolution resolution = provider(TENANT_A).resolve(Set.of(table("ods", "user")));

        SchemaTable resolved = resolution.resolved().get(table("ods", "user"));
        assertEquals(List.of("id", "name", "pt"), resolved.getColumns());
        assertEquals("分区列要带出来，Hive/Spark 血缘依赖它",
                List.of("pt"), resolved.getPartitionColumns());
    }

    /** SQL 里大小写随手写，目录按小写全名存，两边要能对上。 */
    @Test
    public void lookupIsCaseInsensitive() {
        seed("ods", "user", List.of(column("id", "bigint", 1, false)));

        MetadataResolution resolution = provider(TENANT_A).resolve(Set.of(table("ODS", "USER")));

        assertEquals(1, resolution.resolved().size());
        assertTrue(resolution.unresolved().isEmpty());
    }

    /** 查不到的表进 unresolved，不能抛异常 —— 后面还有别的来源要接手。 */
    @Test
    public void unknownTableGoesToUnresolvedWithoutThrowing() {
        seed("ods", "user", List.of(column("id", "bigint", 1, false)));

        MetadataResolution resolution = provider(TENANT_A)
                .resolve(Set.of(table("ods", "user"), table("ods", "missing")));

        assertEquals(1, resolution.resolved().size());
        assertEquals(Set.of(table("ods", "missing")), resolution.unresolved());
    }

    /** 有表无字段等于没用，交给后面的来源，别让解析器拿到一张空表。 */
    @Test
    public void tableWithoutColumnsIsTreatedAsUnresolved() {
        seed("ods", "empty", List.of());

        MetadataResolution resolution = provider(TENANT_A).resolve(Set.of(table("ods", "empty")));

        assertTrue(resolution.resolved().isEmpty());
        assertEquals(Set.of(table("ods", "empty")), resolution.unresolved());
    }

    @Test
    public void emptyInputShortCircuits() {
        assertTrue(provider(TENANT_A).resolve(Set.of()).resolved().isEmpty());
    }

    /** 别的租户的元数据不该被拿来解析。 */
    @Test
    public void doesNotLeakAcrossTenants() {
        seed("ods", "user", List.of(column("id", "bigint", 1, false)));

        MetadataResolution resolution = provider(TENANT_B).resolve(Set.of(table("ods", "user")));

        assertTrue(resolution.resolved().isEmpty());
    }

    // ---------------- 元数据与血缘的隔离 ----------------

    /**
     * 保存血缘不会往元数据目录里写东西。
     *
     * <p>血缘图里的表可能是 {@code InferredMetadataProvider} 猜出来的、或者是临时表。
     * 让它们进元数据，下次解析就会把猜测当成事实，错误会固化下来。
     */
    @Test
    public void savingLineageDoesNotTouchMetaCatalog() {
        lineage.saveVersions(TENANT_A, "hive", "insert into dwd.d select c from ods.inferred;", graph());

        assertTrue("血缘侧应写入了表", count("lineage_table") > 0);
        assertEquals("元数据目录必须纹丝不动", 0, count("meta_table"));
        assertEquals(0, count("meta_column"));
    }

    /** 反过来：删掉一张元数据表，已存的血缘不受影响。 */
    @Test
    public void deletingMetaTableDoesNotAffectStoredLineage() {
        seed("ods", "inferred", List.of(column("c", "string", 1, false)));
        lineage.saveVersions(TENANT_A, "hive", "insert into dwd.d select c from ods.inferred;", graph());

        int edgesBefore = count("lineage_edge");
        long metaTableId = meta.findByFullName(TENANT_A, "cat.ods.inferred").orElseThrow().id();
        meta.deleteTable(TENANT_A, metaTableId);

        assertEquals(0, count("meta_table"));
        assertEquals("血缘边不应受影响", edgesBefore, count("lineage_edge"));
        assertTrue("血缘表目录不应受影响", count("lineage_table") > 0);
    }

    // ------------------------------------------------------------------

    private CatalogMetadataProvider provider(LineageContext ctx) {
        return new CatalogMetadataProvider(meta, ctx);
    }

    /** 表全名恒为三段，所以固定装置也得带上数据目录 —— catalog_name 是 NOT NULL。 */
    private static final String CAT = "cat";

    private void seed(String schema, String tableName, List<MetaColumnRow> columns) {
        meta.upsert(TENANT_A, MetaTableRow.of(CAT, schema, tableName,
                        MetaNames.tableFullName(CAT, schema, tableName),
                        null, null, null, "hive", MetaSource.DDL, null),
                columns, false);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("select count(*) from " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private static MetaColumnRow column(String name, String type, int ordinal, boolean partition) {
        return MetaColumnRow.of(name, type, null, ordinal, partition, true, false, MetaSource.DDL);
    }

    private static QualifiedObjectName table(String schema, String name) {
        return new QualifiedObjectName("", schema, name);
    }

    /** 一条 ods.inferred.c -> dwd.d.c 的血缘。 */
    private static LineageGraph graph() {
        LineageGraph.Field target = new LineageGraph.Field("cat.dwd.d.c", false, 0, 0, null);
        LineageGraph.Field source = new LineageGraph.Field("cat.ods.inferred.c", true, 0, 1, null);
        LineageGraph.Section section = new LineageGraph.Section(
                List.of(new LineageGraph.Item(target, List.of(source))), 1, 1);
        return new LineageGraph(0, new LineageGraph.Data(section, section),
                List.of(), List.of(), List.of(), "", "test");
    }
}
