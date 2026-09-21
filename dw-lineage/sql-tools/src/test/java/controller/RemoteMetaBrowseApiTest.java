package controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.dwai.lineage.Main;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 远程表结构的只读查看（{@code GET /api/meta/sync/table}）。
 *
 * <p>「元数据」页切到某个数据服务后，光有表名判断不了要不要导入，所以要能点开看字段。
 * 这个接口与导入<b>共用同一份字段映射</b>，所以这里断言的映射规则同时也守着导入。
 *
 * <p>用本地 HttpServer 假扮 dbx，写法与 {@code DbxMetadataProviderTest} 一致。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:remote_browse_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password="
})
@AutoConfigureMockMvc
class RemoteMetaBrowseApiTest {

    static {
        persistence.TestSchema.apply(persistence.TestDataSources.h2("remote_browse_test"), "h2");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static HttpServer dbx;
    private static String dbxUrl;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startFakeDbx() throws IOException {
        dbx = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        dbx.createContext("/api/auth/login", ex -> {
            ex.getResponseHeaders().add("Set-Cookie", "dbx_session=s; Path=/");
            respond(ex, 200, "{\"ok\":true}");
        });
        dbx.createContext("/api/connection/list", ex -> respond(ex, 200,
                "[{\"id\":\"c1\",\"name\":\"probe\",\"db_type\":\"postgres\"}]"));
        dbx.createContext("/api/connection/connect", ex -> respond(ex, 200, "\"c1\""));
        // 表注释在这个接口里（不在 columns 里）—— 真实 dbx 就是这么返回的
        dbx.createContext("/api/schema/tables", ex -> respond(ex, 200,
                "[{\"name\":\"orders\",\"table_type\":\"BASE TABLE\",\"comment\":\"订单主表\"},"
                        + "{\"name\":\"other\",\"table_type\":\"BASE TABLE\",\"comment\":null}]"));
        dbx.createContext("/api/schema/columns", ex -> {
            String query = ex.getRequestURI().getQuery();
            if (query != null && query.contains("table=orders")) {
                respond(ex, 200, """
                        [{"name":"id","data_type":"bigint","comment":"订单号",
                          "is_nullable":false,"is_primary_key":true},
                         {"name":"amount","data_type":"decimal(12,2)","comment":"金额",
                          "is_nullable":true,"is_primary_key":false}]
                        """);
            } else {
                respond(ex, 200, "[]");
            }
        });
        dbx.start();
        dbxUrl = "http://127.0.0.1:" + dbx.getAddress().getPort();
    }

    @AfterAll
    static void stopFakeDbx() {
        dbx.stop(0);
    }

    // ==================================================================

    /** 字段的名字、类型、中文名要原样带出来，列序从 1 开始。 */
    @Test
    void returnsColumnsWithTypesAndComments() throws Exception {
        long sourceId = dbxSource("browse-ok");

        JsonNode detail = MAPPER.readTree(body(mvc.perform(get("/api/meta/sync/table")
                        .param("sourceId", String.valueOf(sourceId))
                        .param("database", "db").param("schema", "public")
                        .param("table", "orders").param("connectionId", "c1"))
                .andExpect(status().isOk())));

        assertEquals("orders", detail.get("name").asText());
        JsonNode columns = detail.get("columns");
        assertEquals(2, columns.size());

        JsonNode id = columns.get(0);
        assertEquals(1, id.get("ordinal").asInt(), "列序从 1 开始，前端拿它当 row-key");
        assertEquals("id", id.get("name").asText());
        assertEquals("bigint", id.get("dataType").asText());
        assertEquals("订单号", id.get("comment").asText());
        assertFalse(id.get("nullable").asBoolean());
        assertTrue(id.get("primaryKey").asBoolean());

        assertEquals("decimal(12,2)", columns.get(1).get("dataType").asText());
        assertEquals(2, columns.get(1).get("ordinal").asInt());

        // 表注释来自 /api/schema/tables，曾经被 namesOf 丢掉过
        assertEquals("订单主表", detail.get("comment").asText(),
                "dbx 的表注释在 tables 接口里，必须带出来");
    }

    /**
     * dbx 给不出分区信息，那一项必须留 false，<b>不能编</b>。
     *
     * <p>血缘解析靠分区标记做判断，编一个出来会让整条链路的结果都偏，比留空糟得多。
     */
    @Test
    void dbxCannotProvidePartitionSoItStaysFalse() throws Exception {
        long sourceId = dbxSource("browse-gaps");

        JsonNode detail = MAPPER.readTree(body(mvc.perform(get("/api/meta/sync/table")
                        .param("sourceId", String.valueOf(sourceId))
                        .param("database", "db").param("schema", "public")
                        .param("table", "orders").param("connectionId", "c1"))
                .andExpect(status().isOk())));

        for (JsonNode c : detail.get("columns")) {
            assertFalse(c.get("partition").asBoolean(),
                    "dbx 给不出分区信息，编造出来会把血缘的分区判断带偏");
        }
    }

    /** 表名是必填的，缺了要在进远程调用之前就被挡住。 */
    @Test
    void missingTableParameterIsRejected() throws Exception {
        long sourceId = dbxSource("browse-no-table");

        mvc.perform(get("/api/meta/sync/table")
                        .param("sourceId", String.valueOf(sourceId))
                        .param("schema", "public"))
                .andExpect(status().isBadRequest());
    }

    /** 本地目录不是「远程」，从它查表结构没有意义，要给一句能看懂的话。 */
    @Test
    void localCatalogSourceIsRejectedWithAReadableMessage() throws Exception {
        Long localId = jdbc.queryForObject(
                "select id from metadata_source where tenant_id = 1 and type = 'CATALOG'", Long.class);
        assertNotNull(localId, "建库脚本应当种了一条内置的本地目录来源");

        String message = body(mvc.perform(get("/api/meta/sync/table")
                        .param("sourceId", String.valueOf(localId))
                        .param("schema", "public").param("table", "orders"))
                .andExpect(status().isBadRequest()));
        assertTrue(message.contains("本地"), "错误信息要说清为什么不行: " + message);
    }

    // ------------------------------------------------------------------

    private long dbxSource(String name) {
        jdbc.update("insert into metadata_source(tenant_id, name, type, base_url, priority, enabled) "
                + "values(1, ?, 'DBX', ?, 100, 1)", name, dbxUrl);
        Long id = jdbc.queryForObject(
                "select id from metadata_source where tenant_id = 1 and name = ?", Long.class, name);
        assertNotNull(id);
        return id;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
