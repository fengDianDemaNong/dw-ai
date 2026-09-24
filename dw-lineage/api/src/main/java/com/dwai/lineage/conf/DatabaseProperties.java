package com.dwai.lineage.conf;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 面向部署人员的数据库配置。
 *
 * <p>只需在 {@code conf/application.yml} 里选择 {@code database.type} 并填写连接信息，
 * JDBC URL、驱动类、Flyway 脚本目录（由 {@link FlywayLocationEnvironmentPostProcessor}
 * 按 {@code database.type} 推导）都自动匹配，
 * 不必让运维去拼 JDBC 串、记驱动类名，也避免脚本目录与数据库类型对不上。
 */
@ConfigurationProperties(prefix = "database")
public class DatabaseProperties {

    /** 数据库类型。 */
    public enum Type {
        /** 内嵌数据库，数据存放在 data 目录，开箱即用，适合单机与试用。 */
        H2,
        /** 需要 8.0 及以上，血缘图遍历依赖递归 CTE。 */
        MYSQL,
        /** 需要 12 及以上。 */
        POSTGRESQL
    }

    private Type type = Type.H2;

    private String host = "localhost";
    private Integer port;
    private String name = "dw_lineage";
    /** 数据库用户名，绑定 {@code DB_USER}（与 dw-org / dw-model 同口径）。 */
    private String username;

    /**
     * 用户名的旧名字，绑定 {@code DB_USERNAME}。
     *
     * <p>本服务早于 org/model 的 {@code DB_USER} 口径，老部署的 .env 里写的是
     * {@code DB_USERNAME}，不能一改了之。两个字段分开绑、由
     * {@link #resolveUsername()} 取第一个非空的 —— 不合并成
     * {@code ${DB_USER:${DB_USERNAME:}}} 是因为 Spring 对「已定义但为空串」的变量照样
     * 取值，而 compose 透传环境变量时必然注入空串，嵌套写法会让空值顶掉旧名。
     */
    private String usernameLegacy;

    private String password;

    /** H2 数据文件目录，仅 type=H2 时有效。 */
    private String h2Path = "./data/dw_lineage";

    /** 完整 JDBC URL。填写后将忽略 host/port/name，用于需要特殊连接参数的场景。 */
    private String url;

    /** 附加连接参数，形如 {@code useSSL=false&serverTimezone=Asia/Shanghai}。 */
    private String params;

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUsernameLegacy() {
        return usernameLegacy;
    }

    public void setUsernameLegacy(String usernameLegacy) {
        this.usernameLegacy = usernameLegacy;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getH2Path() {
        return h2Path;
    }

    public void setH2Path(String h2Path) {
        this.h2Path = h2Path;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    // ------------------------------------------------------------------
    // 由 type 推导出的技术细节
    // ------------------------------------------------------------------

    public String resolveUrl() {
        if (url != null && !url.isBlank()) {
            return url;
        }
        String base = switch (type) {
            case H2 -> "jdbc:h2:file:" + h2Path + ";DB_CLOSE_DELAY=-1";
            case MYSQL -> "jdbc:mysql://" + host + ":" + portOrDefault()
                    + "/" + name + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai";
            case POSTGRESQL -> "jdbc:postgresql://" + host + ":" + portOrDefault() + "/" + name;
        };
        if (params == null || params.isBlank()) {
            return base;
        }
        return base + (base.contains("?") ? "&" : "?") + params;
    }

    public String resolveDriver() {
        return switch (type) {
            case H2 -> "org.h2.Driver";
            case MYSQL -> "com.mysql.cj.jdbc.Driver";
            case POSTGRESQL -> "org.postgresql.Driver";
        };
    }

    /**
     * 建表脚本所在目录（相对安装目录），仅用于提示运维安装包里手工脚本的位置。
     * 应用本身的建表与升级由 Flyway 的 {@code db/migration/} 托管。
     */
    public String resolveScriptDir() {
        return "sql/" + type.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 取第一个非空的用户名：{@code DB_USER}（新口径，与 org/model 一致）→
     * {@code DB_USERNAME}（lineage 老名字）→ H2 时 {@code sa}，其余为空串。
     *
     * <p>两个变量分开判空而不是写成一个三级嵌套默认值，缘由见
     * {@link #usernameLegacy}。
     */
    public String resolveUsername() {
        if (username != null && !username.isBlank()) {
            return username;
        }
        if (usernameLegacy != null && !usernameLegacy.isBlank()) {
            return usernameLegacy;
        }
        return type == Type.H2 ? "sa" : "";
    }

    public String resolvePassword() {
        return password == null ? "" : password;
    }

    private int portOrDefault() {
        if (port != null && port > 0) {
            return port;
        }
        return type == Type.MYSQL ? 3306 : 5432;
    }
}
