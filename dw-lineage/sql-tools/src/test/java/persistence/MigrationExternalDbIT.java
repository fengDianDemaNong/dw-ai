package persistence;

import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;

/**
 * MySQL / PostgreSQL 的建表脚本与递归 CTE 验证。
 *
 * <p>跑的是 {@code db/migration/<方言>/} 里那套脚本（Flyway 与打包派生共用的单一来源）——
 * 脚本在真实数据库上跑不通，这里就会红。
 *
 * <p>需要真实数据库，未通过系统属性提供连接信息时自动跳过：
 * <pre>
 * mvn test -Dtest=MigrationExternalDbIT \
 *   -Dit.mysql.url=jdbc:mysql://localhost:13306/dw_lineage \
 *   -Dit.mysql.username=root -Dit.mysql.password=root \
 *   -Dit.postgres.url=jdbc:postgresql://localhost:15432/dw_lineage \
 *   -Dit.postgres.username=postgres -Dit.postgres.password=postgres
 * </pre>
 */
public class MigrationExternalDbIT {

    @Test
    public void mysqlScriptCreatesAllTables() throws Exception {
        DataSource ds = TestDataSources.external("mysql", "com.mysql.cj.jdbc.Driver");
        assumeNotNull("未提供 MySQL 连接信息，跳过", ds);

        TestSchema.reset(ds, "mysql");

        Set<String> actual = MigrationDialectTest.listTables(ds);
        for (String expected : MigrationDialectTest.EXPECTED_TABLES) {
            assertTrue("MySQL 缺少表 " + expected + "，实际: " + actual, actual.contains(expected));
        }
    }

    /** MySQL 8.0+ 才支持递归 CTE，血缘图遍历依赖它。 */
    @Test
    public void mysqlSupportsRecursiveCte() throws Exception {
        DataSource ds = TestDataSources.external("mysql", "com.mysql.cj.jdbc.Driver");
        assumeNotNull("未提供 MySQL 连接信息，跳过", ds);

        TestSchema.reset(ds, "mysql");
        assertEquals("应遍历出 3 个节点", 3, recursiveHops(ds));
    }

    @Test
    public void postgresScriptCreatesAllTables() throws Exception {
        DataSource ds = TestDataSources.external("postgres", "org.postgresql.Driver");
        assumeNotNull("未提供 PostgreSQL 连接信息，跳过", ds);

        TestSchema.reset(ds, "postgresql");

        Set<String> actual = MigrationDialectTest.listTables(ds);
        for (String expected : MigrationDialectTest.EXPECTED_TABLES) {
            assertTrue("PostgreSQL 缺少表 " + expected + "，实际: " + actual, actual.contains(expected));
        }
    }

    @Test
    public void postgresSupportsRecursiveCte() throws Exception {
        DataSource ds = TestDataSources.external("postgres", "org.postgresql.Driver");
        assumeNotNull("未提供 PostgreSQL 连接信息，跳过", ds);

        TestSchema.reset(ds, "postgresql");
        assertEquals("应遍历出 3 个节点", 3, recursiveHops(ds));
    }


    /**
     * MySQL 上跑一遍 1.0.2 → 1.0.3 的升级，重点是 {@code full_name} 的改写。
     *
     * <p>H2 上验过一遍还不够：这一步改的是<b>唯一键</b>，而 H2 的唯一约束行为、
     * 字符串拼接语法都与 MySQL 不同 —— MySQL 里 {@code ||} 是逻辑或不是拼接，
     * 写错了不会报错，只会把所有 full_name 冲成同一个 0 或 1。
     */
    @Test
    public void mysqlUpgradeTo103RewritesFullNames() throws Exception {
        DataSource ds = TestDataSources.external("mysql", "com.mysql.cj.jdbc.Driver");
        assumeNotNull("未提供 MySQL 连接信息，跳过", ds);
        assertUpgradeRewritesFullNames(ds, "mysql");
    }

    @Test
    public void postgresUpgradeTo103RewritesFullNames() throws Exception {
        DataSource ds = TestDataSources.external("postgres", "org.postgresql.Driver");
        assumeNotNull("未提供 PostgreSQL 连接信息，跳过", ds);
        assertUpgradeRewritesFullNames(ds, "postgresql");
    }

