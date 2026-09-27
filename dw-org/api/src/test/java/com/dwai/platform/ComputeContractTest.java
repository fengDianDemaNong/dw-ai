package com.dwai.platform;

import com.dwai.platform.auth.LlmCrypto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 租户自己的计算资源（{@code tenant_compute}，V27）。
 *
 * <h2>这一层要钉住的三件事</h2>
 *
 * <ol>
 *   <li><b>Token 永不回明文</b>。这里用两条独立的证据：库里的密文<b>解不回原文以外的任何东西</b>
 *       （解回来必须逐字等于原文，说明确实加密了而不是存了又忘了钥），以及
 *       <b>整个响应体里不含明文串</b> —— 后者才防得住「某天有人给 DTO 加了个字段」。</li>
 *   <li><b>空 Token = 保持原值</b>。页面上那格永远显示不出明文，用户不碰它就不该被清掉；
 *       而「清掉」的症状是保存后测试连接一直失败，页面上看不出任何异常。</li>
 *   <li><b>结论只对当时的地址与 Token 有效</b>。改了配置就必须把上一次的「已测通」打回未测 ——
 *       否则会出现「绿标对着一个从没测过的地址」。</li>
 * </ol>
 *
 * <p>探活这一条**打的是本地回环上的保留端口**（必然被拒），不指向任何真实服务：
 * 这里要验的是「连不上是一个正常结论」，不是「能不能连上某台 DS」。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_compute;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=compute-module-token",
        "dwai.security.llm-secret=compute-test-secret",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class ComputeContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_CODE = "cmp_t1";
    private static final String SECRET = "ds-token-please-encrypt-me";
    private static final String BASE_URL = "http://10.20.0.15:12345/dolphinscheduler";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private LlmCrypto crypto;

    private static String adminToken;
    private static String tenantAdminToken;
    private static String memberToken;
    private static String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken == null) {
            adminToken = login("admin", "123456");
            Resp created = call(post("/api/platform/tenants")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"计算资源验证租户\","
                            + "\"adminUserId\":\"admin\"}"));
            assertEquals(200, created.status(), "创建租户失败: " + created.body());
            tenantId = MAPPER.readTree(created.body()).path("id").asText();
            createUser("cmp_admin", "admin");
            createUser("cmp_member", "member");
            tenantAdminToken = login("cmp_admin", "123456");
            memberToken = login("cmp_member", "123456");
        }
        new JdbcTemplate(dataSource).update("delete from tenant_compute where tenant_id = ?", tenantId);
    }

    // ------------------------------------------------------------------
    // 读：没配过 = 全默认
    // ------------------------------------------------------------------

    /** 从没配过的租户读到的是一份合理的空态，而不是 404 或 null。 */
    @Test
    void unconfiguredTenantReadsDefaults() throws Exception {
        JsonNode c = getCompute();
        assertFalse(c.path("schedulerEnabled").asBoolean(), c.toString());
        assertEquals("", c.path("schedulerBaseUrl").asText(), c.toString());
        assertFalse(c.path("hasToken").asBoolean(), "没存过 Token: " + c);
        assertEquals("unconfigured", c.path("schedulerStatus").asText(), c.toString());
        assertEquals("", c.path("schedulerNote").asText(), c.toString());
        assertEquals(4, c.path("engines").size(), "四个引擎都要有行（关着也是行）: " + c);
        for (JsonNode e : c.path("engines")) {
            assertFalse(e.path("enabled").asBoolean(), e.toString());
        }
    }

    // ------------------------------------------------------------------
    // Token
    // ------------------------------------------------------------------

    /**
     * <b>本类最重要的一条。</b>Token 加密落库、且没有任何回传路径带明文。
     *
     * <p>「解回来等于原文」与「响应体里没有原文」是两件事：前者证明它真的被加密了
     * （而不是明文塞进 BLOB），后者才是防泄漏的那道门 —— 将来有人给 DTO 加个字段就会红在这里。
     */
    @Test
    void tokenIsEncryptedAndNeverEchoed() throws Exception {
        Resp saved = putCompute("{\"schedulerEnabled\":true,\"schedulerBaseUrl\":\"" + BASE_URL + "\","
                + "\"schedulerToken\":\"" + SECRET + "\"}");
        assertEquals(200, saved.status(), "保存失败: " + saved.body());
        assertFalse(saved.body().contains(SECRET), "保存的响应里不能带明文 Token: " + saved.body());
        assertTrue(MAPPER.readTree(saved.body()).path("hasToken").asBoolean(), saved.body());

        byte[] stored = storedToken();
        assertNotEquals(SECRET, new String(stored, StandardCharsets.UTF_8),
                "库里存的不能是明文");
        assertEquals(SECRET, crypto.decrypt(stored),
                "解回来必须是原文 —— 否则「加密了」只是存进去一串没人能读的东西");

        Resp read = call(get("/api/v1/tenants/" + tenantId + "/compute")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, read.status(), read.body());
        assertFalse(read.body().contains(SECRET), "读回来的响应里也不能带明文 Token: " + read.body());
        assertTrue(MAPPER.readTree(read.body()).path("hasToken").asBoolean(), "只回「有没有」: " + read.body());
    }

    /** 空 Token = 保持原值（页面上那格永远显示不出明文，不碰它就不该被清掉）。 */
    @Test
    void blankTokenKeepsTheStoredOne() throws Exception {
        putCompute("{\"schedulerBaseUrl\":\"" + BASE_URL + "\",\"schedulerToken\":\"" + SECRET + "\"}");
        Resp again = putCompute("{\"schedulerBaseUrl\":\"" + BASE_URL + "\",\"schedulerToken\":\"\"}");
        assertEquals(200, again.status(), again.body());
        assertTrue(MAPPER.readTree(again.body()).path("hasToken").asBoolean(),
                "留空保存不该把已存的 Token 清掉: " + again.body());

        assertEquals(SECRET, crypto.decrypt(storedToken()), "存着的还是原来那个");
    }

    /** 引擎启停落库，且**只存合法的那几个** —— 前端传来不认识的名字时不该写进去。 */
    @Test
    void engineTogglesAreStoredAndUnknownKindsDropped() throws Exception {
        putCompute("{\"engines\":[{\"kind\":\"hive\",\"enabled\":true},"
                + "{\"kind\":\"doris\",\"enabled\":true},{\"kind\":\"no_such_engine\",\"enabled\":true}]}");

        JsonNode c = getCompute();
        assertEquals(4, c.path("engines").size(), "表里永远四行: " + c);
        assertEquals(true, enabledOf(c, "hive"), c.toString());
        assertEquals(true, enabledOf(c, "doris"), c.toString());
        assertEquals(false, enabledOf(c, "spark"), "没提的引擎是关的: " + c);
        for (JsonNode e : c.path("engines")) {
            assertNotEquals("no_such_engine", e.path("kind").asText(), "不认识的引擎不该落库: " + c);
        }

        // 再次保存只提 hive：没提的回到关闭（全量覆盖，不是增量）
        putCompute("{\"engines\":[{\"kind\":\"hive\",\"enabled\":true}]}");
        JsonNode after = getCompute();
        assertEquals(true, enabledOf(after, "hive"), after.toString());
        assertEquals(false, enabledOf(after, "doris"), "全量覆盖：没提的应当回到关闭: " + after);
    }

    // ------------------------------------------------------------------
    // 探活
    // ------------------------------------------------------------------

    /**
     * 连不上是一个<b>正常结论</b>，不是 5xx：管理员点这个按钮，失败原因要显示在页面上，
     * 而不是变成一条「平台坏了」的错误提示 + 一行只在日志里的堆栈。
     */
    @Test
    void unreachableSchedulerIsAConclusiveResultNotAServerError() throws Exception {
        // 回环上的保留端口：必然被拒，且不指向任何真实服务
        putCompute("{\"schedulerEnabled\":true,\"schedulerBaseUrl\":\"http://127.0.0.1:1/ds\","
                + "\"schedulerToken\":\"x\"}");

        Resp res = call(post("/api/v1/tenants/" + tenantId + "/compute/test")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "连不上不该回 5xx: " + res.body());
        JsonNode c = MAPPER.readTree(res.body());
        assertEquals("error", c.path("schedulerStatus").asText(), c.toString());
        assertFalse(c.path("schedulerNote").asText().isEmpty(), "要说明为什么没通: " + c);
        assertFalse(c.path("schedulerTestedAt").asText().isEmpty(), "要记下什么时候测的: " + c);
        assertFalse(res.body().contains(SECRET), "结论里也不能带 Token: " + res.body());
    }

    /** 还没填地址就点测试：400 并说清楚要先填 —— 不是拿空地址去拼一串 URL。 */
    @Test
    void testingWithoutBaseUrlIsRejected() throws Exception {
        Resp res = call(post("/api/v1/tenants/" + tenantId + "/compute/test")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(400, res.status(), "没填地址应当被拒: " + res.body());
    }

    // ------------------------------------------------------------------
    // 结论只对当时的配置有效
    // ------------------------------------------------------------------

    /**
     * 改了地址，上一次的「已测通」必须作废。
     *
     * <p>不作废的症状是「绿标对着一个从没测过的地址」—— 而用户刚改完地址，最信的恰恰是那个绿标。
     * 这里直接把库里的状态改成 {@code ok} 来造场景，不走真实探活（那需要一个真 DS）。
     */
    @Test
    void changingBaseUrlInvalidatesThePreviousResult() throws Exception {
        putCompute("{\"schedulerBaseUrl\":\"" + BASE_URL + "\",\"schedulerToken\":\"" + SECRET + "\"}");
        markTestedOk();
        assertEquals("ok", getCompute().path("schedulerStatus").asText(), "先确认造出了「已测通」");

        putCompute("{\"schedulerBaseUrl\":\"http://10.20.0.99:12345/dolphinscheduler\"}");
        JsonNode after = getCompute();
        assertEquals("unconfigured", after.path("schedulerStatus").asText(),
                "换了地址，上一次的结论就不作数了: " + after);
        assertTrue(after.path("hasToken").asBoolean(), "只换地址不该丢掉 Token: " + after);
    }

    /** 换了 Token 同理 —— 上一次「通了」是对旧 Token 说的。 */
    @Test
    void changingTokenInvalidatesThePreviousResult() throws Exception {
        putCompute("{\"schedulerBaseUrl\":\"" + BASE_URL + "\",\"schedulerToken\":\"" + SECRET + "\"}");
        markTestedOk();

        putCompute("{\"schedulerToken\":\"a-brand-new-token\"}");
        assertEquals("unconfigured", getCompute().path("schedulerStatus").asText(),
                "换了 Token，上一次的结论就不作数了: " + getCompute());
    }

    /**
     * 反过来：地址<b>一字不改</b>地重复保存不该把结论抹掉。
     *
     * <p>「保存」是这一页最常用的动作，每次保存都把绿标打回未测，用户会以为是自己弄坏了什么。
     *
     * <p>注意这里<b>不</b>把「末尾多一个斜杠」当成同一个地址：库里存的是用户填的原文
     * （页面上回显的也就是它），归一发生在探活拼 URL 时（见 {@code probeUrl}）。
     * 若改成归一化比较，用户填 {@code A/} 会被存成 {@code A}，他刚填完再一看框里变了样。
     */
    @Test
    void resavingTheSameBaseUrlKeepsTheResult() throws Exception {
        putCompute("{\"schedulerBaseUrl\":\"" + BASE_URL + "\",\"schedulerToken\":\"" + SECRET + "\"}");
        markTestedOk();

        // 地址原样 + Token 留空：两处都不该触发作废
        putCompute("{\"schedulerBaseUrl\":\"" + BASE_URL + "\",\"schedulerToken\":\"\"}");
        JsonNode after = getCompute();
        assertEquals("ok", after.path("schedulerStatus").asText(),
                "地址没变时重复保存不该作废结论: " + after);
        assertTrue(after.path("hasToken").asBoolean(), after.toString());
    }

    /** 非管理员读不到也改不了 —— 这里存的是组织的凭据。 */
    @Test
    void nonAdminCannotReadOrWrite() throws Exception {
        assertEquals(403, call(get("/api/v1/tenants/" + tenantId + "/compute")
                .header("Authorization", "Bearer " + memberToken)
                .header("X-Tenant-Code", TENANT_CODE)).status(),
                "普通成员不该能读计算资源");
        assertEquals(403, call(put("/api/v1/tenants/" + tenantId + "/compute")
                .header("Authorization", "Bearer " + memberToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"schedulerEnabled\":true}")).status(),
                "普通成员不该能改计算资源");
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private Resp putCompute(String json) throws Exception {
        return call(put("/api/v1/tenants/" + tenantId + "/compute")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private JsonNode getCompute() throws Exception {
        Resp res = call(get("/api/v1/tenants/" + tenantId + "/compute")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "读计算资源失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /** 造「已测通」的场景：直接改库，不走真实探活（那需要一个真的 DS）。 */
    private void markTestedOk() {
        new JdbcTemplate(dataSource).update(
                "update tenant_compute set scheduler_status = 'ok', scheduler_note = '连接正常'"
                        + " where tenant_id = ?", tenantId);
    }

    private static boolean enabledOf(JsonNode c, String kind) {
        for (JsonNode e : c.path("engines")) {
            if (kind.equals(e.path("kind").asText())) return e.path("enabled").asBoolean();
        }
        throw new AssertionError("引擎列表里没有 " + kind + ": " + c);
    }

    /** 库里那一列是 BLOB，按 {@code byte[]} 取 —— 取成 String 会在 H2 下拿到一段没法还原的表示。 */
    private byte[] storedToken() {
        return new JdbcTemplate(dataSource).queryForObject(
                "select scheduler_token_enc from tenant_compute where tenant_id = ?",
                byte[].class, tenantId);
    }

    private void createUser(String username, String tenantRole) throws Exception {
        Resp res = call(post("/api/tenants/" + tenantId + "/users")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"displayName\":\"" + username + "\","
                        + "\"password\":\"123456\",\"tenantRole\":\"" + tenantRole + "\"}"));
        assertEquals(200, res.status(), "建用户失败(" + username + "): " + res.body());
    }

    private String login(String username, String password) throws Exception {
        Resp res = call(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, res.status(), "登录失败(" + username + "): " + res.body());
        return MAPPER.readTree(res.body()).path("token").asText();
    }

    private record Resp(int status, String body) {
    }

    private Resp call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }
}
