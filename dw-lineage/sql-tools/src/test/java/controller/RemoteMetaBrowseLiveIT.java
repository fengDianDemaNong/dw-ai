package controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.dwai.lineage.Main;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对着<b>真实的</b> Gravitino / dbx 跑一遍浏览与表结构查看。
 *
 * <p>{@code RemoteMetaBrowseApiTest} 用假服务器验的是我们这边的映射逻辑；
 * 这里验的是另一半 —— 真实响应的形状是不是和我们假设的一样。
 * 上一版就是在这上面栽过：一直以为「dbx 不提供表注释」，
 * 真连上去才发现注释在 {@code /api/schema/tables} 里，只是被丢了。
 *
 * <p>未提供连接信息时自动跳过，与 {@code MigrationExternalDbIT} 的做法一致：
 * <pre>
 * mvn test -Dtest=RemoteMetaBrowseLiveIT \
 *   -Dit.gravitino.url=http://localhost:8090 \
 *   -Dit.dbx.url=http://localhost:4224 -Dit.dbx.password=123456 \
 *   -Dit.dbx.connectionId=xxxx-xxxx
 * </pre>
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:remote_live_it;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        // dbx 要凭据，而凭据一律加密存储；不给密钥的话会 503（这是对的，见 CredentialCipher）
        "metadata.secret-key=live-it-secret-key-not-for-production"
})
@AutoConfigureMockMvc
class RemoteMetaBrowseLiveIT {

