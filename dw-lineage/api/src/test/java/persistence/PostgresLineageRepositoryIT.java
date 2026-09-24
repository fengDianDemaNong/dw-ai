package persistence;


import javax.sql.DataSource;

/**
 * PostgreSQL 后端。需通过系统属性提供连接信息，否则整个用例集跳过：
 * {@code -Dit.postgres.url=... -Dit.postgres.username=... -Dit.postgres.password=...}
 */
public class PostgresLineageRepositoryIT extends AbstractLineageRepositoryTest {

    private static DataSource dataSource;
    private static boolean migrated;

    @Override
    protected DataSource dataSource() {
        DataSource ds = dataSource != null
                ? dataSource
                : TestDataSources.external("postgres", "org.postgresql.Driver");
        if (ds == null) {
            return null;
        }
        if (!migrated) {
            TestSchema.reset(ds, "postgresql");
            migrated = true;
        }
        dataSource = ds;
        return ds;
    }
}
