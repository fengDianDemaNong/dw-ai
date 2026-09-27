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

    /** dw-org 全库应有的表（跨 V1~V23 全部迁移）。 */
    private static final List<String> EXPECTED_TABLES = List.of(
            "tenants", "users", "user_tenants", "projects", "project_members",
            "platform_access", "tenant_grants", "tenant_licenses", "tenant_llm",
            "appearance_prefs", "tenant_ai_prompts", "tenant_knowledge_articles",
            "refresh_tokens", "service_registry", "nav_nodes",
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
            // nav_nodes 是 V23 新增的（菜单树：分组与菜单合并成一张表，parent_id 自引用，
            // 层级不限深度）。这几列各自都是这张表存在的理由，漏一个就少一种能力，
            // 而「表建出来了」不会报任何错：漏 parent_id 就没有层级、漏 product/ref/mounted
            // 挂载整个失效、漏 admin_only 那批菜单会显示给所有成员、漏 empty_policy
            // 空目录的行为就成了某个没人预期的默认值。
            "nav_nodes", List.of("id", "scope", "parent_id", "title", "path", "icon", "perm",
                    "sort_order", "enabled", "admin_only", "product", "ref", "mounted", "empty_policy"),
            // product_roles / product_role_perms 是 V20 新增的（产品角色）。
            // is_admin 与 builtin 是这张表存在的理由：漏了 is_admin，租户管理员在该产品里
            // 就找不到短路映射的目标；漏了 builtin，管理员能把出厂角色删掉。
            "product_roles", List.of("id", "product", "code", "label", "hint",
                    "is_admin", "builtin", "sort_order"),
            "product_role_perms", List.of("role_id", "perm"),
            "tenant_licenses", List.of("tenant_id", "modules", "ai_caps"));

    /**
     * V23 的 {@code (scope, parent_id, title)} 唯一约束真的建出来了。
     *
     * <p>这条必须单独验：约束没建出来时上面每条断言照样绿 —— 建表语句能跑、列也都在，
     * 症状要等管理员在同一层建了两个同名菜单才现形，表现为侧栏里出现两条一模一样的入口，
     * 看起来像渲染重复，其实是唯一键没建。
     *
     * <p>三个方向都要钉住，因为「收得太松」与「收得太紧」都坏：
     * 换壳要能重名（两个壳各有一套侧栏）、同壳不同层要能重名（层级是菜单树的核心）、
     * 同壳同层不能重名。
     *
     * <p><b>顶层那一行用的是空串而不是 {@code null}</b> —— 这条就是「为什么不用 NULL」的
     * 可执行说明：唯一约束里 {@code null} 与任何值都不相等，用 {@code null} 表示顶层时
     * 顶层可以建出无数个同名节点，约束形同虚设。
     *
     * <p>用完自己清干净：这个 H2 库在本类各测试间共享，留一行会污染别人的列表断言。
     */
    @Test
    void navNodesUniqueConstraintCoversScopeParentTitle() {
        JdbcTemplate db = new JdbcTemplate(dataSource);
        String insert = "insert into nav_nodes (id, scope, parent_id, title, sort_order)"
                + " values (?, ?, ?, ?, 0)";
        String cleanup = "delete from nav_nodes where id in"
                + " ('t-n1', 't-n2', 't-n3', 't-n4', 't-n5')";

        try {
            db.update(insert, "t-n1", "project", "", "数据地图");

            // 换个壳就是另一个节点 —— 必须能共存（两个壳各有一套侧栏）
            db.update(insert, "t-n2", "workbench", "", "数据地图");

            // 同一壳、不同父节点下的同名节点必须能共存：层级是这棵树的全部意义，
            // 收得比 (scope, parent_id) 更严就等于「全树不能重名」
            db.update(insert, "t-n3", "project", "", "数仓");
            db.update(insert, "t-n4", "project", "t-n3", "数据地图");

            // 同壳同层再建一个同名的仍要撞
            try {
                db.update(insert, "t-n5", "project", "", "数据地图");
                org.junit.jupiter.api.Assertions.fail("同壳同层的同名菜单应当被唯一约束挡住");
            } catch (org.springframework.dao.DuplicateKeyException expected) {
                // 正是预期
            }
        } finally {
            db.update(cleanup);
        }
    }

    /**
     * V23 把两张旧表 DROP 了。
     *
     * <p>这不只是洁癖：{@code nav_items} 还在，说明迁移只跑了前半段（建新表、灌种子），
     * 而旧的读路径如果也还在（见 {@code CrossServiceDesignGuardTest}），就会有一份
     * 没人维护的菜单继续被读出来 —— 表现是「改了新配置但侧栏不变」。
     */
    @Test
    void legacyNavTablesAreDropped() {
        Map<String, Set<String>> actual = allColumns();
        assertFalse(actual.containsKey("nav_items"), "nav_items 应当已被 V23 删掉");
        assertFalse(actual.containsKey("nav_groups"), "nav_groups 应当已被 V23 删掉");
    }

    /**
     * V23 的种子：org 自有菜单必须落库。
     *
     * <p><b>这条守的是「升级后侧栏不空」这个承诺。</b>V23 之前这批菜单硬编码在
     * {@code ui/src/config/sysNav.ts} 里，迁移之后前端不再有它们；种子没灌或灌漏了，
     * 表现是所有人的侧栏少了「用户管理 / 角色管理」这几项，而 Flyway 报的是「成功」。
     *
     * <p>「成员管理」的路径单独钉：它是<b>模板</b>（含运行期的项目码），前端渲染时替换
     * {@code {code}}。写成任何别的形状（比如带上某个具体项目码）都只会在那个项目里才对，
     * 进别的项目这一项就点不动。
     */
    @Test
    void navNodesAreSeeded() {
        JdbcTemplate db = new JdbcTemplate(dataSource);

        Integer seeded = db.queryForObject(
                "select count(*) from nav_nodes where id like 'nav-sys%' or id like 'nav-proj%'",
                Integer.class);
        assertEquals(9, seeded == null ? 0 : seeded,
                "种子应当是 6 条工作台壳（系统管理 + 5 项）+ 3 条项目壳");

        assertEquals("/org/project/{code}/members", db.queryForObject(
                        "select path from nav_nodes where id = 'nav-proj-members'", String.class),
                "项目码是运行期才知道的，菜单里只能存模板");
        assertEquals("iam:member", db.queryForObject(
                        "select perm from nav_nodes where id = 'nav-proj-members'", String.class),
                "「成员管理」的可见性由这个权限词决定");
        assertEquals(Boolean.TRUE, db.queryForObject(
                        "select admin_only from nav_nodes where id = 'nav-sys-users'", Boolean.class),
                "「用户管理」只有租户管理员可见（原先由 buildSysNav 的入参在前端算）");
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
