package persistence;

import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.enums.MetadataSourceType;
import com.dwai.lineage.persistence.JdbcMetadataSourceRepository;
import com.dwai.lineage.persistence.MetadataSourceRepository;
import com.dwai.lineage.persistence.MetadataSourceRow;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 元数据服务配置的存取，含跨租户越权的负向用例。
 *
 * <p>与血缘持久化同一套约定：所有查询强制带租户过滤，拿不到别的租户的数据。
 */
public class MetadataSourceRepositoryTest {

    private static final LineageContext TENANT_A = new LineageContext(1, 10);
    private static final LineageContext TENANT_B = new LineageContext(2, 20);

    private static DataSource dataSource;

    private MetadataSourceRepository repository;
    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        if (dataSource == null) {
            DataSource ds = TestDataSources.h2("metadata_source_h2");
            TestSchema.apply(ds, "h2");
            dataSource = ds;
        }
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcMetadataSourceRepository(jdbc);
        jdbc.update("delete from metadata_source");
    }

    @Test
    public void insertAndRead() {
        long id = repository.insert(TENANT_A, row("gravitino-prd", MetadataSourceType.GRAVITINO,
                "http://localhost:8090", "cipher-text", null, 10, true));

        MetadataSourceRow saved = repository.findById(TENANT_A, id).orElseThrow();

        assertEquals("gravitino-prd", saved.name());
        assertEquals(MetadataSourceType.GRAVITINO, saved.type());
        assertEquals("http://localhost:8090", saved.baseUrl());
        assertEquals(10, saved.priority());
        assertTrue(saved.enabled());
        assertTrue(saved.hasCredential());
    }

    @Test
    public void listIsOrderedByPriority() {
        repository.insert(TENANT_A, row("low", MetadataSourceType.DBX, "http://a", null, null, 200, true));
        repository.insert(TENANT_A, row("high", MetadataSourceType.GRAVITINO, "http://b", null, null, 1, true));

        List<MetadataSourceRow> rows = repository.list(TENANT_A);

        assertEquals("数字小的排前面", List.of("high", "low"),
                rows.stream().map(MetadataSourceRow::name).toList());
    }

    @Test
    public void listEnabledSkipsDisabled() {
        repository.insert(TENANT_A, row("on", MetadataSourceType.DBX, "http://a", null, null, 1, true));
        repository.insert(TENANT_A, row("off", MetadataSourceType.DBX, "http://b", null, null, 2, false));

        assertEquals(List.of("on"),
                repository.listEnabled(TENANT_A).stream().map(MetadataSourceRow::name).toList());
    }

    /** 前端不回传凭据明文时用这条路径：只改其它字段，库里的密文原样保留。 */
    @Test
    public void updateCanKeepCredential() {
        long id = repository.insert(TENANT_A, row("src", MetadataSourceType.DBX,
                "http://old", "original-cipher", null, 100, true));

        repository.update(TENANT_A, id, row("src-renamed", MetadataSourceType.DBX,
                "http://new", null, null, 5, false), true);

        MetadataSourceRow updated = repository.findById(TENANT_A, id).orElseThrow();
        assertEquals("src-renamed", updated.name());
        assertEquals("http://new", updated.baseUrl());
        assertEquals(5, updated.priority());
        assertFalse(updated.enabled());
        assertEquals("凭据未回传时必须保持原值", "original-cipher", updated.credential());
    }

    @Test
    public void updateCanReplaceCredential() {
        long id = repository.insert(TENANT_A, row("src", MetadataSourceType.DBX,
                "http://x", "original-cipher", null, 100, true));

        repository.update(TENANT_A, id, row("src", MetadataSourceType.DBX,
                "http://x", "new-cipher", null, 100, true), false);

        assertEquals("new-cipher", repository.findById(TENANT_A, id).orElseThrow().credential());
    }

    // ---------------- 跨租户越权（负向） ----------------

    @Test
    public void otherTenantCannotSeeTheSource() {
        repository.insert(TENANT_A, row("a-only", MetadataSourceType.DBX, "http://a", null, null, 1, true));

        assertTrue("B 不该看到 A 的配置", repository.list(TENANT_B).isEmpty());
        assertTrue(repository.listEnabled(TENANT_B).isEmpty());
    }

    @Test
    public void otherTenantCannotReadById() {
        long id = repository.insert(TENANT_A, row("a-only", MetadataSourceType.DBX,
                "http://a", null, null, 1, true));

        assertEquals(Optional.empty(), repository.findById(TENANT_B, id));
    }

    @Test
    public void otherTenantCannotUpdateOrDelete() {
        long id = repository.insert(TENANT_A, row("a-only", MetadataSourceType.DBX,
                "http://a", "cipher", null, 1, true));

        assertFalse("B 改不动 A 的配置", repository.update(TENANT_B, id,
                row("hijacked", MetadataSourceType.DBX, "http://evil", null, null, 1, true), true));
        assertFalse("B 删不掉 A 的配置", repository.delete(TENANT_B, id));

        MetadataSourceRow untouched = repository.findById(TENANT_A, id).orElseThrow();
        assertEquals("a-only", untouched.name());
        assertEquals("http://a", untouched.baseUrl());
    }

    private static MetadataSourceRow row(String name, MetadataSourceType type, String baseUrl,
                                         String credential, String extraConfig,
                                         int priority, boolean enabled) {
        return new MetadataSourceRow(0, 0, name, type, baseUrl, credential, extraConfig,
                priority, enabled, null, null);
    }
}
