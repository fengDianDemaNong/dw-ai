package persistence;

import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.persistence.JdbcTenantAdminRepository;
import com.dwai.lineage.persistence.ProjectRow;
import com.dwai.lineage.persistence.TenantAdminRepository;
import com.dwai.lineage.persistence.TenantRow;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * 租户/项目管理面的存取。
 *
 * <p>与本包其它仓储测试的关注点不同：这里验证的是「租户能不能被创建、能不能被安全地删除」，
 * 而不是「跨租户能不能读到数据」—— 后者由 {@link MetaCatalogRepositoryTest} 等负责。
 */
public class TenantAdminRepositoryTest {

    private static DataSource dataSource;

    private TenantAdminRepository repository;
    private JdbcTemplate jdbc;

    @Before
    public void setUp() {
        if (dataSource == null) {
            DataSource ds = TestDataSources.h2("tenant_admin_h2");
            TestSchema.apply(ds, "h2");
            dataSource = ds;
        }
        jdbc = new JdbcTemplate(dataSource);
        repository = new JdbcTenantAdminRepository(jdbc);

        // 迁移种的默认租户 1 / 项目 1 保留，只清掉上一个用例建的
        jdbc.update("delete from metadata_source where tenant_id <> 1");
        jdbc.update("delete from meta_table where tenant_id <> 1");
        jdbc.update("delete from project where tenant_id <> 1");
        jdbc.update("delete from tenant where id <> 1");
    }

    @Test
    public void defaultTenantAndProjectComeFromMigration() {
        TenantRow tenant = repository.findTenant(1).orElseThrow();
        assertEquals("default", tenant.code());
        assertTrue(tenant.enabled());

        List<ProjectRow> projects = repository.listProjects(1);
        assertEquals(1, projects.size());
        assertEquals("default", projects.get(0).code());
    }

    @Test
    public void createTenantThenProject() {
        long tenantId = repository.createTenant("acme", "ACME 数据平台");
        assertTrue("自增主键应当避开迁移种的 id=1", tenantId > 1);

        TenantRow saved = repository.findTenant(tenantId).orElseThrow();
        assertEquals("acme", saved.code());
        assertEquals("ACME 数据平台", saved.name());
        assertTrue(saved.enabled());

        long projectId = repository.createProject(tenantId, "dw", "数仓", "离线数仓");
        ProjectRow project = repository.findProject(tenantId, projectId).orElseThrow();
        assertEquals("dw", project.code());
        assertEquals("离线数仓", project.description());
        assertEquals(tenantId, project.tenantId());
    }

    @Test
    public void tenantCodeIsGloballyUnique() {
        repository.createTenant("dup", "第一个");
        assertThrows(DuplicateKeyException.class, () -> repository.createTenant("dup", "第二个"));
    }

    /** 项目编码只在租户内唯一：两个不同租户可以各有一个 default 项目。 */
    @Test
    public void projectCodeIsUniqueWithinTenantOnly() {
        long a = repository.createTenant("t-a", "A");
        long b = repository.createTenant("t-b", "B");

        repository.createProject(a, "default", "默认项目", null);
        repository.createProject(b, "default", "默认项目", null);

        assertThrows(DuplicateKeyException.class,
                () -> repository.createProject(a, "default", "重复", null));
    }

    @Test
    public void findProjectIsScopedToItsTenant() {
        long a = repository.createTenant("owner", "拥有者");
        long b = repository.createTenant("other", "另一个");
        long projectId = repository.createProject(a, "p", "项目", null);

        assertTrue(repository.findProject(a, projectId).isPresent());
        assertTrue("换个租户就不该查得到", repository.findProject(b, projectId).isEmpty());
    }

    @Test
    public void updateAndDisableTenant() {
        long id = repository.createTenant("t", "旧名");

        assertTrue(repository.updateTenant(id, "新名", 0));

        TenantRow updated = repository.findTenant(id).orElseThrow();
        assertEquals("新名", updated.name());
        assertFalse("status=0 应当投影成 enabled=false", updated.enabled());
    }

