package com.dwai.lineage.service.metadata.dbx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * dbx Web API 客户端。
 *
 * <p><b>所有字段名与响应结构均以实测为准</b>（见 PHASE3_PLAN.md 5.2），多处与官方文档不符，
 * 照文档写会直接失败。dbx 官方也声明这套 API 服务于其 Web 界面、不是对外契约，
 * 升级后可能断裂 —— 因此这里的解析一律取「拿不到就当没有」，不做严格反序列化。
 *
 * <p>连哪种数据库由 dbx 那边决定（原生 + Agent 驱动覆盖 80+ 种，含 Hive / Trino /
 * Databricks 等），本客户端不做类型判断，也不维护支持列表。
 *
 * <h2>两条硬性约束</h2>
 * <ol>
 *   <li><b>密码绝不落日志</b>：{@code GET /api/connection/list} 会明文返回数据库密码，
 *       该接口的原始响应不进任何级别的日志，解析后只保留
 *       {@link DbxConnectionSummary} 的四个字段。</li>
 *   <li><b>重登录只做一次</b>：dbx 会话存在其进程内存中，重启即失效，识别 401 后自动重登一次；
 *       但连续 5 次登录失败会锁定 60 秒，所以失败即放弃，不做密集重试。</li>
 * </ol>
 *
 * <p>本类非单例、不共享：每次使用时按某个 {@code metadata_source} 的地址与凭据构造，
 * 会话 Cookie 保存在实例字段里。
 */
public class DbxClient {

