package com.dwai.lineage.service.metadata.dbx;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * dbx 的一个数据库连接配置，对应 {@code POST /api/connection/connect} 请求体里的 {@code config}。
 *
 * <p>字段名与 dbx 的实测契约一一对应（snake_case）。几个必须照实测来、照文档写会失败的点：
 * <ul>
 *   <li><b>{@code id} 必填</b>：文档示例里没有，不带会 422</li>
 *   <li><b>{@code db_type} 只接受 {@code postgres}</b>，写 {@code postgresql} 会 422</li>
 *   <li><b>SQLite 的文件路径放在 {@code host}</b>，不是文档写的 {@code file_path}</li>
 * </ul>
 *
 * @param password 明文密码，只在内存中存在于一次调用期间。<b>禁止 toString / 日志输出</b>
 */
public record DbxConnectionConfig(
        String id,
        String name,
        String dbType,
        String host,
        Integer port,
        String username,
        String password,
        String database) {

    public Map<String, Object> toRequestBody() {
        // 用 LinkedHashMap 而不是 Map.of：顺序稳定便于排查。
        // 这些字段一个都不能少：实测缺 port 会直接 422
        // （`missing field \`port\``），即便是不需要端口的 sqlite 也一样。
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("id", id);
        config.put("name", name == null ? id : name);
        config.put("db_type", dbType);
        config.put("host", host == null ? "" : host);
        config.put("port", port == null ? 0 : port);
        config.put("username", username == null ? "" : username);
        config.put("password", password == null ? "" : password);
        config.put("database", database == null ? "" : database);
        return config;
    }

    /**
     * 刻意覆盖：record 默认的 toString 会把 password 打出来，
     * 一旦被塞进日志或异常消息就等于明文泄漏。
     */
    @Override
    public String toString() {
        return "DbxConnectionConfig[id=" + id + ", dbType=" + dbType
                + ", database=" + database + ", password=***]";
    }
}
