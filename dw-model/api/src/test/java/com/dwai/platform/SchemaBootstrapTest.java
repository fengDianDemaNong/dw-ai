package com.dwai.platform;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableFieldInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfo;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
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

    /** dw-model 全库应有的表（跨 V1~V15 全部迁移），含组织与建模两侧。 */
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

    /**
     * dw-common 的实体是**三个模块共用**的：加一个字段，三个模块的库都要跟着加列。
     *
     * <p>dw-model 上就栽过这一下 —— {@code TenantLicenseEntity} 加了 {@code modulePolicies}
     * （dw-org 的 V27），dw-model 的库停在 V14，于是 {@code AuthService} / {@code ProjectService}
     * / {@code AccessService} 里每一次 {@code selectById} 都 500：MyBatis-Plus 会把实体映射到的
     * <b>每一列</b>都写进 SELECT，而库里的表没有那一列。当时的两个守卫都拦不住它 ——
     * {@link #allTablesExistAfterMigration()} 只钉<b>表名</b>（表在就绿），
     * dw-org 那份 {@code EXPECTED_COLUMNS} 是<b>本模块</b>的清单，管不到这里。
     *
     * <p>所以这里不写死清单（清单会腐化，这次腐化的正是清单），改成从实体的映射元数据反推：
     * {@code com.dwai.platform.meta.entity} 包里每个 {@code @TableName} 类，它映射到的每一列
     * 都必须在本模块的库里存在。<b>表不在本模块的库里就跳过</b> —— 断言的是「库里有的表得
     * 装得下实体」，不是「每个共享实体都必须有表」（{@code tenant_compute} 只有 dw-org 用）。
     *
     * <p>那个包在 classpath 上是<b>跨 jar 合并</b>的：dw-common 和 dw-model 各自往里面放实体，
     * 于是这一条同时守住了两侧 —— 共享实体（漏了就是这次这种 500）与本模块自己的实体。
     */
    @Test
    void sharedEntityColumnsMatchTheDatabase() {
        Map<String, Set<String>> actual = allColumns();
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        List<String> missing = new ArrayList<>();
        List<String> checked = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.dwai.platform.meta.entity")) {
            Class<?> cls;
            try {
                cls = Class.forName(bd.getBeanClassName());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (!cls.isAnnotationPresent(TableName.class)) continue;

            TableInfo info = TableInfoHelper.getTableInfo(cls);
            if (info == null) {
                // 没被任何 mapper 引用过的实体不会注册，这里显式初始化一次，免得它悄悄漏检
                info = TableInfoHelper.initTableInfo(
                        new MapperBuilderAssistant(new MybatisConfiguration(), ""), cls);
            }

            Set<String> cols = actual.get(info.getTableName().toLowerCase(Locale.ROOT));
            if (cols == null) continue;

            List<String> mapped = new ArrayList<>();
            if (info.getKeyColumn() != null) mapped.add(plainColumn(info.getKeyColumn()));
            for (TableFieldInfo f : info.getFieldList()) {
                mapped.add(plainColumn(f.getColumn()));
            }
            checked.add(info.getTableName());
            for (String column : mapped) {
                if (!cols.contains(column)) missing.add(info.getTableName() + "." + column);
            }
        }

        assertFalse(checked.isEmpty(), "一个共享实体都没查上 —— 包扫描没生效，这条守卫等于没跑");
        assertEquals(List.of(), missing,
                "dw-common 的实体映射到了本模块库里没有的列 —— 补一条迁移（照 dw-model 的 V15 那种）");
    }

    /**
     * 实体的列名 → 库里的列名。
     *
     * <p>两边的写法可以不一样：{@code @TableField("`sensitive`")} 是**故意**带反引号的
     * （sensitive 是 MySQL 8 的保留字，MyBatis-Plus 会把反引号原样拼进 SQL），
     * 而库里那一列就叫 {@code sensitive}。比对前先剥掉转义符，否则这里会报一堆假阳性。
     */
    private static String plainColumn(String column) {
        return column.replace("`", "").replace("\"", "")
                .replace("[", "").replace("]", "").trim().toLowerCase(Locale.ROOT);
    }

    /** 库里实际有哪些表、每张表有哪些列（走 JDBC 元数据，与方言无关）。 */
    private Map<String, Set<String>> allColumns() {
        return new JdbcTemplate(dataSource).execute((org.springframework.jdbc.core.ConnectionCallback<
                        Map<String, Set<String>>>) (Connection conn) -> {
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
