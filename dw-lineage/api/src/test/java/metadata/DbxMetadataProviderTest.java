package metadata;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.melin.sqlflow.metadata.QualifiedObjectName;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import com.dwai.lineage.service.metadata.dbx.DbxClient;
import com.dwai.lineage.service.metadata.provider.DbxMetadataProvider;
import com.dwai.lineage.service.metadata.provider.MetadataResolution;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link DbxMetadataProvider} 对 {@link com.dwai.lineage.service.metadata.provider.MetadataProvider}
 * 契约的遵守情况：批量入参、查不到进 unresolved 而不抛异常、以及分区列缺失的告警。
 *
 * <p>用本地 HttpServer 假扮 dbx。真机联调见 PHASE3_PROGRESS.md 的记录。
 */
public class DbxMetadataProviderTest {

    private HttpServer server;
    private String baseUrl;

    /** 置 true 时 /api/connection/list 返回空数组，模拟「连接没保存过」。 */
    private volatile boolean noSavedConnections;

    @Before
    public void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "dbx_session=s; Path=/");
            respond(exchange, 200, "{\"ok\":true}");
        });
        server.createContext("/api/connection/list", exchange -> respond(exchange, 200,
                noSavedConnections ? "[]"
                        : "[{\"id\":\"pgprobe\",\"name\":\"probe\",\"db_type\":\"postgres\","
                        + "\"password\":\"secret\",\"database\":\"kohakuhub\"}]"));
        server.createContext("/api/connection/connect", exchange -> respond(exchange, 200, "\"pgprobe\""));
        server.createContext("/api/schema/columns", exchange -> {
            String query = exchange.getRequestURI().getQuery();
            if (query != null && query.contains("table=ods_user")) {
                respond(exchange, 200,
                        "[{\"name\":\"id\"},{\"name\":\"name\"},{\"name\":\"city\"}]");
            } else if (query != null && query.contains("table=missing")) {
                // dbx 对不存在的表返回空列表
                respond(exchange, 200, "[]");
            } else {
                respond(exchange, 500, "{\"detail\":\"Connection config not found\"}");
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void resolvesColumnsForKnownTable() {
        MetadataResolution resolution = newProvider().resolve(Set.of(table("public", "ods_user")));

        var resolved = resolution.resolved().get(table("public", "ods_user"));
        assertEquals(List.of("id", "name", "city"), resolved.getColumns());
        assertTrue("dbx 拿不到分区列，这里必须是空的，不能编造",
                resolved.getPartitionColumns().isEmpty());
    }

    /**
     * 拿到结构就要提醒分区列的缺失。
     *
     * <p>dbx 本身能连的数据库很多（含 Hive），但实测其 schema 接口不返回分区列，
     * 用户对分区表用了 dbx 却毫不知情的话，会得到一份少了分区字段的血缘。
     */
    @Test
    public void warnsAboutMissingPartitionColumns() {
        MetadataResolution resolution = newProvider().resolve(Set.of(table("public", "ods_user")));

        assertTrue("应提示结构里不含分区列，并建议用 Gravitino 交叉验证",
                resolution.warnings().stream().anyMatch(w -> w.contains("分区列") && w.contains("Gravitino")));
    }

    /** 单张表查不到属于正常情况：进 unresolved，不能让整次解析失败。 */
    @Test
    public void unknownTableGoesToUnresolvedWithoutThrowing() {
        MetadataResolution resolution = newProvider()
                .resolve(Set.of(table("public", "ods_user"), table("public", "missing")));

        assertEquals(Set.of(table("public", "missing")), resolution.unresolved());
        assertEquals(1, resolution.resolved().size());
    }

    /**
     * 连接不在「已保存」列表里时不能直接失败：dbx 的连接是有状态的，
     * 用户可能只在其界面上连了没保存。此时应继续尝试，由 schema 调用决定成败。
     */
    @Test
    public void proceedsWhenConnectionIsNotSavedButActive() {
        noSavedConnections = true;

        MetadataResolution resolution = newProvider().resolve(Set.of(table("public", "ods_user")));

        assertEquals(1, resolution.resolved().size());
    }

    /** 连接 id 写错时，报错里要带上 dbx 现有的连接 id，否则用户不知道该填什么。 */
    @Test
    public void reportsAvailableConnectionIdsWhenConnectionMissing() {
        MetadataResolution resolution = newProvider().resolve(Set.of(table("public", "other_table")));

        assertTrue("报错应指向 extraConfig 里的 connectionId 并列出可用连接: " + resolution.warnings(),
                resolution.warnings().stream()
                        .anyMatch(w -> w.contains("connectionId") && w.contains("pgprobe")));
    }

    /**
     * 一张表都没解析出来时不能提「分区列拿不到」。
     *
     * <p>那句提示的含义是「这些结构是 dbx 给的，其中不含分区列」；
     * 什么都没给出来时再说一遍，只会让用户以为分区列是失败的原因。
     */
    @Test
    public void noPartitionWarningWhenNothingResolved() {
        MetadataResolution resolution = newProvider().resolve(Set.of(table("public", "other_table")));

        assertTrue("没有解析出任何表结构时不应出现分区列提示: " + resolution.warnings(),
                resolution.warnings().stream().noneMatch(w -> w.contains("分区列")));
    }

    @Test
    public void emptyInputShortCircuits() {
        assertTrue(newProvider().resolve(Set.of()).resolved().isEmpty());
    }

    private DbxMetadataProvider newProvider() {
        DbxClient client = new DbxClient(baseUrl, "pwd", RestClient.builder());
        return new DbxMetadataProvider(client, "pgprobe", "kohakuhub", "public");
    }

    private static QualifiedObjectName table(String schema, String name) {
        return new QualifiedObjectName("dbx", schema, name);
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
