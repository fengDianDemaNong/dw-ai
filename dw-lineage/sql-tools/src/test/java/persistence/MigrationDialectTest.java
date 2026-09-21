package persistence;

import org.junit.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 建表脚本方言验证：三套 DDL 必须都能真实执行，并产出一致的表结构。
 *
 * <p>脚本即 {@code db/migration/<方言>/}——Flyway 启动时执行的、测试建库用的、
 * 打包派生到安装包的，都是同一批文件。
 *
 * <p>本用例只覆盖 H2（无需外部依赖，可在 CI 中稳定运行）。
 * MySQL / PostgreSQL 由 {@code MigrationExternalDbIT} 用真实数据库验证。
 */
public class MigrationDialectTest {

    /** 执行建表脚本后应存在的全部业务表。 */
    static final Set<String> EXPECTED_TABLES = new LinkedHashSet<>(java.util.List.of(
            "TENANT", "PROJECT", "LINEAGE_VERSION",
            "LINEAGE_TABLE", "LINEAGE_COLUMN", "LINEAGE_EDGE", "METADATA_SOURCE",
            // V3：元数据目录，与血缘表隔离存储
            "META_TABLE", "META_COLUMN", "DATA_CATALOG", "TEMP_RULE",
            // 结构版本记录，供启动校验
            "SCHEMA_VERSION"));

    @Test
    public void h2ScriptCreatesAllTables() throws Exception {
        DataSource ds = TestDataSources.h2("migration_h2");
        TestSchema.apply(ds, "h2");

        Set<String> actual = listTables(ds);
        for (String expected : EXPECTED_TABLES) {
            assertTrue("缺少表 " + expected + "，实际: " + actual, actual.contains(expected));
        }
    }

    /**
     * 建表脚本<b>不是</b>幂等的，重复执行必须报错。
     *
     * <p>这是刻意保留的行为，不是缺陷。以前由 Flyway 管理时重复执行是安全的（它记账），
     * 现在改成使用者手工执行，"重复跑一遍看看"就成了真实的误操作场景 ——
     * 此时报「表已存在」远好过默默 {@code CREATE TABLE IF NOT EXISTS} 跳过：
     * 后者会让人以为脚本生效了，而实际上库还停在旧结构上。
     * 升级请走 {@code sql/upgrade/}。
     */
    @Test
    public void applyingSchemaTwiceFails() throws Exception {
        DataSource ds = TestDataSources.h2("script_not_idempotent");
        TestSchema.apply(ds, "h2");
        try {
            TestSchema.apply(ds, "h2");
            throw new AssertionError("重复执行建表脚本竟然成功了，说明用了 IF NOT EXISTS，"
                    + "会掩盖「库其实没升级」的问题");
        } catch (IllegalStateException expected) {
            assertTrue("报错应指明是表已存在，实际: " + expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("already exists")
                            || expected.getMessage().contains("已存在"));
        }
    }

    /**
     * 升级脚本必须把旧库改成与新版建表脚本完全一致的结构。
     *
     * <p>这是这个项目吃过亏的地方：表结构一旦有两条产生路径（全新安装走
     * {@code V1__schema.sql}、升级走 {@code upgrade/}），两边就会漂移，
     * 而漂移在功能测试里看不出来 —— 全新装的环境一切正常，只有升上来的环境出问题。
     *
     * <p>做法：一条路径建 1.0.0 的旧结构再执行升级脚本，另一条路径直接建新结构，
     * 逐表逐列比对。
     */
    @Test
    public void upgradeScriptProducesSameSchemaAsFreshInstall() throws Exception {
        DataSource upgraded = TestDataSources.h2("upgrade_path");
        applyLegacy100(upgraded);
        TestSchema.runScript(upgraded,
                java.nio.file.Path.of("..", "release", "sql", "upgrade", "h2", "1.0.0_to_1.0.1.sql"));

        DataSource fresh = TestDataSources.h2("fresh_install");
        TestSchema.apply(fresh, "h2");

        assertEquals("升级得到的结构与全新安装不一致",
                describe(fresh), describe(upgraded));
    }