    private static final Logger logger = LoggerFactory.getLogger(DbxClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 一次取表清单的条数上限。
     *
     * <p>不带 limit 时 dbx 用的是未文档化的默认值，可能截断。取一个足够大的显式值，
     * 与 dbx 自己界面的量级一致。真有超过这个数的 schema，宁可在日志里看出来，
     * 也好过页面上少几张表而无人察觉。
     */
    private static final int TABLE_PAGE_LIMIT = 5000;

    // 这里刻意不维护「支持哪些数据源类型」的清单。
    // 早期版本照着服务端某条错误信息枚举了一份十来项的列表，并据此在产品文案里
    // 写了「dbx 不支持 hive / trino」—— 那只是原生驱动的一部分，实际 dbx 通过
    // 原生 + Agent 驱动覆盖 80+ 种数据库（含 Hive / Trino / Databricks 等）。
    // 硬编码这类清单只会随对方版本更新而变成错误信息，交给 dbx 自己判定即可。

    private final String baseUrl;
    private final String password;
    private final RestClient http;

    /** 登录后拿到的 {@code dbx_session} Cookie，形如 {@code dbx_session=xxx}。 */
    private volatile String sessionCookie;

    public DbxClient(String baseUrl, String password, RestClient.Builder builder) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.password = password;
        this.http = builder.baseUrl(this.baseUrl)
                // 4xx/5xx 不抛异常，交由各方法按实测的错误结构自行判断，
                // 否则 401 会先变成异常，重登录逻辑无从下手
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    // ------------------------------------------------------------------
    // 认证
    // ------------------------------------------------------------------

    /**
     * 登录并记住会话 Cookie。
     *
     * @throws DbxException 登录失败
     */
    public void login() {
        var response = http.post()
                .uri("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("password", password == null ? "" : password))
                .retrieve()
                .toEntity(String.class);

        if (response.getStatusCode().isError()) {
            // 密码错时 dbx 只回一个空体的 401，直接透传等于什么都没说，
            // 这里补上「可能是密码错」和登录锁定的提醒
            if (response.getStatusCode().value() == 401) {
                throw new DbxException("dbx 登录失败：密码不正确（dbx 返回 401）。"
                        + "注意连续 5 次登录失败会被锁定约 60 秒");
            }
            // 其它失败的响应体里不含密码，可以带上 detail 帮助定位（地址写错、服务未启动等）
            throw new DbxException("dbx 登录失败（HTTP " + response.getStatusCode().value() + "）: "
                    + describeError(response.getBody()));
        }

        String cookie = extractSessionCookie(response.getHeaders());
        if (cookie == null) {
            throw new DbxException("dbx 登录未返回会话 Cookie，响应: " + safeHead(response.getBody()));
        }
        this.sessionCookie = cookie;
        logger.debug("dbx 登录成功: {}", baseUrl);
    }

    /**
     * 探活：登录并确认认证状态。供页面的「测试连接」使用。
     *
     * @return 面向用户的成功描述
     */
    public String testConnection() {
        login();
        JsonNode node = getJson("/api/auth/check", Map.of());
        boolean authenticated = node != null && node.path("authenticated").asBoolean(false);
        if (!authenticated) {
            throw new DbxException("dbx 登录成功但认证状态校验未通过，请确认密码与服务版本");
        }
        int connections = listConnections().size();
        return "连接成功，dbx 中已保存 " + connections + " 个数据库连接";
    }

    // ------------------------------------------------------------------
    // 连接
    // ------------------------------------------------------------------

    /**
     * 已保存的连接列表（<b>已脱敏</b>）。
     *
     * <p>原始响应含明文密码，因此在本方法内部就地收敛，绝不返回也绝不打印。
     */
    public List<DbxConnectionSummary> listConnections() {
        JsonNode node = getJson("/api/connection/list", Map.of());
        List<DbxConnectionSummary> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            result.add(new DbxConnectionSummary(
                    text(item, "id"),
                    text(item, "name"),
                    text(item, "db_type"),
                    text(item, "database")));
        }
        return result;
    }

    /**
     * 建立连接。调任何 {@code /api/schema/*} 之前<b>必须</b>先做这一步。
     *
     * @return dbx 返回的连接 id（实测是个裸字符串，如 {@code "pgprobe"}）
     */
    public String connect(DbxConnectionConfig config) {
        String body = post("/api/connection/connect", Map.of("config", config.toRequestBody()));
        String id = unquote(body);
        return (id == null || id.isBlank()) ? config.id() : id;
    }

    /**
     * 按 id 建立一个<b>已保存在 dbx 中</b>的连接。
     *
     * <p>{@code /api/connection/connect} 要求带完整 config，而完整 config 只能从
     * {@code /api/connection/list} 取 —— 那个响应里含明文密码。这里的处理是：
     * 原始 JSON 节点<b>只在本方法的栈上停留</b>，直接转成请求体发出去，
     * 既不落日志、也不返回给调用方，更不会进入我方任何 DTO。
     * 对外可见的连接列表一律走脱敏的 {@link #listConnections()}。
     *
     * @return 该 id 是否命中了某条已保存的连接。<b>返回 false 不代表不能用</b>：
     *         dbx 的连接是有状态的，一个只在其界面上连过、并未保存的连接
     *         同样能被 schema 接口使用，此时应继续尝试而不是直接失败
     */
    @SuppressWarnings("unchecked")
    public boolean connectIfSaved(String connectionId) {
        JsonNode list = getJson("/api/connection/list", Map.of());
        if (list == null || !list.isArray()) {
            return false;
        }
        for (JsonNode item : list) {
            if (connectionId.equals(text(item, "id"))) {
                Map<String, Object> body = MAPPER.convertValue(item, Map.class);
                post("/api/connection/connect", Map.of("config", body));
                return true;
            }
        }
        return false;
    }

    /** 已保存连接的 id 列表，用于在报错时告诉用户有哪些可选。只含 id，不含任何其它字段。 */
    public List<String> savedConnectionIds() {
        List<String> ids = new ArrayList<>();
        for (DbxConnectionSummary summary : listConnections()) {
            ids.add(summary.id());
        }
        return ids;
    }

    /** 测试一个连接配置是否可用。实测成功时返回字符串 {@code "Connection successful"}。 */
    public String testConnectionConfig(DbxConnectionConfig config) {
        String body = post("/api/connection/test", Map.of("config", config.toRequestBody()));
        String text = unquote(body);
        return (text == null || text.isBlank()) ? "Connection successful" : text;
    }

    // ------------------------------------------------------------------
    // Schema
    // ------------------------------------------------------------------

    /** 库列表。实测返回<b>对象数组</b> {@code [{"name":"x"}]}。 */
    public List<String> databases(String connectionId) {
        JsonNode node = getJson("/api/schema/databases", Map.of("connection_id", connectionId));
        return namesOf(node);
    }

    /**
     * schema 列表。实测返回<b>字符串数组</b> {@code ["public"]} ——
     * 与上面的 databases 结构不一致，这是 dbx 自身的不统一，不是笔误。
     */
    public List<String> schemas(String connectionId, String database) {
        JsonNode node = getJson("/api/schema/schemas",
                Map.of("connection_id", connectionId, "database", database));
        return namesOf(node);
    }

    public List<String> tables(String connectionId, String database, String schema) {
        return namesOf(tableList(connectionId, database, schema));
    }

    /**
     * 一次取回某个 schema 下的表清单（含注释）。
     *
     * <p><b>必须显式给 limit</b>：实测 dbx 认这个参数（{@code limit=3} 就只回 3 条），
     * 而不给时用的是它自己的默认值 —— 那个值没有文档，库里表一多就可能被悄悄截断，
     * 页面上表列表少了几张还看不出来。dbx 自己的界面发的就是 1000 这个量级，跟它对齐。
     */
    private JsonNode tableList(String connectionId, String database, String schema) {
        return getJson("/api/schema/tables", Map.of(
                "connection_id", connectionId,
                "database", database,
                "schema", schema,
                "limit", String.valueOf(TABLE_PAGE_LIMIT),
                "offset", "0"));
    }

    /**
     * 某张表的注释。
     *
     * <p><b>表注释在这个接口里，不在 {@code /api/schema/columns} 里</b> ——
     * 实测 {@code /api/schema/tables} 每一项都带 {@code comment}（如「字段依赖」），
     * 只是 {@link #tables} 用 {@code namesOf} 把它连同其它字段一起丢了。
     * 曾据此以为「dbx 提供不了表注释」，是错的。
     *
     * <p>单独一个方法而不是让 {@link #tables} 返回富对象：那个方法服务于层级浏览，
     * 调用方只要名字；而这里是查看单表时才需要，多打一次请求换不改动既有调用方。
     *
     * @return 注释；表不存在或没有注释时返回 null
     */
    public String tableComment(String connectionId, String database, String schema, String table) {
        JsonNode node = tableList(connectionId, database, schema);
        if (node == null || !node.isArray() || table == null) {
            return null;
        }
        for (JsonNode item : node) {
            if (table.equalsIgnoreCase(text(item, "name"))) {
                return text(item, "comment");
            }
        }
        return null;
    }

    /**
     * 列结构 —— 血缘所需的核心接口。
     *
     * <p>只取列名并保持顺序；血缘不需要类型。<b>注意这里没有分区列的概念</b>，
     * 这是 dbx 的硬限制，Hive / Spark 表必须走 Gravitino。
     */
    public List<String> columns(String connectionId, String database, String schema, String table) {
        JsonNode node = getJson("/api/schema/columns", Map.of(
                "connection_id", connectionId, "database", database,
                "schema", schema, "table", table));
        return namesOf(node);
    }

    /**
     * 列结构的<b>完整</b>版本，供同步进元数据目录使用。
     *
     * <p>{@link #columns} 只返回列名（血缘解析只需要那个），而同步时类型与注释
     * 才是用户最关心的信息，所以单独给一个方法，避免为了省一次调用把两种需求揉在一起。
     */
    public List<DbxColumn> columnDetails(String connectionId, String database,
                                         String schema, String table) {
        JsonNode node = getJson("/api/schema/columns", Map.of(
                "connection_id", connectionId, "database", database,
                "schema", schema, "table", table));

        List<DbxColumn> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            String name = text(item, "name");
            if (name == null) {
                continue;
            }
            result.add(new DbxColumn(
                    name,
                    text(item, "data_type"),
                    text(item, "comment"),
                    // 缺字段时按「可空、非主键」处理，这两个属性缺失不影响血缘，不值得让整次同步失败
                    bool(item, "is_nullable", true),
                    bool(item, "is_primary_key", false)));
        }
        return result;
    }

