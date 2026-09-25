package com.dwai.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dw-org 的最小回归网之一：空库冷启动必须由 Flyway 建出全部表。
 *
 * <p>这个模块此前<b>一个测试都没有</b>。它是「Flyway 能建库」这条底线的第一道守卫 ——
 * 迁移脚本写错、方言目录选错、EnvironmentPostProcessor 没生效，这里都会立刻变红。
 *
 * <p>用随机命名的内存库（见 {@code application-test} 的 datasource.url 覆盖）：
 * schema 只能来自 Flyway，不是测试自己种的。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class SchemaBootstrapTest {

    /** dw-org 全库应有的表（跨 V1~V20 全部迁移）。 */
    private static final List<String> EXPECTED_TABLES = List.of(
            "tenants", "users", "user_tenants", "projects", "project_members",
            "platform_access", "tenant_grants", "tenant_licenses", "tenant_llm",
            "appearance_prefs", "tenant_ai_prompts", "tenant_knowledge_articles",
            "refresh_tokens", "service_registry", "nav_items", "nav_groups",
            "product_roles", "product_role_perms");

    /**
     * 关键列的存在性断言 —— 只建了表、列漏了是另一种失败形态。
     *
     * <p>{@code count(*)} 查得动、Flyway 也报成功，坏处要等真跑到那条 SQL 才显形：
     * 实体映射到不存在的列时，症状是运行期才炸，而迁移测试全绿。
     * 这里只钉<b>迁移新增的、或实体立刻要读</b>的列，不追求覆盖全部。
     */
    private static final Map<String, List<String>> EXPECTED_COLUMNS = Map.of(
            // frontend_url 是 V16 新增的，门户 iframe 嵌入读的就是它
            "service_registry", List.of("product", "version", "base_url", "seen_at", "frontend_url"),
            // nav_items 是 V17 新增的（门户菜单）；scope / group_title / perm 是 V18 加的
            "nav_items", List.of("id", "product", "label", "icon", "path", "sort_order", "enabled",
                    "scope", "group_title", "perm"),
            // nav_groups 是 V19 新增的（分组管理）。empty_policy 是这张表存在的理由之一，
            // 漏了它整张表就只剩个空壳，却又「表建出来了」不报错，所以要单独钉住。
            "nav_groups", List.of("id", "scope", "product", "title", "sort_order", "empty_policy"),
            // product_roles / product_role_perms 是 V20 新增的（产品角色）。
            // is_admin 与 builtin 是这张表存在的理由：漏了 is_admin，租户管理员在该产品里
            // 就找不到短路映射的目标；漏了 builtin，管理员能把出厂角色删掉。
            "product_roles", List.of("id", "product", "code", "label", "hint",
                    "is_admin", "builtin", "sort_order"),
            "product_role_perms", List.of("role_id", "perm"),
            "tenant_licenses", List.of("tenant_id", "modules", "ai_caps"));

    /**
     * V18 把唯一约束从 {@code (product, path)} 换成了 {@code (scope, product, path)}。
     *
     * <p>这条必须单独验：旧约束<b>不会</b>让上面任何一条断言变红 —— 建表语句能跑、
     * 列也都在，症状要等管理员把同一条路径分别挂到工作台与项目壳时才现形，
     * 那时报的是 400「已经有指向 /lineage/search 的菜单项了」，看起来像数据重复而不是约束没改。
     */
    @Test
    void navItemsUniqueConstraintCoversScope() {
        JdbcTemplate db = new JdbcTemplate(dataSource);
        String insert = "insert into nav_items (id, product, scope, group_title, label, icon, path, perm,"
                + " sort_order, enabled) values (?, ?, ?, '', ?, '', ?, '', 0, true)";

        db.update(insert, "t-p1", "metadata", "project", "血缘", "/lineage/tables");
        // 同 product + 同 path，只是换了壳 —— 必须能共存，这正是 V18 放宽约束的理由
        db.update(insert, "t-w1", "metadata", "workbench", "血缘", "/lineage/tables");

        // 同一壳下再配一遍仍要撞约束，否则侧栏会出现两个一模一样的入口
        try {
            db.update(insert, "t-p2", "metadata", "project", "血缘", "/lineage/tables");
            org.junit.jupiter.api.Assertions.fail("同一壳下重复路径应当被唯一约束挡住");
        } catch (org.springframework.dao.DuplicateKeyException expected) {
            // 正是预期
        }
    }

    /**
     * V19 的 {@code (scope, product, title)} 唯一约束真的建出来了。
     *
     * <p>这条也要单独验：约束没建出来时上面每条断言照样绿，症状要等管理员在同一壳同一
     * 产品下建了两个同名分组才现形 —— 表现为侧栏出现两段同名分组、顺序还各按各的
     * {@code sortOrder}，看起来像排序逻辑坏了，其实是唯一键没建。
     *
     * <p>用完自己清干净：这个 H2 库在本类各测试间共享，留一行会污染别人的列表断言。
     */
    @Test
    void navGroupsUniqueConstraintCoversScopeProductTitle() {
        JdbcTemplate db = new JdbcTemplate(dataSource);
        String insert = "insert into nav_groups (id, scope, product, title, sort_order, empty_policy)"
                + " values (?, ?, ?, ?, 0, 'hide')";
        String cleanup = "delete from nav_groups where id in ('t-g1', 't-g2', 't-g3')";

        try {
            db.update(insert, "t-g1", "project", "metadata", "数据地图");

            // 同 product + 同 title，只是换了壳 —— 必须能共存（两个壳各有一套侧栏）
            db.update(insert, "t-g2", "workbench", "metadata", "数据地图");

            // 同一壳同一产品下再建一遍同名分组仍要撞约束
            try {
                db.update(insert, "t-g3", "project", "metadata", "数据地图");
                org.junit.jupiter.api.Assertions.fail("同壳同产品下的同名分组应当被唯一约束挡住");
            } catch (org.springframework.dao.DuplicateKeyException expected) {
                // 正是预期
            }
        } finally {
            db.update(cleanup);
        }
    }

    /**
     * V20 的 {@code (product, code)} 唯一约束真的建出来了。
     *
     * <p>约束没建出来时上面每条断言照样绿，症状要等管理员在两个产品下用了同一个角色码、
     * 或同一产品下建了两个同码角色才现形 —— 后者表现为「判权按先查到的那条走」，
     * 权限时而对时而不对，是最难查的一类。
     */
    @Test
    void productRolesUniqueConstraintCoversProductCode() {
        JdbcTemplate db = new JdbcTemplate(dataSource);
        String insert = "insert into product_roles (id, product, code, label, hint, is_admin, builtin,"
                + " sort_order) values (?, ?, ?, '测试角色', '', false, false, 0)";
        String cleanup = "delete from product_roles where id in ('t-pr1', 't-pr2', 't-pr3')";

        try {
            db.update(insert, "t-pr1", "warehouse", "t_probe");

            // 同 code 不同产品必须能共存：两个产品各有一套角色码空间
            db.update(insert, "t-pr2", "metadata", "t_probe");

            // 同一产品下再建同码角色仍要撞约束
            try {
                db.update(insert, "t-pr3", "warehouse", "t_probe");
                org.junit.jupiter.api.Assertions.fail("同产品下的同码角色应当被唯一约束挡住");
            } catch (org.springframework.dao.DuplicateKeyException expected) {
                // 正是预期
            }
        } finally {
            db.update(cleanup);
        }
    }

    /**
     * V20 的种子：内置角色与它们的权限词必须落库。
     *
     * <p><b>这条守的是「升级后行为不变」这个承诺。</b>判权改成「先查表、查不到才回落
     * {@code Perms}」，如果种子没灌或灌漏了，{@code project_members} 里的历史行
     * （role = admin/modeler/viewer）会集体判否 —— 表现为升级完所有人突然没权限了，
     * 而 Flyway 报的是「成功」。
     *
     * <p>只数<b>内置</b>（{@code prole-} 前缀）那些行，因为同库的其他用例会插自己的角色。
     * 「种子内容是否与 {@code Perms.java} 逐字一致」由 {@code ProductRoleTest} 用反射守着。
     */
    @Test
    void productRolesAreSeeded() {
        JdbcTemplate db = new JdbcTemplate(dataSource);

        Integer roles = db.queryForObject(
                "select count(*) from product_roles where id like 'prole-%'", Integer.class);
        assertEquals(6, roles == null ? 0 : roles, "种子应当是 2 个产品 × 3 个内置角色");

        Integer perms = db.queryForObject(
                "select count(*) from product_role_perms where role_id like 'prole-%'", Integer.class);
        assertEquals(20, perms == null ? 0 : perms,
                "种子权限词应当是 20 条（warehouse 6+3+2、metadata 4+3+2）");

        // 每个有角色的产品必须恰有一个管理角色 —— AccessService.roleOf 对租户管理员
        // 是短路映射到它的，缺了这个产品，租户管理员进去反而判否。
        for (String product : List.of("warehouse", "metadata")) {
            Integer admins = db.queryForObject(
                    "select count(*) from product_roles where product = ? and is_admin = true",
                    Integer.class, product);
            assertEquals(1, admins == null ? 0 : admins,
                    product + " 应当恰有一个管理角色");
        }
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayAppliesMigrationsOnEmptyDatabase() {
        JdbcTemplate db = new JdbcTemplate(dataSource);

        List<String> applied = db.query(
                "select \"version\" from \"flyway_schema_history\" where \"success\" = true order by \"installed_rank\"",
                (rs, i) -> rs.getString(1));
        assertFalse(applied.isEmpty(), "Flyway 没有应用任何迁移 —— 检查 db/migration 与 locations 配置");
    }

    @Test
    void allOrgTablesExistAfterMigration() {
        JdbcTemplate db = new JdbcTemplate(dataSource);

        for (String table : EXPECTED_TABLES) {
            Integer count = db.queryForObject("select count(*) from " + table, Integer.class);
            assertTrue(count != null && count >= 0, "表 " + table + " 查询失败，说明没有被建出来");
        }
    }

    @Test
    void bootstrapAdminIsSeeded() {
        JdbcTemplate db = new JdbcTemplate(dataSource);

        Integer admins = db.queryForObject(
                "select count(*) from users where username = ?", Integer.class, "admin");
        assertEquals(1, admins == null ? 0 : admins,
                "启动时应由 BootstrapAdminRunner 建出内置管理员 admin");
    }

    /** 见 {@link #EXPECTED_COLUMNS}：表建出来了，列也得建出来。 */
    @Test
    void expectedColumnsExistAfterMigration() {
        Map<String, Set<String>> actual = allColumns();

        EXPECTED_COLUMNS.forEach((table, columns) -> {
            Set<String> cols = actual.get(table);
            assertTrue(cols != null, "表 " + table + " 没有被建出来");
            for (String column : columns) {
                assertTrue(cols.contains(column),
                        table + " 缺少列 " + column + " —— 迁移只建了表没建列");
            }
        });
    }

    /** 全库「表名 → 列名」索引；两侧都转小写，避开 H2 / MySQL 的大小写差异。 */
    private Map<String, Set<String>> allColumns() {
        return new JdbcTemplate(dataSource).execute(
                (ConnectionCallback<Map<String, Set<String>>>) conn -> {
                    Map<String, Set<String>> out = new LinkedHashMap<>();
                    try (ResultSet rs = conn.getMetaData().getColumns(null, null, null, null)) {
                        while (rs.next()) {
                            out.computeIfAbsent(
                                            rs.getString("TABLE_NAME").toLowerCase(Locale.ROOT),
                                            k -> new LinkedHashSet<>())
                                    .add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
                        }
                    }
                    return out;
                });
    }
}
