package com.dwai.lineage.cli;

import com.dwai.lineage.conf.DatabaseProperties;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 从 {@code conf/application.yml} 读取数据库配置，供命令行工具复用。
 *
 * <p>不启动 Spring 容器 —— CLI 只是要连个库执行 SQL，
 * 为此拉起整个应用上下文既慢又可能因为端口占用等原因失败。
 */
final class ConfLoader {

    private ConfLoader() {
    }

    /**
     * @param confPath 指定的配置文件路径，为空时按约定依次查找
     */
    static DatabaseProperties load(String confPath) {
        Path path = confPath != null ? Path.of(confPath) : locateDefault();
        if (path == null || !Files.isReadable(path)) {
            throw new IllegalStateException(
                    "找不到配置文件，请用 -c 指定，或用 -u 直接给出 JDBC 连接串");
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(path)) {
            root = new Yaml().load(in);
        } catch (Exception e) {
            throw new IllegalStateException("读取配置文件失败: " + path + " — " + e.getMessage(), e);
        }
        if (root == null) {
            throw new IllegalStateException("配置文件为空: " + path);
        }

        Object section = root.get("database");
        if (!(section instanceof Map<?, ?> db)) {
            throw new IllegalStateException("配置文件中缺少 database 段: " + path);
        }

        DatabaseProperties props = new DatabaseProperties();
        String type = str(db.get("type"));
        if (type != null) {
            props.setType(parseType(type));
        }
        applyIfPresent(str(db.get("host")), props::setHost);
        applyIfPresent(str(db.get("name")), props::setName);
        applyIfPresent(str(db.get("username")), props::setUsername);
        applyIfPresent(str(db.get("username-legacy")), props::setUsernameLegacy);
        applyIfPresent(str(db.get("password")), props::setPassword);
        applyIfPresent(str(db.get("h2-path")), props::setH2Path);
        applyIfPresent(str(db.get("params")), props::setParams);
        applyIfPresent(str(db.get("url")), props::setUrl);

        Object port = db.get("port");
        if (port instanceof Number n) {
            props.setPort(n.intValue());
        } else if (port != null && !port.toString().isBlank()) {
            props.setPort(Integer.parseInt(port.toString().trim()));
        }

        // 环境变量优先：yaml_get 是纯文本解析、看不见环境变量，所以 conf/env.sh 里
        // export 的这一组必须在这里补一遍，口径与 start.sh 的桥接、以及 dw-org /
        // dw-model 保持一致（DB_TYPE / DB_HOST / DB_PORT / DB_NAME / DB_URL /
        // DB_USER / DB_PASSWORD）。
        //
        // 少认一个就会留下「一半对一半错」的状态：bin/init-db.sh 用 DB_TYPE=mysql
        // 建好了库，建表那步 CLI 却按 yml 里的 h2 去连，拿 MySQL 方言的脚本在 H2 上跑，
        // 报的是一句语法错，很难联想到根因是这里没读环境变量。
        applyIfPresent(System.getenv("DB_TYPE"), v -> props.setType(parseType(v)));
        applyIfPresent(System.getenv("DB_HOST"), props::setHost);
        applyIfPresent(System.getenv("DB_NAME"), props::setName);
        applyIfPresent(System.getenv("DB_URL"), props::setUrl);
        applyIfPresent(System.getenv("DB_PORT"), v -> props.setPort(parsePort(v)));
        applyIfPresent(System.getenv("DB_PASSWORD"), props::setPassword);
        applyIfPresent(System.getenv("DB_USER"), props::setUsername);
        return props;
    }

    /** 从常见位置查找配置：当前目录、上一级的 conf（从 bin 调用时）。 */
    private static Path locateDefault() {
        for (String candidate : new String[]{
                "conf/application.yml", "./application.yml", "../conf/application.yml"}) {
            Path p = Path.of(candidate);
            if (Files.isReadable(p)) {
                return p;
            }
        }
        return null;
    }

    private static DatabaseProperties.Type parseType(String raw) {
        try {
            return DatabaseProperties.Type.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("不支持的数据库类型: " + raw + "（可选 h2 / mysql / postgresql）", e);
        }
    }

    private static int parsePort(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("DB_PORT 不是合法的端口号: " + raw, e);
        }
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private static void applyIfPresent(String value, java.util.function.Consumer<String> setter) {
        if (value != null && !value.isBlank()) {
            setter.accept(value);
        }
    }
}
