package com.dwai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜单候选接口（`GET /api/v1/platform/nav-candidates`）对每种失败都要给出能排查的信息。
 *
 * <h2>为什么这条接口值得单独一层测试</h2>
 *
 * 它是「各服务的菜单从哪来」这条链路上唯一会<b>跨进程失败</b>的一环，而这里的失败
 * 长得都一样：接口 200、某个产品 `ok:false`、侧栏里没有它的候选。真正要区分的是
 * <b>为什么</b> —— 管理员能拿到的只有 `error` 那句话，所以那句话里必须带上
 * <b>实际请求的地址</b>。把页面地址填成后端端口是这套配置里最常见的手误，
 * 而那个地址除了这里之外没有任何地方会显示出来。
 *
 * <h2>失败不落缓存，这里也要钉住</h2>
 *
 * {@link #failureIsNotCachedSoRetryAfterFixingWorks()} 走的是管理员的真实动作：
 * 拉取失败 → 去服务注册改地址 → 再拉一次。若失败被缓存，「改完再点」会在一分钟内
 * 一直看到同一条旧错误，看起来像改错了地方。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_menu_candidates;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=cand-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class MenuCandidateContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 合法清单：产品码与 `product=warehouse` 对得上。 */
    private static final String GOOD = """
            {"product":"warehouse","version":"0.1.3","menus":[
              {"id":"warehouse:project:/model","scope":"project","group":"","path":"/model",
               "label":"概况","icon":"DashboardOutlined","perm":"","sort":10},
              {"id":"warehouse:project:/model/members","scope":"project","group":"","path":"/model/members",
               "label":"项目成员","icon":"TeamOutlined","perm":"iam:member","sort":20}]}
            """;

    /** 换个自报产品码的同一份清单 —— 用来分别伺候「自报不符」与「改对后重试成功」两种用例。 */
    private static String forProduct(String code) {
        return GOOD.replace("\"warehouse\"", "\"" + code + "\"");
    }

    /** 自报是另一个产品 —— 地址填串了的典型形态。 */
    private static final String WRONG_PRODUCT = forProduct("metadata");

    /** 前端没构建过时这个地址上常见的东西。 */
    private static final String HTML = "<!doctype html><html><body>vite dev</body></html>";

    private static HttpServer server;
    private static String base;

    @Autowired
    private MockMvc mvc;

    private static String adminToken;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok/menu.json", respond(200, GOOD));
        // 自报就是 serve 的一份 —— 「改对地址后再拉」要落到一个真正属于它的地址上
        server.createContext("/serve/menu.json", respond(200, forProduct("serve")));
        server.createContext("/wrong/menu.json", respond(200, WRONG_PRODUCT));
        // 故意也回 Content-Type: application/json —— 证明我们看的是内容本身，
        // 而不是信了这个头。真实世界里前端没构建过时这里就是一段 HTML。
        server.createContext("/html/menu.json", respond(200, HTML));
        // 没有任何 context 匹配的路径由 HttpServer 自己回 404
        server.setExecutor(null);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void registerProducts() throws Exception {
        if (adminToken == null) adminToken = login();
        // 每个产品指着那个桩服务器的一个路径，因而各自的失败形态互不干扰。
        // 端口每次都是新的，所以要逐个重新登记（registry.put 是覆盖语义）。
        register("warehouse", base + "/ok");
        register("quality", base + "/wrong");
        register("serve", base + "/html");
        register("metadata", base + "/nonexistent");
    }

    /** 成功的产品带上解析好的候选，其余照常各报各的错 —— 一个坏不影响另一个好。 */
    @Test
    void oneBrokenProductDoesNotHideTheGoodOne() throws Exception {
        JsonNode body = candidates();

        JsonNode warehouse = row(body, "warehouse");
        assertTrue(warehouse.path("ok").asBoolean(),
                "正常清单应当解析成功: " + warehouse);
        assertEquals(2, warehouse.path("menus").size(), warehouse.toString());
        assertEquals("/model/members", warehouse.path("menus").get(1).path("path").asText());
        assertEquals("iam:member", warehouse.path("menus").get(1).path("perm").asText(),
                "权限词要原样带出来，壳靠它决定置灰: " + warehouse);
        assertEquals("项目成员", warehouse.path("menus").get(1).path("label").asText());

        assertFalse(row(body, "metadata").path("ok").asBoolean(), "404 的产品不该是 ok");
        assertFalse(row(body, "serve").path("ok").asBoolean(), "拿到 HTML 的产品不该是 ok");
        assertFalse(row(body, "quality").path("ok").asBoolean(), "自报产品码不符的不该是 ok");
    }

    /** 拉不到时 `error` 必须带上**实际请求的地址** —— 那是排查时唯一能对的东西。 */
    @Test
    void errorCarriesTheAddressActuallyRequested() throws Exception {
        JsonNode body = candidates();

        // 每个失败产品的页面地址指向桩服务器上的一个路径，请求地址即为 前端地址 + /menu.json
        for (String[] pair : new String[][]{
                {"metadata", "nonexistent"}, {"serve", "html"}, {"quality", "wrong"}}) {
            String code = pair[0];
            JsonNode failed = row(body, code);

            assertEquals(base + "/" + pair[1] + "/menu.json", failed.path("url").asText(),
                    code + " 没有回显实际请求地址: " + failed);
            assertTrue(failed.path("error").asText().contains("127.0.0.1"),
                    code + " 的报错里没有地址，管理员看不出自己填的是哪个: " + failed);
            assertTrue(failed.path("menus").isArray() && failed.path("menus").isEmpty(),
                    "失败的产品应当给空候选而不是缺字段: " + failed);
        }
    }

    /**
     * 自报产品码不符要说清「是谁的」——
     * 否则管理员只知道拉不到，不知道这个地址上其实是另一个产品。
     */
    @Test
    void productCodeMismatchSaysWhoseItIs() throws Exception {
        String error = row(candidates(), "quality").path("error").asText();
        assertTrue(error.contains("metadata"),
                "报错里要出现地址上那个服务真实的自报产品码: " + error);
        assertTrue(error.contains("quality"),
                "报错里要出现我们以为在拉的产品码: " + error);
    }

    /**
     * 失败不落缓存：改对地址后<b>立刻</b>再拉一次就要成功。
     *
     * <p>这是管理员的真实动作顺序，也是「失败缓存」唯一会咬人的地方 ——
     * 被缓存住的话，「改完再点」在一分钟内一直返回同一条旧错误。
     */
    @Test
    void failureIsNotCachedSoRetryAfterFixingWorks() throws Exception {
        // 先指到一个没人监听的端口（先占住再释放，拿到一个确定空闲的端口号）
        int deadPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            deadPort = probe.getLocalPort();
        }
        register("serve", "http://127.0.0.1:" + deadPort);

        JsonNode first = row(candidates(), "serve");
        assertFalse(first.path("ok").asBoolean(), "连不上的地址不该是 ok: " + first);
        assertTrue(first.path("error").asText().contains(String.valueOf(deadPort)),
                "连不上时的报错没带上地址: " + first);

        // 管理员去「服务注册」改对了地址，再拉一次 —— 必须真的重新发请求
        register("serve", base + "/serve");

        JsonNode second = row(candidates(), "serve");
        assertTrue(second.path("ok").asBoolean(),
                "改对地址后再拉仍然是失败的 —— 失败被缓存住了，管理员会以为改错了地方: " + second);
    }

    /** 空白名单（一个产品都没登记）时返回空数组而不是报错。 */
    @Test
    void noRegisteredProductYieldsEmptyListNotError() throws Exception {
        for (String product : new String[]{"warehouse", "quality", "serve", "metadata"}) {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .delete("/api/v1/platform/services/" + product)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        }
        JsonNode body = candidates();
        assertTrue(body.path("products").isArray() && body.path("products").isEmpty(),
                "没有已登记产品时应当是空数组: " + body);
    }

    /** 候选是平台配置动作，普通成员不该能读。 */
    @Test
    void candidatesRequirePlatformAdmin() throws Exception {
        var res = mvc.perform(get("/api/v1/platform/nav-candidates")
                        .header("Authorization", "Bearer " + memberToken()))
                .andReturn().getResponse();
        assertEquals(403, res.getStatus(),
                "普通成员读到了菜单候选 —— 这条路能探出各服务的内网地址");
    }

    // ------------------------------------------------------------------

    /** 一个非管理员令牌，用来验门禁。 */
    private String memberToken() throws Exception {
        String tenantId = MAPPER.readTree(mvc.perform(post("/api/platform/tenants")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"cand_t1\",\"name\":\"候选门禁租户\",\"adminUserId\":\"admin\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("id").asText();

        mvc.perform(post("/api/tenants/" + tenantId + "/users")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", "cand_t1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"cand_member\",\"displayName\":\"候选成员\","
                        + "\"password\":\"123456\",\"tenantRole\":\"member\"}"));

        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cand_member\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).path("token").asText();
    }

    private JsonNode candidates() throws Exception {
        var res = mvc.perform(get("/api/v1/platform/nav-candidates")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse();
        return MAPPER.readTree(res.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 取某个产品那一份 —— 接口逐产品报告，所以断言也要逐产品取，别在整份 body 上找字段。 */
    private static JsonNode row(JsonNode body, String code) {
        for (JsonNode r : body.path("products")) {
            if (code.equals(r.path("product").asText())) return r;
        }
        throw new AssertionError("响应里没有产品 " + code + ": " + body);
    }

    private String login() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body).path("token").asText();
    }

    private void register(String product, String frontendUrl) throws Exception {
        mvc.perform(post("/api/v1/platform/services")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"" + product + "\",\"frontendUrl\":\"" + frontendUrl + "\"}"))
                .andExpect(status().isOk());
    }

    private static com.sun.net.httpserver.HttpHandler respond(int status, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException ignored) {
                // 客户端提前断开（超时用例），无所谓
            }
        };
    }
}
