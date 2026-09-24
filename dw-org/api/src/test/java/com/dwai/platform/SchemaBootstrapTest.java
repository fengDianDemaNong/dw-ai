package com.dwai.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

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

    /** dw-org 全库应有的表（跨 V1~V15 全部迁移）。 */
    private static final List<String> EXPECTED_TABLES = List.of(
            "tenants", "users", "user_tenants", "projects", "project_members",
            "platform_access", "tenant_grants", "tenant_licenses", "tenant_llm",
            "appearance_prefs", "tenant_ai_prompts", "tenant_knowledge_articles",
            "refresh_tokens", "service_registry");

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
}