    @Test
    public void updateProjectIsScopedToItsTenant() {
        long a = repository.createTenant("t-a2", "A");
        long b = repository.createTenant("t-b2", "B");
        long projectId = repository.createProject(a, "p", "原名", null);

        assertFalse("拿别的租户 id 去改，不该命中",
                repository.updateProject(b, projectId, "被改了", null, 1));
        assertEquals("原名", repository.findProject(a, projectId).orElseThrow().name());
    }

    @Test
    public void countBusinessRowsIgnoresBuiltInCatalogSource() {
        long tenantId = repository.createTenant("fresh", "新租户");
        long projectId = repository.createProject(tenantId, "default", "默认项目", null);
        LineageContext ctx = new LineageContext(tenantId, projectId);

        // 元数据服务是租户级的，不属于任何项目，因此不该计入项目的业务数据
        jdbc.update("insert into metadata_source(tenant_id, name, type, base_url, "
                        + "priority, enabled) values(?,?,?,?,?,1)",
                tenantId, "本地元数据目录", "CATALOG", "local://catalog", 50);

        assertEquals("元数据服务不属于项目，不该阻止项目被删除",
                0, repository.countBusinessRows(ctx));
    }

    @Test
    public void countBusinessRowsSeesRealData() {
        long tenantId = repository.createTenant("busy", "有数据的租户");
        long projectId = repository.createProject(tenantId, "default", "默认项目", null);
        LineageContext ctx = new LineageContext(tenantId, projectId);

        jdbc.update("insert into meta_table(tenant_id, project_id, catalog_name, schema_name, "
                        + "table_name, full_name, source) values(?,?,?,?,?,?,?)",
                tenantId, projectId, "cat", "ods", "t1", "cat.ods.t1", "DDL");
        assertEquals(1, repository.countBusinessRows(ctx));

        jdbc.update("insert into metadata_source(tenant_id, name, type, base_url, "
                        + "priority, enabled) values(?,?,?,?,?,1)",
                tenantId, "dbx-local", "DBX", "http://localhost:4224", 100);
        assertEquals("元数据服务是租户级的，加了也不影响项目的业务数据量",
                1, repository.countBusinessRows(ctx));
    }

    @Test
    public void countBusinessRowsIsScopedToOneProject() {
        long tenantId = repository.createTenant("multi", "多项目");
        long p1 = repository.createProject(tenantId, "p1", "项目一", null);
        long p2 = repository.createProject(tenantId, "p2", "项目二", null);

        jdbc.update("insert into meta_table(tenant_id, project_id, catalog_name, schema_name, "
                        + "table_name, full_name, source) values(?,?,?,?,?,?,?)",
                tenantId, p1, "cat", "ods", "t1", "cat.ods.t1", "DDL");

        assertEquals(1, repository.countBusinessRows(new LineageContext(tenantId, p1)));
        assertEquals("另一个项目不该被算进来",
                0, repository.countBusinessRows(new LineageContext(tenantId, p2)));
    }

    @Test
    public void deleteProjectThenTenant() {
        long tenantId = repository.createTenant("gone", "待删除");
        long projectId = repository.createProject(tenantId, "p", "项目", null);

        assertTrue(repository.deleteProject(tenantId, projectId));
        assertTrue(repository.findProject(tenantId, projectId).isEmpty());

        assertTrue(repository.deleteTenant(tenantId));
        assertTrue(repository.findTenant(tenantId).isEmpty());
    }

    @Test
    public void listTenantsAndAllProjects() {
        long a = repository.createTenant("l-a", "A");
        long b = repository.createTenant("l-b", "B");
        repository.createProject(a, "p1", "一", null);
        repository.createProject(b, "p1", "一", null);
        repository.createProject(b, "p2", "二", null);

        assertEquals("含迁移种的默认租户", 3, repository.listTenants().size());
        assertEquals("含默认项目", 4, repository.listAllProjects().size());
        assertEquals(2, repository.listProjects(b).size());
    }
}