    /** 建 1.0.3 的库 → 退回 1.0.2 的形态 → 灌存量 → 跑升级 → 核对。 */
    private static void assertUpgradeRewritesFullNames(DataSource ds, String dialect)
            throws Exception {
        TestSchema.reset(ds, dialect);

        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("delete from meta_column");
            c.createStatement().execute("delete from meta_table");
            c.createStatement().execute("drop table temp_rule");
            c.createStatement().execute("drop table data_catalog");
            // 1.0.4 给 catalog_name 加了 NOT NULL，退回旧形态要把它放开，
            // 否则下面根本造不出「无目录的存量表」
            relaxCatalogNotNull(c, dialect);
            // 版本号也要退回去，否则升级脚本末尾写 1.0.3 时会撞主键
            c.createStatement().execute("delete from schema_version");
            c.createStatement().execute(
                    "insert into schema_version(version, description) values('1.0.2','旧版本')");

            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,schema_name,table_name,"
                            + "full_name,source) values(1,1,1,'ods','users','ods.users','DDL')");
            c.createStatement().execute(
                    "insert into meta_column(id,tenant_id,project_id,table_id,column_name,"
                            + "full_name,source) values(1,1,1,1,'id','ods.users.id','DDL')");
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name,source) values(2,1,1,'hive_prod','ods',"
                            + "'orders','hive_prod.ods.orders','DDL')");
        }

        TestSchema.runScript(ds, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", dialect, "1.0.2_to_1.0.3.sql"));

        try (Connection c = ds.getConnection()) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select catalog_name, full_name from meta_table where id = 1")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 无目录的表要归到默认目录", "default", rs.getString(1));
                assertEquals(dialect + ": full_name 要补上目录段，不能被拼成 0 或 1",
                        "default.ods.users", rs.getString(2));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select full_name from meta_column where id = 1")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 字段全名也要跟着改",
                        "default.ods.users.id", rs.getString(1));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select full_name from meta_table where id = 2")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 已有目录的表不该被动",
                        "hive_prod.ods.orders", rs.getString(1));
            }
            // 存量里已在用的目录要被登记进配置表，否则配置页上看不见它
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select count(*) from data_catalog where name = 'hive_prod'")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 存量目录要登记", 1, rs.getInt(1));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select count(*) from data_catalog where is_default = 1")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 默认目录必须恰好一条", 1, rs.getInt(1));
            }
            // 判「存在」而不是「最新」：MySQL 的 applied_at 只到秒，
            // 退回旧版本与写入新版本常在同一秒，按时间排序的结果不确定。
            // 与 Flyway baseline 的判断口径一致：也是包含关系
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select count(*) from schema_version where version = '1.0.3'")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 脚本末尾要写入新版本号 1.0.3", 1, rs.getInt(1));
            }
        }
    }

    /**
     * MySQL / PostgreSQL 上跑 1.0.3 → 1.0.4：血缘侧存量归位 + 加 NOT NULL。
     *
     * <p>这一版有两处必须在真实库上验：
     *
     * <ul>
     *   <li>拼接：MySQL 用 {@code CONCAT}，另外两家用 {@code ||}。
     *       在 MySQL 上写 {@code ||} 不报错，只会把 full_name 全冲成 0 或 1</li>
     *   <li>改约束：MySQL 是 {@code MODIFY 列 类型 NOT NULL}（要重写整个列定义），
     *       另外两家是 {@code ALTER COLUMN ... SET NOT NULL}</li>
     * </ul>
     */
    @Test
    public void mysqlUpgradeTo104() throws Exception {
        DataSource ds = TestDataSources.external("mysql", "com.mysql.cj.jdbc.Driver");
        assumeNotNull("未提供 MySQL 连接信息，跳过", ds);
        assertUpgradeTo104(ds, "mysql");
    }

    @Test
    public void postgresUpgradeTo104() throws Exception {
        DataSource ds = TestDataSources.external("postgres", "org.postgresql.Driver");
        assumeNotNull("未提供 PostgreSQL 连接信息，跳过", ds);
        assertUpgradeTo104(ds, "postgresql");
    }

    /** 建 1.0.4 的库 → 退回 1.0.3 的形态 → 灌无目录存量 → 跑升级 → 核对。 */
    private static void assertUpgradeTo104(DataSource ds, String dialect) throws Exception {
        TestSchema.reset(ds, dialect);

        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("delete from lineage_column");
            c.createStatement().execute("delete from lineage_table");
            c.createStatement().execute("delete from meta_column");
            c.createStatement().execute("delete from meta_table");
            relaxCatalogNotNull(c, dialect);
            c.createStatement().execute("delete from schema_version");
            c.createStatement().execute(
                    "insert into schema_version(version, description) values('1.0.3','旧版本')");

            // 默认目录不叫 default：脚本里若硬编码前缀，这里就会露馅
            c.createStatement().execute("delete from data_catalog");
            c.createStatement().execute(
                    "insert into data_catalog(tenant_id,project_id,name,is_default) "
                            + "values(1,1,'prod',1)");

            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,schema_name,table_name,"
                            + "full_name) values(1,1,1,'ods','orders','ods.orders')");
            c.createStatement().execute(
                    "insert into lineage_column(id,tenant_id,project_id,table_id,column_name,"
                            + "full_name) values(1,1,1,1,'id','ods.orders.id')");
        }

        TestSchema.runScript(ds, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", dialect, "1.0.3_to_1.0.4.sql"));

        try (Connection c = ds.getConnection()) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select catalog_name, full_name from lineage_table where id = 1")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 要用项目实际的默认目录名", "prod", rs.getString(1));
                assertEquals(dialect + ": full_name 要补上目录段，不能被拼成 0 或 1",
                        "prod.ods.orders", rs.getString(2));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select full_name from lineage_column where id = 1")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 字段全名也要跟着改",
                        "prod.ods.orders.id", rs.getString(1));
            }
            // 判「存在」而不是「最新」：MySQL 的 applied_at 只到秒，
            // 退回旧版本与写入新版本常在同一秒，按时间排序的结果不确定。
            // 与 Flyway baseline 的判断口径一致：也是包含关系
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select count(*) from schema_version where version = '1.0.4'")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 脚本末尾要写入新版本号 1.0.4", 1, rs.getInt(1));
            }
        }

        // NOT NULL 真的加上了：方言写错（比如 MySQL 漏了 MODIFY 的类型）在这里暴露
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(
                    "insert into lineage_table(tenant_id,project_id,schema_name,table_name,"
                            + "full_name) values(1,1,'ods','late','ods.late')");
            throw new AssertionError(dialect + ": catalog_name 仍可写空，NOT NULL 没加上");
        } catch (java.sql.SQLException expected) {
            // 约束生效
        }
    }

    /**
     * 把 catalog_name 改回可空，用于退回 1.0.4 之前的形态。
     *
     * <p>MySQL 的 {@code MODIFY} 会重写整个列定义，类型必须原样写全；
     * 另外两家用 {@code ALTER COLUMN ... DROP NOT NULL}。
     */
    private static void relaxCatalogNotNull(Connection c, String dialect) throws Exception {
        boolean mysql = "mysql".equals(dialect);
        for (String table : new String[]{"meta_table", "lineage_table"}) {
            c.createStatement().execute(mysql
                    ? "alter table " + table + " modify catalog_name varchar(128) null"
                    : "alter table " + table + " alter column catalog_name drop not null");
        }
    }

    /**
     * MySQL / PostgreSQL 上跑 1.0.4 → 1.0.5：加描述列 + 从元数据回填存量。
     *
     * <p>这一版必须在真实库上验的点：
     *
     * <ul>
     *   <li>{@code comment} 是 MySQL 的关键字，加列和 UPDATE 都要反引号，
     *       另外两家不用</li>
     *   <li>回填用的相关子查询带 {@code HAVING COUNT(*) = 1}（多目录同名时不猜），
     *       三家对「聚合子查询无 GROUP BY」的处理要一致</li>
     *   <li>{@code UPDATE 表 别名 SET ...} 的别名语法三家都支持，但得验</li>
     * </ul>
     */
    @Test
    public void mysqlUpgradeTo105() throws Exception {
        DataSource ds = TestDataSources.external("mysql", "com.mysql.cj.jdbc.Driver");
        assumeNotNull("未提供 MySQL 连接信息，跳过", ds);
        assertUpgradeTo105(ds, "mysql");
    }

    @Test
    public void postgresUpgradeTo105() throws Exception {
        DataSource ds = TestDataSources.external("postgres", "org.postgresql.Driver");
        assumeNotNull("未提供 PostgreSQL 连接信息，跳过", ds);
        assertUpgradeTo105(ds, "postgresql");
    }

    /** 建 1.0.5 的库 → 退回 1.0.4 的形态 → 灌跨 catalog 的存量 → 跑升级 → 核对回填。 */
    private static void assertUpgradeTo105(DataSource ds, String dialect) throws Exception {
        TestSchema.reset(ds, dialect);
        String cmt = "mysql".equals(dialect) ? "`comment`" : "comment";

        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("delete from lineage_column");
            c.createStatement().execute("delete from lineage_table");
            c.createStatement().execute("delete from meta_column");
            c.createStatement().execute("delete from meta_table");
            for (String col : new String[]{"table_type", cmt, "remark"}) {
                c.createStatement().execute("alter table lineage_table drop column " + col);
            }
            for (String col : new String[]{"data_type", cmt, "remark"}) {
                c.createStatement().execute("alter table lineage_column drop column " + col);
            }
            c.createStatement().execute("delete from schema_version");
            c.createStatement().execute(
                    "insert into schema_version(version, description) values('1.0.4','旧版本')");

            // 元数据在 hive_prod，血缘在 default —— 首段不同，旧的 full_name 关联匹配不上
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name,table_type," + cmt + ",source) "
                            + "values(1,1,1,'hive_prod','ods','orders','hive_prod.ods.orders',"
                            + "'FULL','订单主表','DDL')");
            c.createStatement().execute(
                    "insert into meta_column(id,tenant_id,project_id,table_id,column_name,"
                            + "full_name,data_type," + cmt + ",source) "
                            + "values(1,1,1,1,'id','hive_prod.ods.orders.id','bigint','订单号','DDL')");
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name) values(1,1,1,'default','ods','orders',"
                            + "'default.ods.orders')");
            c.createStatement().execute(
                    "insert into lineage_column(id,tenant_id,project_id,table_id,column_name,"
                            + "full_name) values(1,1,1,1,'id','default.ods.orders.id')");
        }

        TestSchema.runScript(ds, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", dialect, "1.0.4_to_1.0.5.sql"));

        try (Connection c = ds.getConnection()) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select " + cmt + ", table_type from lineage_table where id = 1")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 跨 catalog 的存量描述要回填上", "订单主表", rs.getString(1));
                assertEquals(dialect + ": 表类型也要回填", "FULL", rs.getString(2));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select data_type, " + cmt + " from lineage_column where id = 1")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 字段类型要回填", "bigint", rs.getString(1));
                assertEquals(dialect + ": 字段中文名要回填", "订单号", rs.getString(2));
            }
            // 判「存在」而不是「最新」：MySQL 的 applied_at 只到秒，
            // 退回旧版本与写入新版本常在同一秒，按时间排序的结果不确定。
            // 与 Flyway baseline 的判断口径一致：也是包含关系
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select count(*) from schema_version where version = '1.0.5'")) {
                assertTrue(rs.next());
                assertEquals(dialect + ": 脚本末尾要写入新版本号 1.0.5", 1, rs.getInt(1));
            }
        }
    }

    // ------------------------------------------------------------------

    /** 插入一条 3 跳链路并用递归 CTE 遍历，返回遍历到的节点数。 */
    private static int recursiveHops(DataSource ds) throws Exception {
        try (Connection c = ds.getConnection()) {
            // V3 起：表/字段是累积目录（不带 version_id），版本挂在目标表上
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name) values(1,1,1,'cat','ods','t','cat.ods.t')");
            c.createStatement().execute(
                    "insert into lineage_version(id,tenant_id,project_id,target_table_id,version_no,"
                            + "db_type,sql_hash,is_current) values(1,1,1,1,1,'hive','h',1)");
            for (int i = 1; i <= 3; i++) {
                c.createStatement().execute(
                        "insert into lineage_column(id,tenant_id,project_id,table_id,column_name,full_name) "
                                + "values(" + i + ",1,1,1,'c" + i + "','cat.ods.t.c" + i + "')");
            }
            c.createStatement().execute(
                    "insert into lineage_edge(tenant_id,project_id,version_id,target_col_id,source_col_id) "
                            + "values(1,1,1,1,2)");
            c.createStatement().execute(
                    "insert into lineage_edge(tenant_id,project_id,version_id,target_col_id,source_col_id) "
                            + "values(1,1,1,2,3)");

            // 遍历沿「各表的 current 版本」走，因此要 join lineage_version
            String sql = """
                    WITH RECURSIVE up(col_id, depth) AS (
                        SELECT c.id, 0 FROM lineage_column c
                         WHERE c.tenant_id = 1 AND c.project_id = 1
                           AND c.full_name = 'cat.ods.t.c1'
                        UNION ALL
                        SELECT e.source_col_id, up.depth + 1
                          FROM lineage_edge e
                          JOIN lineage_version v ON v.id = e.version_id AND v.is_current = 1
                          JOIN up ON e.target_col_id = up.col_id
                         WHERE e.tenant_id = 1 AND e.project_id = 1 AND up.depth < 10
                    )
                    SELECT count(*) FROM up
                    """;
            try (ResultSet rs = c.createStatement().executeQuery(sql)) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
