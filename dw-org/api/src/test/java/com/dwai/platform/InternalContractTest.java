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

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 组织平台对外契约：<b>{@code /internal/v1/**}</b> 是其它服务唯一允许调用组织的入口。
 *
 * <p>技术方案 §2 把「模块共库、直打兄弟业务 API、拿组织主键当跨服务 code」列为<b>禁止项</b>，
 * §3.5 规定模块只能走 {@code /internal/v1} 且必须带模块令牌，§3.3 规定跨服务只认 code。
 * 这些此前全是文档约定，<b>没有一行测试守着</b> —— 本类把它们变成可执行断言。
 *
 * <p>为什么值得专门上锁：这几条一旦被破坏，症状是「本机能跑、一上多租户就串数据」，
 * 靠人工点页面几乎发现不了。特别是 §3.3 那句「解析不到不准静默落到租户 1」——
 * 静默回落的表现是完全正常的 200，只有断言能抓住它。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_internal_contract;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=contract-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class InternalContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODULE_TOKEN = "contract-module-token";

    /** 契约里用的租户，只在第一个用例里建一次。 */
    private static final String TENANT_CODE = "contract_t1";

    /**
     * 「项目成员」页要显示的那个人。
     *
     * <p>显示名<b>故意不等于</b>用户名：后端若把显示名弄丢、回落成 userId，
     * 断言必须能红 —— 两者相同的话这条用例就是假绿。
     */
    private static final String MEMBER_USERNAME = "contract_u1";
    private static final String MEMBER_DISPLAY_NAME = "契约张三";

    @Autowired
    private MockMvc mvc;

    private static String adminToken;
    private static String adminRefreshToken;
    private static String tenantId;
    /** 造出来的成员与其所在项目，只建一次，供成员名单那条用例断言。 */
    private static String seedUserId;
    private static String seedProjectId;

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken != null) return;

        Resp login = call(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"123456\"}"));
        assertEquals(200, login.status(), "管理员登录失败: " + login.body());
        JsonNode loginBody = MAPPER.readTree(login.body());
        adminToken = loginBody.path("token").asText();
        adminRefreshToken = loginBody.path("refreshToken").asText();

        // 建一个租户，作为「已登记 code」的样本。
        // adminUserId 走「指定已有账号」这条路 —— 不写它接口会要求同时给用户名和密码。
        Resp created = call(post("/api/platform/tenants")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"契约验证租户\",\"adminUserId\":\"admin\"}"));
        assertEquals(200, created.status(), "创建租户失败: " + created.body());

        JsonNode t = MAPPER.readTree(created.body());
        tenantId = t.path("id").asText();
        assertEquals(TENANT_CODE, t.path("code").asText());
        assertFalse(tenantId.isBlank(), "租户主键不该为空");
        // 主键是 t-<code> 形态：这正是「不能把主键当跨服务 code」的由来
        assertNotEquals(TENANT_CODE, tenantId, "组织主键与 code 必须是两个东西");
    }

    // ------------------------------------------------------------------
    // §3.5 模块令牌：/internal/v1 不是公开接口
    // ------------------------------------------------------------------

    @Test
    void internalEndpointsRequireModuleToken() throws Exception {
        mvc.perform(get("/internal/v1/context")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/context").header("X-Module-Token", "wrong-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void internalEndpointsAcceptModuleTokenFromHeader() throws Exception {
        mvc.perform(get("/internal/v1/context").header("X-Module-Token", MODULE_TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void heartbeatValidatesRequiredFields() throws Exception {
        mvc.perform(post("/internal/v1/registry/heartbeat")
                        .header("X-Module-Token", MODULE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/internal/v1/registry/heartbeat")
                        .header("X-Module-Token", MODULE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"product\":\"warehouse\",\"version\":\"0.2.0\",\"baseUrl\":\"http://127.0.0.1:1\"}"))
                .andExpect(status().isOk());
    }

    /**
     * 模块替用户续期/注销也必须走 {@code /internal/v1} 并带模块令牌。
     *
     * <p>这两条以前是模块直接打 {@code /api/auth/refresh}、{@code /api/auth/logout}
     * （组织的浏览器接口）—— 「直打兄弟业务 API」的典型形态，已改为内部端点。
     * 本用例防止它被改回去：如果模型侧的 OrgClient 又指回 {@code /api/**}，
     * {@link CrossServiceDesignGuardTest} 会红；如果组织侧忘了加令牌校验，这里会红。
     */
    @Test
    void moduleRefreshAndLogoutRequireModuleToken() throws Exception {
        String payload = "{\"refreshToken\":\"whatever\"}";
        assertEquals(401, call(post("/internal/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON).content(payload)).status(),
                "无限流的续期端点必须拒绝");
        assertEquals(401, call(post("/internal/v1/auth/logout")
                .contentType(MediaType.APPLICATION_JSON).content(payload)).status(),
                "无限流的注销端点必须拒绝");
    }

    /** 带模块令牌时可以替用户续期，且返回的令牌与原浏览器登录得到的形态一致。 */
    @Test
    void moduleCanRefreshOnBehalfOfUser() throws Exception {
        Resp res = call(post("/internal/v1/auth/refresh")
                .header("X-Module-Token", MODULE_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + adminRefreshToken + "\"}"));
        assertEquals(200, res.status(), "模块代续期失败: " + res.body());

        JsonNode out = MAPPER.readTree(res.body());
        assertFalse(out.path("token").asText().isBlank(), "续期必须返回新的 access token: " + res.body());
        assertFalse(out.path("refreshToken").asText().isBlank(), "续期必须轮换 refresh token");
    }

    // ------------------------------------------------------------------
    // §3.2/§3.3 跨服务只认 code，且解析不到绝不放行
    // ------------------------------------------------------------------

    /** 两个标识一个都不给：必须拒绝，不能默认放行。 */
    @Test
    void authzDeniesWhenNoTenantIdentified() throws Exception {
        JsonNode res = authz("userId=admin");
        assertFalse(res.path("allow").asBoolean(), "没给出租户却放行了: " + res);
    }

    /** code 解析不到：必须拒绝。这是「不准静默落到租户 1」的核心断言。 */
    @Test
    void authzDeniesUnsyncedTenantCode() throws Exception {
        JsonNode res = authz("userId=admin&tenantCode=definitely_not_synced");
        assertFalse(res.path("allow").asBoolean(), "未同步的 code 竟然放行了: " + res);
        assertTrue(res.path("reason").asText().contains("未同步"),
                "拒绝原因应指向「编码未同步」，便于定位，实际: " + res);
    }

    /** 已登记的 code：必须能解析出来（否则就是「看起来拒绝了一切」的假绿）。 */
    @Test
    void authzResolvesRegisteredTenantCode() throws Exception {
        JsonNode res = authz("userId=admin&tenantCode=" + TENANT_CODE);
        assertEquals(TENANT_CODE, res.path("tenantCode").asText(),
                "已登记的 code 应当被解析到并回显，实际: " + res);
        assertFalse(res.path("reason").asText().contains("未同步"),
                "已登记的 code 不该被判为未同步: " + res);
    }

    /**
     * 同时传 code 与 legacy 主键、且 code 合法时，<b>以 code 为准</b>。
     *
     * <p>legacy 主键故意填一个对不上的值 —— 如果实现先认主键，就会解析到别的租户
     * 或直接失败，这条断言会立刻变红。
     */
    @Test
    void authzPrefersCodeOverLegacyId() throws Exception {
        JsonNode res = authz("userId=admin&tenantCode=" + TENANT_CODE + "&tenantId=t-somethingelse");
        assertEquals(TENANT_CODE, res.path("tenantCode").asText(),
                "code 与 legacy 主键冲突时应以 code 为准，实际: " + res);
    }

    // ------------------------------------------------------------------
    // §3.3 请求头：X-Tenant-Code 解析不到，不得静默落到默认租户
    // ------------------------------------------------------------------

    /**
     * 带上一个查无此租户的 {@code X-Tenant-Code}，业务接口必须拒绝。
     *
     * <p>这条比 {@code authz} 那两条更贴近真实风险：它走的是本进程的请求头解析。
     * 如果实现把解析不到的 code 悄悄换成默认租户，调用方<b>会拿到 200 和别的租户的数据</b> ——
     * 那正是「跨租户串数据」的形态。
     *
     * <p>注：技术方案写的是「解析不到 → 400」，当前实现回的是 403（「无权进入该组织」）。
     * 两者都满足「不静默回落」这条底线，本用例断言的是底线，不锁具体状态码。
     */
    @Test
    void requestHeaderWithUnknownTenantCodeIsRejected() throws Exception {
        Resp res = call(get("/api/auth/me")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", "does-not-exist"));
        assertTrue(res.status() >= 400,
                "未知 X-Tenant-Code 被放行（HTTP " + res.status() + "），可能静默落到了默认租户: " + res.body());
    }

    /** 不带租户头时应当正常 —— 否则上一条可能是「所有带头的请求都被拒」造成的假绿。 */
    @Test
    void requestWithoutTenantHeaderStillWorks() throws Exception {
        Resp res = call(get("/api/auth/me").header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "不带租户头的请求应当正常: " + res.body());
    }

    /** 带上已登记的 code，同样应当正常。 */
    @Test
    void requestWithRegisteredTenantCodeWorks() throws Exception {
        Resp res = call(get("/api/auth/me")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "已登记的 code 应当被接受: " + res.body());
    }

    // ------------------------------------------------------------------
    // §3.3/§3.5 模块拉取成员名单：走内部通道，且必须带得出显示名
    // ------------------------------------------------------------------

    /**
     * 模块从组织拉成员名单，用来在「项目成员」页显示人名（仓建设 multi）。
     *
     * <p>为什么值得专门上锁：这条路拉回来的是<b>展示数据</b> —— 模块侧拿不到时会静默回落成
     * 「只显示自己」，页面照常 200。与 {@code authz} 那条「拿不到就拒绝」正好相反，
     * 坏了没有任何症状，只会看到「显示名」列退化成内部 id（本用例诞生前的实际表现）。
     * 所以断言必须落在 <b>displayName 有值且不等于 userId</b> 上，而不是「接口返回 200」。
     */
    @Test
    void membersCarryDisplayNameAndRequireModuleToken() throws Exception {
        assertEquals(401, call(get("/internal/v1/members").queryParam("tenantCode", TENANT_CODE)).status(),
                "成员名单端点必须和其余 /internal/v1 一样走模块令牌门禁");

        seedMember();
        JsonNode res = members();
        assertTrue(res.isArray(), "应返回数组: " + res);

        JsonNode mine = null;
        for (JsonNode m : res) {
            if (seedUserId.equals(m.path("userId").asText())) {
                mine = m;
                break;
            }
        }
        assertNotNull(mine, "刚建的成员不在名单里，拉取链路可能整条断了: " + res);
        assertEquals(seedProjectId, mine.path("projectId").asText(), "成员挂错了项目: " + mine);
        assertEquals("admin", mine.path("role").asText(), "建项目时自动授予的角色应当回显: " + mine);
        assertEquals(MEMBER_DISPLAY_NAME, mine.path("displayName").asText(),
                "显示名没带出来 —— 前端会退化成 userId，这正是本用例要抓的: " + mine);
        assertEquals(MEMBER_USERNAME, mine.path("username").asText(),
                "登录名没带出来 —— 成员页的「用户」列会退回显示内部主键，对人没有意义: " + mine);
    }

    // ------------------------------------------------------------------

    private JsonNode authz(String query) throws Exception {
        Resp res = call(get("/internal/v1/authz/check?" + query).header("X-Module-Token", MODULE_TOKEN));
        assertEquals(200, res.status(), "authz/check 调用失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /** 以模块身份拉成员名单。 */
    private JsonNode members() throws Exception {
        Resp res = call(get("/internal/v1/members")
                .header("X-Module-Token", MODULE_TOKEN)
                .queryParam("tenantCode", TENANT_CODE));
        assertEquals(200, res.status(), "members 调用失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /**
     * 造一个成员和他所在的项目，只造一次。
     *
     * <p>走真实接口而不是直接插表：那条成员记录是 {@code createProject} 内部
     * {@code upsertMember} 写进去的，也正是线上成员进表的唯一路径 ——
     * 绕开它，测的就不是同一件事了。
     */
    private void seedMember() throws Exception {
        if (seedUserId != null) return;

        Resp user = call(post("/api/tenants/" + tenantId + "/users")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + MEMBER_USERNAME + "\",\"displayName\":\"" + MEMBER_DISPLAY_NAME
                        + "\",\"password\":\"123456\",\"tenantRole\":\"member\"}"));
        assertEquals(200, user.status(), "建用户失败: " + user.body());
        seedUserId = MAPPER.readTree(user.body()).path("id").asText();
        assertFalse(seedUserId.isBlank(), "建用户没返回主键: " + user.body());

        Resp project = call(post("/api/tenants/" + tenantId + "/projects")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"contract_p1\",\"name\":\"契约项目\",\"adminUserId\":\"" + seedUserId + "\"}"));
        assertEquals(200, project.status(), "建项目失败: " + project.body());
        seedProjectId = MAPPER.readTree(project.body()).path("id").asText();
        assertFalse(seedProjectId.isBlank(), "建项目没返回主键: " + project.body());
    }

    /** 状态码 + 响应体。带上 body 断言，失败时能直接看到原因，不用再跑一遍。 */
    private record Resp(int status, String body) {
    }

    private Resp call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        // MockMvc 默认按 ISO-8859-1 解码，中文会变乱码，必须显式指定 UTF-8
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }
}
