package com.dwai.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * dw-model 的最小回归网之一：空库冷启动必须由 Flyway 建出全部表。
 *
 * <p>这个模块此前<b>一个测试都没有</b>。它是「Flyway 能建库」这条底线的第一道守卫 ——
 * 迁移脚本写错、方言目录选错、EnvironmentPostProcessor 没生效，这里都会立刻变红。
 *
 * <p>用独立命名的内存库：schema 只能来自 Flyway，不是测试自己种的。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dwmodel_schema;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
})
class SchemaBootstrapTest {

    /** dw-model 全库应有的表（跨 V1~V14 全部迁移），含组织与建模两侧。 */
    private static final List<String> EXPECTED_TABLES = List.of(
            // 组织与平台（与 dw-org 同源）
            "tenants", "users", "user_tenants", "projects", "project_members",
            "platform_access", "tenant_grants", "tenant_licenses", "tenant_llm",
            "appearance_prefs", "tenant_ai_prompts", "tenant_knowledge_articles",
            "refresh_tokens",
            // 建模与治理（dw-model 独有）
            "warehouse_tables", "table_columns", "table_versions", "domains",
            "data_grades", "layer_rules", "word_roots", "modeling_drafts",
            "metrics", "quality_rules", "approvals", "materialize_recs",
            "serve_folders", "serve_metrics", "query_clusters", "query_logs",
            "api_calls", "jobs");

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
    void allTablesExistAfterMigration() {
        JdbcTemplate db = new JdbcTemplate(dataSource);

        for (String table : EXPECTED_TABLES) {
            Integer count = db.queryForObject("select count(*) from " + table, Integer.class);
            assertTrue(count != null && count >= 0, "表 " + table + " 查询失败，说明没有被建出来");
        }
    }
}
