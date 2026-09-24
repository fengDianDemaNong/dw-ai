package com.dwai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 平台管理员的租户管理契约。
 *
 * <p>这里守的是一个<b>高权限能力</b>：平台可以重置任何租户管理员的密码。它一旦失去门禁，
 * 任何一个租户管理员都能去重置别人租户的管理员 —— 而症状是「一切正常返回 200」，
 * 靠人工点页面发现不了，只有断言能抓住。
 *
 * <p>同理，「重置」这件事本身也要能被证明真的发生了：密码换了却没人验证过新旧密码，
 * 是这类功能最常见的假绿。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_platform_contract;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=contract-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class PlatformAdminContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 被重置的那个租户，以及它的管理员。 */
    private static final String TENANT_CODE = "platform_t1";
    private static final String ADMIN_USERNAME = "t1_admin";
    private static final String OLD_PWD = "old-pass-1";
    private static final String NEW_PWD = "new-pass-2";

    /**
     * 另一个租户的管理员，专职当「非平台用户」用来试越权。
     *
     * <p>单独建一个而不是复用 t1_admin：密码重置那条用例会把 t1_admin 的密码改掉，
     * 而 JUnit 不保证方法执行顺序 —— 共用的话这条用例会时而因登录失败而红，
     * 变成一条「看运气」的测试。
     */
    private static final String OTHER_TENANT_CODE = "platform_t2";
    private static final String OTHER_ADMIN = "t2_admin";
    private static final String OTHER_PWD = "t2-pass-1";

    @Autowired
    private MockMvc mvc;

    private static String platformToken;
    private static String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        if (platformToken != null) return;

        platformToken = login("admin", "123456");

        tenantId = createTenant(TENANT_CODE, ADMIN_USERNAME, OLD_PWD);
        createTenant(OTHER_TENANT_CODE, OTHER_ADMIN, OTHER_PWD);
    }

    /**
     * 重置密码要同时做到三件事：新密码能用、旧密码不能再用、已签发的会话作废。
     *
     * <p>第三条容易被漏掉：{@code AuthService.login} 只在<b>登录时</b> revokeAll，
     * 重置动作本身不作废的话，旧 refresh token 还能继续换 access token ——
     * 账号的实际控制权并没有收回来。
     */
    @Test
    void resetReplacesCredentialAndKicksExistingSessions() throws Exception {
        String staleRefresh = MAPPER.readTree(loginBody(ADMIN_USERNAME, OLD_PWD)).path("refreshToken").asText();
        assertFalse(staleRefresh.isBlank(), "登录没返回 refreshToken，后面的会话断言无从谈起");

        // 先证明这个会话此刻确实有效。少了这一步，「重置后 refresh 失败」也可能是
        // refresh 本来就坏了（比如路径写错），断言会变成假绿。
        Resp rotated = call(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + staleRefresh + "\"}"));
        assertEquals(200, rotated.status(), "重置前 refresh 就不通，本用例的会话断言无意义: " + rotated.body());
        String liveRefresh = MAPPER.readTree(rotated.body()).path("refreshToken").asText();

        Resp reset = call(post("/api/v1/platform/tenants/" + tenantId + "/reset-admin-password")
                .header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"" + NEW_PWD + "\"}"));
        assertEquals(200, reset.status(), "平台管理员重置失败: " + reset.body());

        assertEquals(401, call(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + liveRefresh + "\"}")).status(),
                "重置后旧会话仍能续期，等于没踢下线");

        Resp withNew = call(loginReq(ADMIN_USERNAME, NEW_PWD));
        assertEquals(200, withNew.status(), "新密码登不上，说明密码没改成: " + withNew.body());
        assertEquals(401, call(loginReq(ADMIN_USERNAME, OLD_PWD)).status(),
                "旧密码还能登录，等于没重置");
    }

    /** 非平台用户不得重置任何租户的管理员密码。 */
    @Test
    void resetRequiresPlatformAdmin() throws Exception {
        String tenantAdminToken = login(OTHER_ADMIN, OTHER_PWD);

        Resp res = call(post("/api/v1/platform/tenants/" + tenantId + "/reset-admin-password")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"whatever\"}"));
        assertEquals(403, res.status(), "租户管理员竟然能重置别人租户的管理员密码: " + res.body());
    }

    /** 空密码要挡住，不能让管理员把账号重置成一个空口令。 */
    @Test
    void resetRejectsBlankPassword() throws Exception {
        Resp res = call(post("/api/v1/platform/tenants/" + tenantId + "/reset-admin-password")
                .header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"password\":\"\"}"));
        assertEquals(400, res.status(), "空密码没有被拒绝: " + res.body());
    }

    // ------------------------------------------------------------------

    private String createTenant(String code, String adminUsername, String adminPassword) throws Exception {
        Resp created = call(post("/api/v1/platform/tenants")
                .header("Authorization", "Bearer " + platformToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"name\":\"契约租户 " + code + "\","
                        + "\"adminUsername\":\"" + adminUsername + "\",\"adminPassword\":\"" + adminPassword + "\"}"));
        assertEquals(200, created.status(), "建租户 " + code + " 失败: " + created.body());
        String id = MAPPER.readTree(created.body()).path("id").asText();
        assertFalse(id.isBlank(), "建租户没返回主键: " + created.body());
        return id;
    }

    /** 登录并返回 access token，顺带断言登录本身是成功的。 */
    private String login(String username, String password) throws Exception {
        return MAPPER.readTree(loginBody(username, password)).path("token").asText();
    }

    private String loginBody(String username, String password) throws Exception {
        Resp res = call(loginReq(username, password));
        assertEquals(200, res.status(), "登录失败(" + username + "): " + res.body());
        return res.body();
    }

    private MockHttpServletRequestBuilder loginReq(String username, String password) {
        return post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
    }

    /** 状态码 + 响应体。带上 body 断言，失败时能直接看到原因，不用再跑一遍。 */
    private record Resp(int status, String body) {
    }

    private Resp call(MockHttpServletRequestBuilder rb) throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        // MockMvc 默认按 ISO-8859-1 解码，中文会变乱码，必须显式指定 UTF-8
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }
}