    static {
        persistence.TestSchema.apply(persistence.TestDataSources.h2("remote_live_it"), "h2");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    // ==================================================================
    // Gravitino
    // ==================================================================

    @Test
    void gravitinoBrowsesDownToTablesAndReadsColumns() throws Exception {
        String url = System.getProperty("it.gravitino.url");
        assumeTrue(url != null && !url.isBlank(), "未提供 Gravitino 地址，跳过");

        long sourceId = source("live-gravitino", "GRAVITINO", url, null);

        String metalake = firstOf(browse(sourceId, null));
        assertNotNull(metalake, "Gravitino 里一个 metalake 都没有，无法继续");

        String catalog = firstOf(browse(sourceId, "metalake=" + metalake));
        assertNotNull(catalog, "metalake " + metalake + " 下没有 catalog");

        String schema = or(System.getProperty("it.gravitino.schema"), firstOf(browse(sourceId,
                "metalake=" + metalake + "&catalog=" + catalog)));
        assertNotNull(schema, "catalog " + catalog + " 下没有库");

        String table = or(System.getProperty("it.gravitino.table"), firstOf(browse(sourceId,
                "metalake=" + metalake + "&catalog=" + catalog + "&schema=" + schema)));
        assumeTrue(table != null, "库 " + schema + " 下没有表，跳过字段断言");

        JsonNode detail = MAPPER.readTree(body(mvc.perform(get("/api/meta/sync/table")
                        .param("sourceId", String.valueOf(sourceId))
                        .param("metalake", metalake).param("catalog", catalog)
                        .param("schema", schema).param("table", table))
                .andExpect(status().isOk())));

        assertTrue(detail.get("columns").size() > 0,
                "真实表 " + schema + "." + table + " 应当有字段");
        JsonNode first = detail.get("columns").get(0);
        assertNotNull(first.get("name").asText(), "字段名不能为空");
        assertTrue(first.get("ordinal").asInt() >= 1, "列序从 1 开始");

        // 表注释：Gravitino 的 loadTable 带 comment，别在映射时漏掉。
        // 源端本来就没写注释时跳过，不误报
        JsonNode comment = detail.get("comment");
        assumeTrue(comment != null && !comment.isNull(), "该表在 Gravitino 里没有注释，跳过");
        assertFalse(comment.asText().isBlank(), "源端给了表注释就必须带出来");
    }

    // ==================================================================
    // dbx
    // ==================================================================

    @Test
    void dbxReadsColumnsAndTableComment() throws Exception {
        String url = System.getProperty("it.dbx.url");
        String password = System.getProperty("it.dbx.password");
        String connectionId = System.getProperty("it.dbx.connectionId");
        assumeTrue(url != null && !url.isBlank(), "未提供 dbx 地址，跳过");
        assumeTrue(connectionId != null && !connectionId.isBlank(), "未提供 dbx 连接 id，跳过");

        long sourceId = source("live-dbx", "DBX", url, password);

        String database = or(System.getProperty("it.dbx.database"),
                firstOf(browse(sourceId, "connectionId=" + connectionId)));
        assertNotNull(database, "dbx 连接下一个库都没有");

        String schema = or(System.getProperty("it.dbx.schema"), firstOf(browse(sourceId,
                "connectionId=" + connectionId + "&database=" + database)));
        assertNotNull(schema, "库 " + database + " 下没有 schema");

        String table = or(System.getProperty("it.dbx.table"), firstOf(browse(sourceId,
                "connectionId=" + connectionId + "&database=" + database + "&schema=" + schema)));
        assumeTrue(table != null, "schema " + schema + " 下没有表，跳过字段断言");

        JsonNode detail = MAPPER.readTree(body(mvc.perform(get("/api/meta/sync/table")
                        .param("sourceId", String.valueOf(sourceId))
                        .param("connectionId", connectionId)
                        .param("database", database).param("schema", schema)
                        .param("table", table))
                .andExpect(status().isOk())));

        assertTrue(detail.get("columns").size() > 0, "真实表应当有字段");
        for (JsonNode c : detail.get("columns")) {
            assertFalse(c.get("partition").asBoolean(),
                    "dbx 给不出分区信息，任何情况下都不能是 true");
        }

        // 表注释别再被丢掉：它在 /api/schema/tables 里，曾被 namesOf 一并扔了。
        // 换一套没写注释的数据时自动跳过，不误报
        JsonNode comment = detail.get("comment");
        assumeTrue(comment != null && !comment.isNull(), "该表在 dbx 里没有注释，跳过注释断言");
        assertFalse(comment.asText().isBlank(),
                "源端给了表注释就必须带出来，不能变成空串");
    }

    // ------------------------------------------------------------------

    private JsonNode browse(long sourceId, String extraQuery) throws Exception {
        var req = get("/api/meta/sync/browse").param("sourceId", String.valueOf(sourceId));
        if (extraQuery != null) {
            for (String pair : extraQuery.split("&")) {
                String[] kv = pair.split("=", 2);
                req = req.param(kv[0], kv.length > 1 ? kv[1] : "");
            }
        }
        return MAPPER.readTree(body(mvc.perform(req).andExpect(status().isOk())));
    }

    /** 优先用显式指定的坐标，便于对着已知有注释的表验证。 */
    private static String or(String override, String fallback) {
        return (override == null || override.isBlank()) ? fallback : override;
    }

    private static String firstOf(JsonNode array) {
        return array != null && array.isArray() && array.size() > 0 ? array.get(0).asText() : null;
    }

    /**
     * 建一条元数据服务配置。
     *
     * <p>走 REST 而不是直接插库：凭据要经过 {@code CredentialCipher} 加密才能被后续解密使用，
     * 手工插明文会在用的时候报「凭据解密失败」。顺带也把创建接口本身覆盖到了。
     */
    private long source(String name, String type, String baseUrl, String credential) throws Exception {
        jdbc.update("delete from metadata_source where tenant_id = 1 and name = ?", name);
        String payload = MAPPER.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("name", name);
            put("type", type);
            put("baseUrl", baseUrl);
            if (credential != null) {
                put("credential", credential);
            }
            put("priority", 100);
            put("enabled", true);
        }});
        JsonNode created = MAPPER.readTree(body(mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/metadata-sources")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())));
        return created.get("id").asLong();
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
