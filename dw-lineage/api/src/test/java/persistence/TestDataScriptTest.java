package persistence;

import org.junit.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 测试数据脚本必须能在建好的库上跑通，并造出功能验证需要的那几种数据。
 *
 * <p>脚本是给人手工执行的，没人跑测试它就只能等到用的时候才发现写错了 ——
 * 而它恰恰是用来验证功能的，坏掉的话验证本身就不可信。
 */
public class TestDataScriptTest {

    @Test
    public void testDataLoadsAndCoversTheScenarios() {
        DataSource ds = TestDataSources.h2("testdata_h2");
        TestSchema.apply(ds, "h2");
        TestSchema.runScript(ds, Path.of("..", "release", "sql", "testdata", "h2", "test_data.sql"));

        JdbcTemplate jdbc = new JdbcTemplate(ds);

        // 1. 两个数据目录下的同名表 —— catalog 参与唯一性的关键证据
        assertEquals("hive_prod / hive_test 下应各有一张 ods.orders",
                Integer.valueOf(2),
                jdbc.queryForObject("select count(*) from meta_table where full_name like '%ods.orders'",
                        Integer.class));
        assertEquals("两张同名表的字段数应当不同，证明没有互相覆盖",
                Integer.valueOf(2),
                jdbc.queryForObject("select count(distinct cnt) from ("
                        + "select table_id, count(*) cnt from meta_column "
                        + "where table_id in (select id from meta_table where full_name like '%ods.orders') "
                        + "group by table_id)", Integer.class));

        // 2. 每张表都有数据目录（1.0.3 起是硬约束），没写目录的落到 default
        assertEquals("应有一张落在默认目录下的 ods.users",
                Integer.valueOf(1),
                jdbc.queryForObject("select count(*) from meta_table "
                        + "where full_name = 'default.ods.users' and catalog_name = 'default'",
                        Integer.class));
        assertEquals("不该再有没有数据目录的表",
                Integer.valueOf(0),
                jdbc.queryForObject("select count(*) from meta_table where catalog_name is null",
                        Integer.class));

        // 3. 三个隔离域
        assertEquals("租户1/项目1、租户1/项目1000、租户1000/项目1001",
                Integer.valueOf(3),
                jdbc.queryForObject("select count(*) from (select distinct tenant_id, project_id "
                        + "from meta_table)", Integer.class));

        // 4. 同一目标表两个版本，边数不同
        assertEquals("dwd.order_detail 应有 2 个版本",
                Integer.valueOf(2),
                jdbc.queryForObject("select count(*) from lineage_version where target_table_id = 1004",
                        Integer.class));
        assertEquals("两个版本的边数必须不同，否则切版本时看不出区别",
                Integer.valueOf(2),
                jdbc.queryForObject("select count(distinct cnt) from ("
                        + "select version_id, count(*) cnt from lineage_edge "
                        + "where version_id in (1000, 1001) group by version_id)", Integer.class));
        assertEquals("当前版本应当只有一个",
                Integer.valueOf(1),
                jdbc.queryForObject("select count(*) from lineage_version "
                        + "where target_table_id = 1004 and is_current = 1", Integer.class));

        // 5. 数据目录：每个项目有且只有一个默认目录
        assertEquals("三个域各有一个默认目录，多一个少一个都是坏的",
                Integer.valueOf(0),
                jdbc.queryForObject("select count(*) from ("
                        + "select tenant_id, project_id, count(*) c from data_catalog "
                        + "where is_default = 1 group by tenant_id, project_id having count(*) <> 1)",
                        Integer.class));
        assertEquals("meta_table 用到的目录都要在 data_catalog 里登记，否则配置页看不到它",
                Integer.valueOf(0),
                jdbc.queryForObject("select count(distinct m.catalog_name) from meta_table m "
                        + "where not exists (select 1 from data_catalog c "
                        + "where c.tenant_id = m.tenant_id and c.project_id = m.project_id "
                        + "and c.name = lower(m.catalog_name))", Integer.class));

        // 6. 临时表规则：停用的那条不能被算进去
        assertEquals("启用中的规则应有 4 条（sandbox 那条是停用的）",
                Integer.valueOf(4),
                jdbc.queryForObject("select count(*) from temp_rule where enabled = 1",
                        Integer.class));

        // 7. 自增序列已推进：再插一行不能撞主键
        jdbc.update("insert into tenant(code, name, status) values('probe', '探针', 1)");
        Integer probeId = jdbc.queryForObject(
                "select id from tenant where code = 'probe'", Integer.class);
        assertTrue("自增序列没推进，新插入的 id=" + probeId + " 撞上了脚本写入的 id",
                probeId != null && probeId > 1000);
    }
}
