package persistence;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 测试用的建库：执行的就是 Flyway 用的那套迁移脚本。
 *
 * <p>这一点是刻意的。表结构此前有两个来源（Flyway 迁移脚本 + 安装包里手工维护的
 * {@code 01_schema.sql}），两份手工 DDL 必然漂移，事实上也确实漂移了 ——
 * 安装包脚本少了 {@code meta_table} / {@code meta_column}，且血缘表停在旧结构。
 *
 * <p>现在只剩一份：{@code db/migration/<方言>/}（Flyway 权威来源）。
 * 测试跑的和 Flyway 启动时执行的是同一批文件，所以「脚本和实际表结构不一致」
 * 在结构上就不可能再发生 —— 脚本写错了，整个测试套件立刻全红。
 */
public final class TestSchema {

    /** 从 sql-tools 模块出发指向 Flyway 迁移脚本目录。 */
    private static final Path SQL_DIR = Path.of("src", "main", "resources", "db", "migration");

    private TestSchema() {
    }

    /** 建表 + 灌初始数据（默认租户/项目、内置元数据源、结构版本）。 */
    public static void apply(DataSource dataSource, String dialect) {
        run(dataSource, SQL_DIR.resolve(dialect).resolve("V1__schema.sql"));
        run(dataSource, SQL_DIR.resolve(dialect).resolve("V2__init_data.sql"));
    }

    /** H2 是默认后端，绝大多数用例用它。 */
    public static void apply(DataSource dataSource) {
        apply(dataSource, "h2");
    }

    /**
     * 先清空再重建，供跑真实 MySQL / PostgreSQL 的用例保证可重复执行。
     *
     * <p>此前这一步是靠 Flyway 的 {@code clean()}。Flyway 移除后自己删 ——
     * 顺序按外键/引用关系反向来，虽然本项目没建外键，但保持这个顺序
     * 将来加了约束也不用改。
     */
    public static void reset(DataSource dataSource, String dialect) {
        dropAll(dataSource);
        apply(dataSource, dialect);
    }

    /**
     * 表名硬编码而不是查 information_schema：只删我们自己的表，不误伤同库里别人的东西。
     *
     * <p><b>加了新表就要加到这里</b>。漏掉在 H2 上看不出来（每个用例一个新的内存库），
     * 只有跑真实 MySQL / PostgreSQL 时才会以「Table 'xxx' already exists」暴露出来 ——
     * {@code sync_job} 就这么漏了一整个版本。
     */
    private static final List<String> TABLES_IN_DROP_ORDER = List.of(
            "lineage_edge", "lineage_column", "lineage_version", "lineage_table",
            "meta_column", "meta_table",
            "temp_rule", "data_catalog", "sync_job",
            "metadata_source", "project", "tenant", "schema_version",
            // 旧版 Flyway 留下的历史表，存在就一并清掉
            "flyway_schema_history");

    private static void dropAll(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String table : TABLES_IN_DROP_ORDER) {
                st.execute("drop table if exists " + table);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清空测试库失败: " + e.getMessage(), e);
        }
    }

    /** 执行任意一个脚本文件，供升级脚本等场景直接调用。 */
    static void runScript(DataSource dataSource, Path script) {
        run(dataSource, script);
    }

    private static void run(DataSource dataSource, Path script) {
        String sql;
        try {
            sql = Files.readString(script, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读不到建表脚本: " + script.toAbsolutePath(), e);
        }
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            for (String statement : sql.split(";")) {
                String clean = stripLineComments(statement).trim();
                if (!clean.isEmpty()) {
                    st.execute(clean);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行建表脚本失败: " + script + " —— " + e.getMessage(), e);
        }
    }

    /**
     * 去掉 {@code --} 行注释。
     *
     * <p>脚本是按分号切分执行的，注释里的中文说明若原样留着，
     * 会跟在最后一条语句后面被一并送进 JDBC。
     */
    private static String stripLineComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            out.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return out.toString();
    }
}
