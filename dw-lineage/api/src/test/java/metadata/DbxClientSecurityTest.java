package metadata;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.service.metadata.dbx.DbxClient;
import com.dwai.lineage.service.metadata.dbx.DbxConnectionConfig;
import com.dwai.lineage.service.metadata.dbx.DbxConnectionSummary;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * dbx 客户端的安全约束。
 *
 * <p>背景：{@code GET /api/connection/list} 实测会<b>明文返回数据库密码</b>
 * （与其文档中「敏感信息不会出现在返回 JSON 中」的说法相反）。这几条用例就是
 * PHASE3_PLAN.md 5.2 里那条「要写成测试用例：断言日志与响应中不出现密码」的落实。
 *
 * <p>用一个本地 HttpServer 假扮 dbx，避免依赖真实服务。
 */
public class DbxClientSecurityTest {

    /** 假 dbx 在连接列表里回吐的明文密码。任何地方出现它都算泄漏。 */
    private static final String LEAKED_PASSWORD = "hubpass-should-never-leak";

    private static final String SESSION_COOKIE = "dbx_session=fake-session";

    private HttpServer server;
    private String baseUrl;

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> logs;

    private final AtomicInteger loginCount = new AtomicInteger();
    /** 置 true 后，除 login 外的接口先返回一次 401，用于验证「自动重登一次」。 */
    private volatile boolean expireSessionOnce;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", exchange -> {
            loginCount.incrementAndGet();
            exchange.getResponseHeaders().add("Set-Cookie", SESSION_COOKIE + "; Path=/; HttpOnly");
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/api/auth/check", exchange ->
                respond(exchange, 200, "{\"authenticated\":true,\"required\":true}"));
        server.createContext("/api/connection/list", exchange -> {
            if (expireSessionOnce) {
                expireSessionOnce = false;
                respond(exchange, 401, "{\"detail\":\"unauthorized\"}");
                return;
            }
            // 实测结构：密码明文出现在列表里
            respond(exchange, 200, """
                    [{"id":"pgprobe","name":"probe","db_type":"postgres","host":"127.0.0.1",
                      "port":5432,"username":"u","password":"%s","database":"kohakuhub"}]
                    """.formatted(LEAKED_PASSWORD));
        });
        server.createContext("/api/connection/connect", exchange -> respond(exchange, 200, "\"pgprobe\""));
        server.createContext("/api/schema/columns", exchange ->
                respond(exchange, 200, "[{\"name\":\"id\"},{\"name\":\"pt\"}]"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        // 挂到 root 上并放开到 TRACE：只有把所有级别都收进来，
        // 「任何级别的日志都不得出现密码」这条断言才成立
        rootLogger = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
        rootLogger.setLevel(Level.TRACE);
    }

    @After
    public void tearDown() {
        rootLogger.detachAppender(logs);
        rootLogger.setLevel(Level.INFO);
        server.stop(0);
    }

    @Test
    public void connectionListIsSanitized() {
        List<DbxConnectionSummary> connections = newClient().listConnections();

        assertEquals(1, connections.size());
        DbxConnectionSummary only = connections.get(0);
        assertEquals("pgprobe", only.id());
        assertEquals("postgres", only.dbType());
        assertEquals("kohakuhub", only.database());
        // record 的 toString 会打印全部字段，这里等价于「对外表示里没有凭据」
        assertFalse("脱敏后的连接摘要不得包含密码", only.toString().contains(LEAKED_PASSWORD));
    }

    /**
     * 我方代码在任何级别下都不得把密码写进日志。
     *
     * <p>断言范围限定在 {@code com.dwai.lineage}。HTTP 客户端自己的报文日志
     * （{@code org.apache.hc.client5.http.wire} 等）确实会打印整个响应体，
     * 那不是本类能控制的，只能靠日志级别钉死 —— 见
     * {@link #httpWireLoggingIsPinnedInAppConfig()}。两条用例合起来才是完整的保证。
     */
    @Test
    public void ourCodeNeverLogsThePassword() {
        DbxClient client = newClient();
        client.listConnections();
        client.connectIfSaved("pgprobe");

        List<String> offenders = logs.list.stream()
                .filter(e -> e.getLoggerName().startsWith("com.dwai.lineage"))
                .filter(e -> e.getFormattedMessage().contains(LEAKED_PASSWORD))
                .map(ILoggingEvent::getLoggerName)
                .distinct()
                .toList();

        assertTrue("以下 logger 把 dbx 返回的明文密码写进了日志: " + offenders, offenders.isEmpty());
    }

    /**
     * HTTP 报文日志必须被钉在 INFO。
     *
     * <p>否则有人为了排查问题把 root 调成 DEBUG，dbx 那个「明文返回密码」的响应体
     * 就会被整段写进日志文件。这条配置很容易在后续调整日志时被顺手删掉，
     * 所以用测试守住。
     */
    @Test
    @SuppressWarnings("unchecked")
    public void httpWireLoggingIsPinnedInAppConfig() throws IOException {
        Map<String, Object> yaml;
        try (var in = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            yaml = new Yaml().load(in);
        }

        Map<String, Object> logging = (Map<String, Object>) yaml.get("logging");
        Map<String, Object> levels = (Map<String, Object>) logging.get("level");

        for (String logger : List.of("org.apache.hc.client5.http.wire",
                "org.apache.hc.client5.http.headers",
                "org.springframework.web.client")) {
            Object level = levels.get(logger);
            assertEquals("application.yml 里 " + logger + " 必须钉在 INFO，"
                    + "否则 dbx 明文返回的密码会随报文日志落盘", "INFO", level);
        }
    }

    /** 连接配置本身也不能因为被塞进日志或异常消息而泄漏密码。 */
    @Test
    public void connectionConfigToStringMasksPassword() {
        DbxConnectionConfig config = new DbxConnectionConfig(
                "pgprobe", "probe", "postgres", "127.0.0.1", 5432, "u", LEAKED_PASSWORD, "db");

        assertFalse(config.toString().contains(LEAKED_PASSWORD));
        assertTrue(config.toString().contains("password=***"));
        // 但真正发出去的请求体里必须带上，否则连不上
        assertEquals(LEAKED_PASSWORD, config.toRequestBody().get("password"));
    }

    /**
     * dbx 会话存在其进程内存里，重启即失效。识别 401 后应自动重登<b>一次</b>并重试；
     * 不能无脑重试 —— 连续 5 次登录失败会被锁 60 秒。
     */
    @Test
    public void reLoginsOnceWhenSessionExpired() {
        DbxClient client = newClient();
        client.listConnections();
        int loginsBefore = loginCount.get();

        expireSessionOnce = true;
        List<DbxConnectionSummary> connections = client.listConnections();

        assertEquals("重登后应拿到正常结果", 1, connections.size());
        assertEquals("只应额外登录一次", loginsBefore + 1, loginCount.get());
    }

    /** dbx 拿不到分区列，这里只验证列名解析本身正确，分区能力的缺失由 Provider 层加 warning。 */
    @Test
    public void columnsAreParsed() {
        assertEquals(List.of("id", "pt"),
                newClient().columns("pgprobe", "kohakuhub", "public", "user"));
    }

    private DbxClient newClient() {
        return new DbxClient(baseUrl, "login-password", RestClient.builder());
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
