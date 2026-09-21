package persistence;


import javax.sql.DataSource;

/** H2 后端。无需外部依赖，CI 中稳定运行。 */
public class H2LineageRepositoryTest extends AbstractLineageRepositoryTest {

    private static DataSource dataSource;

    @Override
    protected DataSource dataSource() {
        if (dataSource == null) {
            DataSource ds = TestDataSources.h2("repo_h2");
            TestSchema.apply(ds, "h2");
            dataSource = ds;
        }
        return dataSource;
    }
}