    private static boolean bool(JsonNode node, String field, boolean fallback) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? fallback : value.asBoolean(fallback);
    }

    // ------------------------------------------------------------------
    // 传输
    // ------------------------------------------------------------------

    private JsonNode getJson(String path, Map<String, String> query) {
        String body = withSession(() -> {
            UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
            query.forEach((k, v) -> {
                if (v != null && !v.isBlank()) {
                    uri.queryParam(k, v);
                }
            });
            return http.get()
                    .uri(uri.build().toUriString())
                    .header(HttpHeaders.COOKIE, sessionCookie)
                    .retrieve()
                    .toEntity(String.class);
        }, path);
        return parse(body);
    }

    private String post(String path, Map<String, Object> body) {
        return withSession(() -> http.post()
                .uri(path)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.COOKIE, sessionCookie)
                .body(body)
                .retrieve()
                .toEntity(String.class), path);
    }

    /**
     * 带会话执行；遇到 401 自动重登<b>一次</b>后重试。
     *
     * <p>只重试一次是刻意的：dbx 连续 5 次登录失败会锁定 60 秒，
     * 密集重试只会把自己锁在门外。
     */
    private String withSession(Supplier<org.springframework.http.ResponseEntity<String>> call, String path) {
        // 先记下本次请求用的是哪个会话，401 后据此判断「是否已经有人替我重登过了」
        String used = ensureSession(null);

        var response = call.get();
        if (response.getStatusCode().value() == 401) {
            logger.debug("dbx 会话已失效，重新登录后重试一次: {}", path);
            ensureSession(used);
            response = call.get();
        }

        if (response.getStatusCode().isError()) {
            throw new DbxException("dbx 请求失败 " + path + ": " + describeError(response.getBody()));
        }
        return response.getBody();
    }

    /**
     * 保证有可用会话，必要时登录。
     *
     * <h2>为什么要同步 + 比对旧会话</h2>
     * 同步元数据时会有几十个请求并发打过来。原先的写法是每个拿到 401 的线程各自
     * {@code sessionCookie = null; login();}：
     *
     * <ul>
     *   <li>先重登成功的线程刚写好新 Cookie，就被后一个线程的 {@code = null} 抹掉，
     *       于是后面每个请求又各自重登一次，级联下去</li>
     *   <li>dbx 连续 5 次登录失败会锁定 60 秒 —— 密码真的错时，
     *       这种并发风暴会把自己牢牢锁在门外，而且报错原因看起来像「服务不可用」</li>
     * </ul>
     *
     * <p>所以改成：{@code staleCookie} 是调用方刚用过、已被判定失效的那个会话。
     * 进来发现当前会话已经不是它了，说明别人重登过，直接复用，不再打一次登录请求。
     *
     * @param staleCookie 已失效的会话；首次调用传 null
     * @return 当前可用的会话 Cookie
     */
    private synchronized String ensureSession(String staleCookie) {
        if (sessionCookie != null && !sessionCookie.equals(staleCookie)) {
            return sessionCookie;
        }
        login();
        return sessionCookie;
    }

    // ------------------------------------------------------------------
    // 解析辅助
    // ------------------------------------------------------------------

    /**
     * 统一把「对象数组」与「字符串数组」两种形态都摊平成名字列表。
     *
     * <p>dbx 的 databases 返回前者、schemas 返回后者，与其把这种不一致漏给调用方，
     * 不如在这里吸收掉。
     */
    private static List<String> namesOf(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return names;
        }
        for (JsonNode item : node) {
            if (item.isTextual()) {
                names.add(item.asText());
            } else {
                String name = text(item, "name");
                if (name != null) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    private static JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            // 部分接口返回裸字符串而非 JSON，这里不算错误
            logger.debug("dbx 响应不是 JSON，按原样处理");
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /**
     * 从实测的错误结构里取出可读信息。
     *
     * <p>dbx 的错误体是 {@code {version, code, messageKey, detail, operationOutcome, source, origin}}，
     * 不是文档说的「带 error 字段的 JSON」。
     */
    private static String describeError(String body) {
        JsonNode node = parse(body);
        if (node == null) {
            return safeHead(body);
        }
        String detail = text(node, "detail");
        String code = text(node, "code");
        if (detail != null) {
            return code == null ? detail : detail + " (" + code + ")";
        }
        return safeHead(body);
    }

    /** 截断，避免把超长响应整个塞进异常消息。 */
    private static String safeHead(String body) {
        if (body == null) {
            return "(空响应)";
        }
        String trimmed = body.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "…";
    }

    /** 部分接口返回的是被引号包住的裸字符串，如 {@code "pgprobe"}。 */
    private static String unquote(String body) {
        if (body == null) {
            return null;
        }
        String trimmed = body.strip();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String extractSessionCookie(HttpHeaders headers) {
        List<String> cookies = headers.get(HttpHeaders.SET_COOKIE);
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies) {
            if (cookie.startsWith("dbx_session=")) {
                // 只保留 name=value，丢掉 Path/HttpOnly 等属性
                int end = cookie.indexOf(';');
                return end < 0 ? cookie : cookie.substring(0, end);
            }
        }
        return null;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("dbx 地址不能为空");
        }
        String trimmed = url.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
