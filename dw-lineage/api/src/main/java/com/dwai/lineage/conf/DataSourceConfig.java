package com.dwai.lineage.conf;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 按 {@link DatabaseProperties#getType()} 组装数据源。
 *
 * <p>这样部署时只需在 conf 里选类型并填连接信息，JDBC URL 与驱动自动保持一致。
 *
 * <p><b>表结构由 Flyway 管理</b>（与 dw-org / dw-model 一致）：空库启动时自动执行
 * {@code db/migration/} 下的迁移脚本；脚本目录由
 * {@link FlywayLocationEnvironmentPostProcessor} 按 {@code database.type} 指定。
 */
@Configuration
@EnableConfigurationProperties(DatabaseProperties.class)
public class DataSourceConfig {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceConfig.class);

    private final DatabaseProperties properties;

    public DataSourceConfig(DatabaseProperties properties) {
        this.properties = properties;
    }

    @Bean
    public DataSource dataSource() {
        String url = properties.resolveUrl();
        logger.info("数据库类型={}, url={}", properties.getType(), maskPassword(url));

        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setDriverClassName(properties.resolveDriver());
        ds.setUsername(properties.resolveUsername());
        ds.setPassword(properties.resolvePassword());
        ds.setPoolName("lineage-pool");
        return ds;
    }

    /** 日志里不要出现密码。 */
    private static String maskPassword(String url) {
        return url.replaceAll("(?i)(password=)[^&;]*", "$1***");
    }
}