    /**
     * 1.0.1 → 1.0.2：元数据服务改租户级 + 新增导入任务表。
     *
     * <p>除了比结构，还要验证<b>去重真的发生了</b> —— 删掉 project_id 之后，
     * 原本分属不同项目的同名配置会变成重复行，不先去重就建不上唯一键。
     */
    @Test
    public void upgradeTo102DeduplicatesSourcesAndMatchesFreshInstall() throws Exception {
        DataSource upgraded = TestDataSources.h2("upgrade_102");
        applyLegacy101(upgraded);

        // 造出「两个项目各有一条同名配置」的历史数据
        try (Connection c = upgraded.getConnection()) {
            c.createStatement().execute(
                    "insert into metadata_source(tenant_id, project_id, name, type, base_url, priority, enabled) "
                            + "values(1, 1, '本地元数据目录', 'CATALOG', 'local://catalog', 50, 1)");
            c.createStatement().execute(
                    "insert into metadata_source(tenant_id, project_id, name, type, base_url, priority, enabled) "
                            + "values(1, 2, '本地元数据目录', 'CATALOG', 'local://catalog', 50, 1)");
        }

        TestSchema.runScript(upgraded, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", "h2", "1.0.1_to_1.0.2.sql"));

        try (Connection c = upgraded.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "select count(*) from metadata_source where name = '本地元数据目录'")) {
            assertTrue(rs.next());
            assertEquals("同名配置应当去重成一条", 1, rs.getInt(1));
        }

        DataSource fresh = TestDataSources.h2("fresh_102");
        TestSchema.apply(fresh, "h2");
        assertEquals("升级得到的结构与全新安装不一致", describe(fresh), describe(upgraded));
    }

