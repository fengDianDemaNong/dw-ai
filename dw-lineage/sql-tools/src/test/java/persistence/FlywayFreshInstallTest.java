package persistence;

import org.junit.jupiter.api.Test;
import com.dwai.lineage.Main;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 空库冷启动：Flyway 必须按 database.type 自动选目录、把 V1/V2 跑完。
 *
 * <p>其它 API 测试都是先 {@code TestSchema} 建好表再起上下文，
 * 走的是「已有库 → baseline」分支；空库冷启动这条路径此前没有用例钉住 ——
 * 它依赖 EnvironmentPostProcessor 注册成功、方言目录切换正确、
 * 迁移脚本能被 Flyway 正确切分执行，任何一环断了测试都发现不了。
 *
 * <p>建库用的随机内存库，且与 {@link TestSchema} 完全无关：
 * schema 必须是 Flyway 建出来的，不是测试自己种的。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.username=sa",
        "database.password=",
        "spring.jpa.hibernate.ddl-auto=none"
})
class FlywayFreshInstallTest {

    @DynamicPropertySource
    static void freshDb(DynamicPropertyRegistry registry) {
        // 随机命名的全新内存库：保证上下文起来之前库是空的，schema 只能来自 Flyway
        registry.add("database.url",
                () -> "jdbc:h2:mem:flyway_fresh_" + UUID.randomUUID());
    }

    @Autowired
    private DataSource dataSource;

    @Test
    void flywayCreatedSchemaAndSeedDataFromScratch() {
        JdbcTemplate db = new JdbcTemplate(dataSource);

        // Flyway 10 用带引号的小写标识建历史表，表名和列名都要加引号
        List<String> history = db.query(
                "select \"version\" from \"flyway_schema_history\" order by \"installed_rank\"",
                (rs, i) -> rs.getString(1));
        assertTrue(history.containsAll(List.of("1", "2")),
                "V1__schema 与 V2__init_data 都应被执行，实际: " + history);

        // V1 建的业务表
        Integer tenants = db.queryForObject("select count(*) from tenant", Integer.class);
        // V2 种的默认租户/项目与内置元数据源
        Integer defaultCatalog = db.queryForObject(
                "select count(*) from data_catalog where is_default = 1", Integer.class);
        Integer catalogSources = db.queryForObject(
                "select count(*) from metadata_source where type = 'CATALOG'", Integer.class);

        assertEquals(1, tenants, "V2 应种下默认租户");
        assertEquals(1, defaultCatalog, "默认目录有且只有一个");
        assertEquals(1, catalogSources, "内置「本地元数据目录」元数据源应被种下");
    }
}
