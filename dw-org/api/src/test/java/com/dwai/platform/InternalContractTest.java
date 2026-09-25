package com.dwai.platform;

import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
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

    /** 第二个租户 + 它的同码项目，只在需要跨租户样本时建一次。 */
    private static final String SECOND_TENANT_CODE = "contract_t2";
    private static String secondTenantId;
    private static String secondProjectId;

    /**
     * 直接改许可行用。
     *
     * <p>「组织本地没有许可行」这个状态没有接口能造出来（{@code createTenant} 一定会
     * 建行），而它正是 {@code modules} 该回 null 的唯一场景 —— 只能直接动表。
     */
    @Autowired
    private TenantLicenseMapper licenseMapper;

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
    // 项目同步由推送改拉取：模块按 (tenantCode, projectCode) 拉
    // ------------------------------------------------------------------

    /**
     * 拉取端点同样在模块令牌门禁之后。
     *
     * <p>这条路径返回的是项目 + 该租户的许可，比 {@code context} 敏感得多，
     * 漏了门禁等于把全平台的项目清单开放出去。
     */
    @Test
    void pullProjectByCodeRequiresModuleToken() throws Exception {
        assertEquals(401, call(get("/internal/v1/projects/by-code/contract_p1")).status());
    }

    /**
     * <b>不带 tenantCode 必须 400</b>，不许回落 {@code TenantContext}。
     *
     * <p>这是本批改动里最要紧的一条断言。回落的后路是存在的：{@code ProjectService}
     * 里 {@code resolveTenantId} 就会在 tenantCode 为空时用 {@code TenantContext.tenantId()}。
     * 但 {@code /internal/v1/**} 上没有 JWT，{@code TenantContext} 恒为空 —— 更要命的是，
     * 组织侧的租户归属校验是这个形状：{@code if (tid != null && ...)}，tid 为空时
     * <b>整块跳过</b>。两者叠加，一个「忘了带 tenantCode」的调用就能读到任意租户的
     * 同码项目。返回 200 即视为漏洞。
     */
    @Test
    void pullProjectByCodeRejectsMissingTenantCode() throws Exception {
        Resp res = call(get("/internal/v1/projects/by-code/contract_p1")
                .header("X-Module-Token", MODULE_TOKEN));
        assertEquals(400, res.status(), "缺 tenantCode 竟然没有拒绝（回落就等于跨租户读）: " + res.body());
    }

    /** 项目不存在回 404：调用方要据此区分「组织说没有」与「组织不可用」。 */
    @Test
    void pullProjectByCodeReturns404ForUnknownProject() throws Exception {
        Resp res = call(get("/internal/v1/projects/by-code/no_such_project")
                .header("X-Module-Token", MODULE_TOKEN)
                .queryParam("tenantCode", TENANT_CODE));
        assertEquals(404, res.status(), "查无此项目应当是 404 而不是 200/400: " + res.body());
    }

    /** 正常拉取：字段齐全，且带 id（模块侧拿它当 preferredId，缺了会新旧 id 不一致）。 */
    @Test
    void pullProjectByCodeReturnsProjectWithLicense() throws Exception {
        seedMember();
        JsonNode res = pull("contract_p1", TENANT_CODE);

        assertEquals(seedProjectId, res.path("id").asText(), "必须带 id: " + res);
        assertEquals("contract_p1", res.path("code").asText());
        assertEquals("契约项目", res.path("name").asText());
        assertEquals(TENANT_CODE, res.path("tenantCode").asText());
        assertEquals("契约验证租户", res.path("tenantName").asText(), "租户名要带上，模块侧直接用，不必再查一次");
        assertTrue(res.path("modules").isArray(), "modules 应当是数组: " + res);
        assertTrue(res.path("aiCaps").isArray(), "aiCaps 应当是数组: " + res);
    }

    /**
     * 同一个项目编码存在于两个租户时，拉取必须按租户隔离。
     *
     * <p>这是 R9 的正面反例：{@code code} 单独并不唯一（唯一约束是
     * {@code (tenant_id, code)}）。若实现只按 code 查、或让 tenantCode 变成一个
     * 可选的「过滤条件」，这条会立刻变红 —— 而线上表现是「A 租户读到了 B 租户的项目」。
     */
    @Test
    void pullProjectByCodeIsScopedToTenant() throws Exception {
        seedSecondTenant();
        JsonNode mine = pull("contract_p1", TENANT_CODE);
        JsonNode theirs = pull("contract_p1", SECOND_TENANT_CODE);

        assertEquals(seedProjectId, mine.path("id").asText());
        assertEquals(secondProjectId, theirs.path("id").asText(),
                "两个租户用同一个项目编码，拉回来的必须是各自那一个: " + theirs);
        assertNotEquals(seedProjectId, theirs.path("id").asText(), "串到别的租户的项目了: " + theirs);
        assertEquals(SECOND_TENANT_CODE, theirs.path("tenantCode").asText());
    }

    /**
     * 组织本地没有许可行时 {@code modules} 必须是 <b>null</b>，不是空数组。
     *
     * <p>两者的语义在模块侧完全不同：null = 「组织没这项信息，别动本地的」，
     * 空数组 = 「组织说这个租户一项都没开通」。混成一个，模块要么把许可抹空、
     * 要么永远不跟随组织的变更 —— 两种都是线上事故。
     *
     * <p><b>断言写成「读出来是空」而不是 {@code isNull()}</b>：组织全局配了
     * {@code spring.jackson.default-property-inclusion: non_null}（见 application.yml），
     * 于是这条路径上 null 字段在 JSON 里是<b>键不存在</b>，不是 {@code "modules": null}。
     * 收方 {@code Map.get} 两种形态都得到 null，走的是同一个「别动本地」分支 ——
     * 契约要锁的是这个语义，不是字面。空数组那一侧另有 {@link
     * #pullProjectByCodeSendsEmptyArrayWhenLicenseIsEmpty} 锁住，所以「键不存在」
     * 不会被一个漏写字段的实现冒充过去（漏写时空数组那条仍会红）。
     */
    @Test
    void pullProjectByCodeSendsNullWhenTenantHasNoLicenseRow() throws Exception {
        seedSecondTenant();
        licenseMapper.deleteById(secondTenantId);

        JsonNode res = pull("contract_p1", SECOND_TENANT_CODE);
        assertTrue(nullOrMissing(res, "modules"), "没有许可行时应当读作 null: " + res);
        assertTrue(nullOrMissing(res, "aiCaps"), "没有许可行时应当读作 null: " + res);
    }

    /** 字段缺失与显式 null 对收方等价，见 {@link #pullProjectByCodeSendsNullWhenTenantHasNoLicenseRow}。 */
    private static boolean nullOrMissing(JsonNode res, String field) {
        JsonNode v = res.path(field);
        return v.isNull() || v.isMissingNode();
    }

    /** 反过来：许可行在、且一项都没开通时必须是空数组，不能也塌成 null。 */
    @Test
    void pullProjectByCodeSendsEmptyArrayWhenLicenseIsEmpty() throws Exception {
        seedSecondTenant();
        TenantLicenseEntity lic = licenseMapper.selectById(secondTenantId);
        if (lic == null) {
            lic = new TenantLicenseEntity();
            lic.setTenantId(secondTenantId);
            lic.setModules("[]");
            lic.setAiCaps("[]");
            licenseMapper.insert(lic);
        } else {
            lic.setModules("[]");
            lic.setAiCaps("[]");
            licenseMapper.updateById(lic);
        }

        JsonNode res = pull("contract_p1", SECOND_TENANT_CODE);
        assertTrue(res.path("modules").isArray() && res.path("modules").isEmpty(),
                "许可为空应当是 [] 而不是 null: " + res);
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

    /** 以模块身份按 (租户编码, 项目编码) 拉一个项目。 */
    private JsonNode pull(String projectCode, String tenantCode) throws Exception {
        Resp res = call(get("/internal/v1/projects/by-code/" + projectCode)
                .header("X-Module-Token", MODULE_TOKEN)
                .queryParam("tenantCode", tenantCode));
        assertEquals(200, res.status(), "拉取项目失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /**
     * 第二个租户，里面放一个<b>与第一个租户同码</b>的项目（{@code contract_p1}）。
     *
     * <p>同码是刻意的：跨租户串数据只有在编码撞车时才暴露，编码互不相同时
     * 「只按 code 查」和「按 (tenant, code) 查」的结果一模一样 —— 那样的样本测不出东西。
     */
    private void seedSecondTenant() throws Exception {
        if (secondProjectId != null) return;

        Resp created = call(post("/api/platform/tenants")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + SECOND_TENANT_CODE + "\",\"name\":\"契约验证租户二号\",\"adminUserId\":\"admin\"}"));
        assertEquals(200, created.status(), "建第二个租户失败: " + created.body());
        secondTenantId = MAPPER.readTree(created.body()).path("id").asText();

        Resp project = call(post("/api/tenants/" + secondTenantId + "/projects")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", SECOND_TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"contract_p1\",\"name\":\"另一个租户的同码项目\",\"adminUserId\":\"admin\"}"));
        assertEquals(200, project.status(), "在第二个租户里建项目失败: " + project.body());
        secondProjectId = MAPPER.readTree(project.body()).path("id").asText();
        assertNotEquals(seedProjectId, secondProjectId, "两个租户的同码项目不该是同一行");
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