    /** 用新脚本建库后回退成 1.0.1 的结构：metadata_source 带 project_id、没有 sync_job。 */
    private static void applyLegacy101(DataSource ds) throws Exception {
        TestSchema.apply(ds, "h2");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("delete from metadata_source");
            c.createStatement().execute("drop table sync_job");
            c.createStatement().execute("alter table metadata_source drop constraint uk_source");
            c.createStatement().execute("drop index if exists idx_source_enabled");
            c.createStatement().execute(
                    "alter table metadata_source add column project_id bigint not null default 1");
            c.createStatement().execute(
                    "alter table metadata_source add constraint uk_source unique (tenant_id, project_id, name)");
            c.createStatement().execute(
                    "create index idx_source_enabled on metadata_source (tenant_id, project_id, enabled, priority)");
            c.createStatement().execute("delete from schema_version");
            c.createStatement().execute(
                    "insert into schema_version(version, description) values('1.0.1','旧版本')");
        }
    }

    /** 用新脚本建库后把 full_name 改回 700，模拟 1.0.0 时期的旧结构。 */
    private static void applyLegacy100(DataSource ds) throws Exception {
        TestSchema.apply(ds, "h2");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(
                    "alter table meta_column alter column full_name varchar(700) not null");
            c.createStatement().execute(
                    "alter table lineage_column alter column full_name varchar(700) not null");
            c.createStatement().execute("delete from schema_version");
            c.createStatement().execute(
                    "insert into schema_version(version, description) values('1.0.0','旧版本')");
        }
    }

    /** 表名 → 「列名:类型:长度」集合，比对结构用。只比结构，不比数据。 */
    private static java.util.Map<String, java.util.Set<String>> describe(DataSource ds)
            throws Exception {
        java.util.Map<String, java.util.Set<String>> out = new java.util.TreeMap<>();
        try (Connection c = ds.getConnection()) {
            java.sql.DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getTables(null, "PUBLIC", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    out.put(rs.getString("TABLE_NAME").toUpperCase(java.util.Locale.ROOT),
                            new java.util.TreeSet<>());
                }
            }
            for (String table : out.keySet()) {
                try (ResultSet rs = meta.getColumns(null, "PUBLIC", table, "%")) {
                    while (rs.next()) {
                        out.get(table).add(rs.getString("COLUMN_NAME").toUpperCase(java.util.Locale.ROOT)
                                + ":" + rs.getString("TYPE_NAME")
                                + ":" + rs.getInt("COLUMN_SIZE")
                                + ":" + rs.getInt("NULLABLE"));
                    }
                }
            }
        }
        return out;
    }


    /**
     * 1.0.2 → 1.0.3：新增数据目录与临时规则，并把无 catalog 的存量归到默认目录。
     *
     * <p>除了比结构，重点验<b>存量改写</b>：full_name 从两段变三段，
     * 而它是唯一键 —— 改错要么撞键，要么把两张表冲成一张。
     */
    @Test
    public void upgradeTo103MovesOrphanTablesIntoDefaultCatalog() throws Exception {
        DataSource ds = TestDataSources.h2("upgrade_103");
        applyLegacy102(ds);

        try (Connection c = ds.getConnection()) {
            // 一张无目录的表 + 一张有目录的表，各带一个字段
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,schema_name,table_name,full_name,source) "
                            + "values(1,1,1,'ods','users','ods.users','DDL')");
            c.createStatement().execute(
                    "insert into meta_column(id,tenant_id,project_id,table_id,column_name,full_name,source) "
                            + "values(1,1,1,1,'id','ods.users.id','DDL')");
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,table_name,"
                            + "full_name,source) values(2,1,1,'hive_prod','ods','orders',"
                            + "'hive_prod.ods.orders','DDL')");
        }

        TestSchema.runScript(ds, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", "h2", "1.0.2_to_1.0.3.sql"));

        try (Connection c = ds.getConnection()) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select catalog_name, full_name from meta_table where id = 1")) {
                assertTrue(rs.next());
                assertEquals("default", rs.getString(1));
                assertEquals("无目录的表要归到默认目录并补全 full_name",
                        "default.ods.users", rs.getString(2));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select full_name from meta_column where id = 1")) {
                assertTrue(rs.next());
                assertEquals("字段全名也要跟着改", "default.ods.users.id", rs.getString(1));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select full_name from meta_table where id = 2")) {
                assertTrue(rs.next());
                assertEquals("已有目录的表不该被动", "hive_prod.ods.orders", rs.getString(1));
            }
            // 已在用的目录也要登记进配置表，否则页面上看不到
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select count(*) from data_catalog where name = 'hive_prod'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select count(*) from data_catalog where is_default = 1")) {
                assertTrue(rs.next());
                assertEquals("每个项目有且仅有一个默认目录", 1, rs.getInt(1));
            }
        }
    }

    /**
     * 存量里同时有 ods.users 和 default.ods.users 时，升级必须<b>报错中止</b>。
     *
     * <p>不检测的话，UPDATE 要么撞唯一键报一句看不懂的约束错误，
     * 要么在没有约束的库上默默把两张表冲成一张 —— 后者是静默的数据丢失。
     */
    @Test
    public void upgradeTo103AbortsOnFullNameConflict() throws Exception {
        DataSource ds = TestDataSources.h2("upgrade_103_conflict");
        applyLegacy102(ds);

        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,schema_name,table_name,full_name,source) "
                            + "values(1,1,1,'ods','users','ods.users','DDL')");
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,table_name,"
                            + "full_name,source) values(2,1,1,'default','ods','users',"
                            + "'default.ods.users','DDL')");
        }

        try {
            TestSchema.runScript(ds, java.nio.file.Path.of(
                    "..", "release", "sql", "upgrade", "h2", "1.0.2_to_1.0.3.sql"));
            throw new AssertionError("有冲突却升级成功了 —— 存量数据可能已被静默覆盖");
        } catch (IllegalStateException expected) {
            // 冲突检测触发，脚本中止
        }
    }

    /**
     * 1.0.3 → 1.0.4：把血缘侧遗留的无目录表归位，并给 catalog_name 加上 NOT NULL。
     *
     * <p>1.0.3 已经清过一次存量，但产生它的代码路径当时还开着 —— 血缘侧的表名来自
     * SQL 解析，用户写 {@code from ods.orders} 就会再写出一批两段名。所以这一版
     * 既要再清一次，也要用约束把口子焊死。
     *
     * <p>关键差异：归位用的是<b>项目实际的</b>默认目录名，而不是 1.0.3 里硬编码的
     * {@code 'default'}。这里把默认目录改名成 {@code prod} 来钉住这一点。
     */
    @Test
    public void upgradeTo104MovesLineageOrphansIntoActualDefaultCatalog() throws Exception {
        DataSource ds = TestDataSources.h2("upgrade_104");
        applyLegacy103(ds);

        try (Connection c = ds.getConnection()) {
            // 默认目录不叫 default：硬编码前缀的写法会在这里露馅
            c.createStatement().execute("delete from data_catalog");
            c.createStatement().execute(
                    "insert into data_catalog(tenant_id,project_id,name,is_default) "
                            + "values(1,1,'prod',1)");
            // 血缘侧的两段名存量（SQL 里没写目录）
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,schema_name,table_name,full_name) "
                            + "values(1,1,1,'ods','orders','ods.orders')");
            c.createStatement().execute(
                    "insert into lineage_column(id,tenant_id,project_id,table_id,column_name,full_name) "
                            + "values(1,1,1,1,'id','ods.orders.id')");
            // 已带目录的不该被动
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name) values(2,1,1,'hive_test','ods','orders',"
                            + "'hive_test.ods.orders')");
        }

        TestSchema.runScript(ds, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", "h2", "1.0.3_to_1.0.4.sql"));

        try (Connection c = ds.getConnection()) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select catalog_name, full_name from lineage_table where id = 1")) {
                assertTrue(rs.next());
                assertEquals("要用项目实际的默认目录名，不是写死的 default", "prod", rs.getString(1));
                assertEquals("prod.ods.orders", rs.getString(2));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select full_name from lineage_column where id = 1")) {
                assertTrue(rs.next());
                assertEquals("字段全名也要跟着改", "prod.ods.orders.id", rs.getString(1));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select full_name from lineage_table where id = 2")) {
                assertTrue(rs.next());
                assertEquals("已有目录的表不该被动", "hive_test.ods.orders", rs.getString(1));
            }
        }

        // 约束真的加上了：再插一条无目录的必须失败
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(
                    "insert into lineage_table(tenant_id,project_id,schema_name,table_name,full_name) "
                            + "values(1,1,'ods','late','ods.late')");
            throw new AssertionError("catalog_name 仍然可以写空，NOT NULL 没加上");
        } catch (java.sql.SQLException expected) {
            // 约束生效
        }
    }

    /** 升级到 1.0.4 后的结构必须与全新安装完全一致，否则两条路径又要开始漂移。 */
    @Test
    public void upgradeTo104MatchesFreshInstall() throws Exception {
        DataSource upgraded = TestDataSources.h2("upgrade_104_shape");
        applyLegacy103(upgraded);
        TestSchema.runScript(upgraded, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", "h2", "1.0.3_to_1.0.4.sql"));

        DataSource fresh = TestDataSources.h2("fresh_104");
        TestSchema.apply(fresh, "h2");

        assertEquals("升级得到的结构与全新安装不一致", describe(fresh), describe(upgraded));
    }

    /**
     * 存量里同时有 ods.orders 和 prod.ods.orders 时，1.0.4 也必须报错中止。
     *
     * <p>与 1.0.3 同样的道理：改写 full_name 会撞唯一键，
     * 而在没有约束的库上则是静默把两张表冲成一张。
     */
    @Test
    public void upgradeTo104AbortsOnFullNameConflict() throws Exception {
        DataSource ds = TestDataSources.h2("upgrade_104_conflict");
        applyLegacy103(ds);

        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("delete from data_catalog");
            c.createStatement().execute(
                    "insert into data_catalog(tenant_id,project_id,name,is_default) "
                            + "values(1,1,'prod',1)");
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,schema_name,table_name,full_name) "
                            + "values(1,1,1,'ods','orders','ods.orders')");
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name) values(2,1,1,'prod','ods','orders',"
                            + "'prod.ods.orders')");
        }

        try {
            TestSchema.runScript(ds, java.nio.file.Path.of(
                    "..", "release", "sql", "upgrade", "h2", "1.0.3_to_1.0.4.sql"));
            throw new AssertionError("有冲突却升级成功了 —— 存量数据可能已被静默覆盖");
        } catch (IllegalStateException expected) {
            // 冲突检测触发，脚本中止
        }
    }

    /** 用新脚本建库后回退成 1.0.2 的结构：没有 data_catalog / temp_rule，catalog_name 可空。 */
    private static void applyLegacy102(DataSource ds) throws Exception {
        TestSchema.apply(ds, "h2");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("delete from meta_column");
            c.createStatement().execute("delete from meta_table");
            c.createStatement().execute("drop table temp_rule");
            c.createStatement().execute("drop table data_catalog");
            relaxCatalogNotNull(c);
            c.createStatement().execute("delete from schema_version");
            c.createStatement().execute(
                    "insert into schema_version(version, description) values('1.0.2','旧版本')");
        }
    }

    /**
     * 1.0.4 → 1.0.5：血缘侧加描述列，并从元数据目录回填存量。
     *
     * <p>回填按「库.表」<b>忽略数据目录</b>匹配。这与运行时不追平 catalog 的原则不冲突：
     * 那是刻意的设计，而这是一次性数据订正 —— 跨 catalog 的历史血缘正是最需要补的那批，
     * 它们恰恰因为目录段对不上才一直没有描述。
     */
    @Test
    public void upgradeTo105BackfillsDescriptionsAcrossCatalogs() throws Exception {
        DataSource ds = TestDataSources.h2("upgrade_105");
        applyLegacy104(ds);

        try (Connection c = ds.getConnection()) {
            // 元数据挂在 hive_prod 下
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name,table_type,comment,remark,source) "
                            + "values(1,1,1,'hive_prod','ods','orders','hive_prod.ods.orders',"
                            + "'FULL','订单主表','订单备注','DDL')");
            c.createStatement().execute(
                    "insert into meta_column(id,tenant_id,project_id,table_id,column_name,"
                            + "full_name,data_type,comment,source) "
                            + "values(1,1,1,1,'id','hive_prod.ods.orders.id','bigint','订单号','DDL')");

            // 血缘侧落在默认目录下 —— 首段不同，1.0.5 之前靠 full_name 关联永远匹配不上
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name) values(1,1,1,'default','ods','orders',"
                            + "'default.ods.orders')");
            c.createStatement().execute(
                    "insert into lineage_column(id,tenant_id,project_id,table_id,column_name,"
                            + "full_name) values(1,1,1,1,'id','default.ods.orders.id')");
        }

        TestSchema.runScript(ds, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", "h2", "1.0.4_to_1.0.5.sql"));

        try (Connection c = ds.getConnection()) {
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select comment, table_type, remark from lineage_table where id = 1")) {
                assertTrue(rs.next());
                assertEquals("跨 catalog 的存量正是最该补上的那批", "订单主表", rs.getString(1));
                assertEquals("FULL", rs.getString(2));
                assertEquals("订单备注", rs.getString(3));
            }
            try (ResultSet rs = c.createStatement().executeQuery(
                    "select data_type, comment from lineage_column where id = 1")) {
                assertTrue(rs.next());
                assertEquals("bigint", rs.getString(1));
                assertEquals("订单号", rs.getString(2));
            }
        }
    }

    /**
     * 多个数据目录下有同名表时<b>不猜</b>，留空。
     *
     * <p>随便挑一个会把别的库的中文名安到这张表上，而这种错误在页面上看不出来。
     * 规则与 {@code CatalogMetadataProvider.pick()} 一致。
     */
    @Test
    public void upgradeTo105DoesNotGuessWhenSeveralCatalogsHaveTheSameTable() throws Exception {
        DataSource ds = TestDataSources.h2("upgrade_105_ambiguous");
        applyLegacy104(ds);

        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name,comment,source) values(1,1,1,'hive_prod','ods',"
                            + "'orders','hive_prod.ods.orders','生产订单','DDL')");
            c.createStatement().execute(
                    "insert into meta_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name,comment,source) values(2,1,1,'hive_test','ods',"
                            + "'orders','hive_test.ods.orders','测试订单','DDL')");
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name) values(1,1,1,'default','ods','orders',"
                            + "'default.ods.orders')");
        }

        TestSchema.runScript(ds, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", "h2", "1.0.4_to_1.0.5.sql"));

        try (Connection c = ds.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "select comment from lineage_table where id = 1")) {
            assertTrue(rs.next());
            assertEquals("两个目录下都有同名表，宁可留空也不能赌是哪一张", null, rs.getString(1));
        }
    }

    /** 升级到 1.0.5 后的结构必须与全新安装完全一致。 */
    @Test
    public void upgradeTo105MatchesFreshInstall() throws Exception {
        DataSource upgraded = TestDataSources.h2("upgrade_105_shape");
        applyLegacy104(upgraded);
        TestSchema.runScript(upgraded, java.nio.file.Path.of(
                "..", "release", "sql", "upgrade", "h2", "1.0.4_to_1.0.5.sql"));

        DataSource fresh = TestDataSources.h2("fresh_105");
        TestSchema.apply(fresh, "h2");

        assertEquals("升级得到的结构与全新安装不一致", describe(fresh), describe(upgraded));
    }

    /** 用新脚本建库后回退成 1.0.4 的结构：血缘侧还没有描述列。 */
    private static void applyLegacy104(DataSource ds) throws Exception {
        TestSchema.apply(ds, "h2");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("delete from lineage_column");
            c.createStatement().execute("delete from lineage_table");
            c.createStatement().execute("delete from meta_column");
            c.createStatement().execute("delete from meta_table");
            for (String col : new String[]{"table_type", "comment", "remark"}) {
                c.createStatement().execute("alter table lineage_table drop column " + col);
            }
            for (String col : new String[]{"data_type", "comment", "remark"}) {
                c.createStatement().execute("alter table lineage_column drop column " + col);
            }
            c.createStatement().execute("delete from schema_version");
            c.createStatement().execute(
                    "insert into schema_version(version, description) values('1.0.4','旧版本')");
        }
    }

    /** 用新脚本建库后回退成 1.0.3 的结构：catalog_name 还没加 NOT NULL。 */
    private static void applyLegacy103(DataSource ds) throws Exception {
        TestSchema.apply(ds, "h2");
        try (Connection c = ds.getConnection()) {
            c.createStatement().execute("delete from meta_column");
            c.createStatement().execute("delete from meta_table");
            relaxCatalogNotNull(c);
            c.createStatement().execute("delete from schema_version");
            c.createStatement().execute(
                    "insert into schema_version(version, description) values('1.0.3','旧版本')");
        }
    }

    /**
     * 把 catalog_name 改回可空。
     *
     * <p>1.0.4 给它加了 NOT NULL，而 1.0.3 及更早的库里它是可空的 ——
     * 不放开的话，这些回退测试连「无目录的存量表」都造不出来，
     * 也就测不到升级脚本归位存量的那段逻辑。
     */
    private static void relaxCatalogNotNull(Connection c) throws Exception {
        c.createStatement().execute(
                "alter table meta_table alter column catalog_name set null");
        c.createStatement().execute(
                "alter table lineage_table alter column catalog_name set null");
    }

    /**
     * 图遍历依赖递归 CTE，必须确认 H2 真的支持 —— 这是选型的前提条件。
     */
    @Test
    public void h2SupportsRecursiveCte() throws Exception {
        DataSource ds = TestDataSources.h2("recursive_cte");
        TestSchema.apply(ds, "h2");

        try (Connection c = ds.getConnection()) {
            // V3 起：表/字段是累积目录（不带 version_id），版本挂在目标表上
            c.createStatement().execute(
                    "insert into lineage_table(id,tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name) values(1,1,1,'cat','ods','t','cat.ods.t')");
            c.createStatement().execute(
                    "insert into lineage_version(id,tenant_id,project_id,target_table_id,version_no,"
                            + "db_type,sql_hash,is_current) values(1,1,1,1,1,'hive','h',1)");
            // 构造一条 3 跳链路 c1 <- c2 <- c3
            for (int i = 1; i <= 3; i++) {
                c.createStatement().execute(
                        "insert into lineage_column(id,tenant_id,project_id,table_id,column_name,full_name) "
                                + "values(" + i + ",1,1,1,'c" + i + "','cat.ods.t.c" + i + "')");
            }
            c.createStatement().execute(
                    "insert into lineage_edge(tenant_id,project_id,version_id,target_col_id,source_col_id) "
                            + "values(1,1,1,1,2),(1,1,1,2,3)");

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
                assertTrue(rs.next());
                assertEquals("应遍历出 c1/c2/c3 共 3 个节点", 3, rs.getInt(1));
            }
        }
    }

    /**
     * 元数据目录必须与血缘表隔离：往 meta_* 写数据不应影响 lineage_*，反之亦然。
     *
     * <p>这是本次模型改造的核心约束 —— 血缘里可能有推断出来的表，
     * 它们绝不能进元数据目录，否则下次解析又会把猜测当事实。
     */
    @Test
    public void metaAndLineageTablesAreIndependent() throws Exception {
        DataSource ds = TestDataSources.h2("meta_isolation");
        TestSchema.apply(ds, "h2");

        try (Connection c = ds.getConnection()) {
            c.createStatement().execute(
                    "insert into meta_table(tenant_id,project_id,catalog_name,schema_name,table_name,"
                            + "full_name,source) values(1,1,'cat','ods','real_t','cat.ods.real_t','DDL')");
            c.createStatement().execute(
                    "insert into lineage_table(tenant_id,project_id,catalog_name,schema_name,"
                            + "table_name,full_name) values(1,1,'cat','ods','inferred_t','cat.ods.inferred_t')");

            assertEquals("血缘里推断出来的表不应出现在元数据目录",
                    0, countWhere(c, "meta_table", "full_name = 'cat.ods.inferred_t'"));
            assertEquals("元数据里的表不应被自动写进血缘目录",
                    0, countWhere(c, "lineage_table", "full_name = 'cat.ods.real_t'"));
        }
    }

    /** V3 种下的内置「本地元数据目录」元数据源，让本地元数据能参与解析链的优先级编排。 */
    @Test
    public void builtInCatalogMetadataSourceIsSeeded() throws Exception {
        DataSource ds = TestDataSources.h2("catalog_source_seed");
        TestSchema.apply(ds, "h2");

        try (Connection c = ds.getConnection()) {
            assertEquals("应种下且只种下一条 CATALOG 类型的元数据源",
                    1, countWhere(c, "metadata_source", "type = 'CATALOG'"));
        }
    }

    private static int countWhere(Connection c, String table, String where) throws Exception {
        try (ResultSet rs = c.createStatement()
                .executeQuery("select count(*) from " + table + " where " + where)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    static Set<String> listTables(DataSource ds) throws Exception {
        Set<String> tables = new LinkedHashSet<>();
        try (Connection c = ds.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getTables(null, null, "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    tables.add(rs.getString("TABLE_NAME").toUpperCase());
                }
            }
        }
        return tables;
    }
}
