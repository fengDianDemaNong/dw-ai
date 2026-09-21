package persistence;

import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

/** 测试用数据源工厂。 */
public final class TestDataSources {

    private TestDataSources() {
    }

    /** 独立命名的内存 H2，避免用例之间互相干扰。 */
    public static DataSource h2(String name) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.h2.Driver");
        ds.setUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE");
        ds.setUsername("sa");
        ds.setPassword("");
        return ds;
    }

    /**
     * 外部数据库，由系统属性注入，未配置时返回 null，调用方据此跳过用例。
     *
     * <pre>
     * mvn test -Dit.postgres.url=jdbc:postgresql://localhost:5432/db \
     *          -Dit.postgres.username=u -Dit.postgres.password=p
     * </pre>
     */
    public static DataSource external(String prefix, String driver) {
        String url = System.getProperty("it." + prefix + ".url");
        if (url == null || url.isBlank()) {
            return null;
        }
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(driver);
        ds.setUrl(url);
        ds.setUsername(System.getProperty("it." + prefix + ".username", ""));
        ds.setPassword(System.getProperty("it." + prefix + ".password", ""));
        return ds;
    }
}
