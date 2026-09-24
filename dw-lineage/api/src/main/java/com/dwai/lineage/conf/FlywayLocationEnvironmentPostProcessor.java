package com.dwai.lineage.conf;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 按 {@code database.type} 把 Flyway 脚本目录指到对应方言，
 * 与 dw-org / dw-model 的 {@code MetaDbEnvironmentPostProcessor} 做法一致。
 *
 * <p>application.yml 里只写了默认的 h2 目录；这里在环境（含 profile、环境变量、
 * {@code DB_TYPE}）就绪后按实际方言覆盖 {@code spring.flyway.locations}，
 * 避免运维切了数据库类型却忘了换脚本目录。
 *
 * <p>必须通过 {@code META-INF/spring.factories} 注册：它要赶在 Spring 容器
 * 创建之前运行，组件扫描轮不到它。
 */
public class FlywayLocationEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String type = env.getProperty("database.type", "h2").trim().toLowerCase(Locale.ROOT);
        String location = switch (type) {
            case "mysql" -> "classpath:db/migration/mysql";
            case "postgresql" -> "classpath:db/migration/postgresql";
            default -> "classpath:db/migration/h2";
        };

        Map<String, Object> map = new HashMap<>();
        map.put("spring.flyway.locations", location);
        env.getPropertySources().addFirst(new MapPropertySource("lineage-flyway", map));
    }
}
