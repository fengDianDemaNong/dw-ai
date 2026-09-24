package persistence;


import javax.sql.DataSource;

/**
 * MySQL 后端。需通过系统属性提供连接信息，否则整个用例集跳过：
 * {@code -Dit.mysql.url=... -Dit.mysql.username=... -Dit.mysql.password=...}
 */
public class MySqlLineageRepositoryIT extends AbstractLineageRepositoryTest {

    private static DataSource dataSource;
    private static boolean migrated;

    @Override
    protected DataSource dataSource() {
        DataSource ds = dataSource != null
                ? dataSource
                : TestDataSources.external("mysql", "com.mysql.cj.jdbc.Driver");
        if (ds == null) {
            return null;
        }
        if (!migrated) {
            TestSchema.reset(ds, "mysql");
            migrated = true;
        }
        dataSource = ds;
        return ds;
    }
}
