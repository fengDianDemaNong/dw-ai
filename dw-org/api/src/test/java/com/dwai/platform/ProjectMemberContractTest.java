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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 项目成员端点（{@code /api/v1/projects/{pid}/members}）的契约。
 *
 * <h2>为什么单独一个契约测试</h2>
 *
 * <p>这三个端点此前在 dw-org 里<b>根本没有出口</b> —— {@code ProjectService} 的
 * {@code listMembers/putMember/deleteMember} 写好了却没有任何 Controller 调用，
 * 而 dw-org 前端早就在打这个路径（{@code api.putMember}），是一处<b>静默 404</b>。
 * 本轮把它们暴露出来，最该钉住的不是「能返回 200」，而是下面这几条：
 *
 * <ol>
 *   <li>{@link #listMembersCarriesDisplayName()} —— <b>显示名非空</b>。成员表只存
 *       {@code userId}，不带名字的 DTO 会让前端的「显示名」列退化成
 *       {@code u-1790068559315} 这样的内部主键。这正是最容易漏的一处：
 *       接口 200、结构正确、字段齐全，只有值是 null。</li>
 *   <li>{@link #crossTenantProjectIsForbidden()} —— <b>路径里不带 tenantId 也安全</b>。
 *       本方案的路径选择（{@code /projects/...} 而不是 {@code /tenants/{id}/projects/...}）
 *       全部论据就在这里：{@code AccessService.requireProject} 按
 *       {@code TenantContext} 做归属校验。这条红了就说明那个论据不成立。</li>
 *   <li>{@link #deleteRemovesEveryProductRow()} —— 「移出项目」是清掉该人
 *       <b>所有产品</b>的行，不是某一个。页面上那句警告文案依赖这个语义。</li>
 * </ol>
 *
 * <p>判权的两条边界（普通成员不能写、别租户用户不能拉）也各钉一条。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_proj_member;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=proj-member-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class ProjectMemberContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String T1 = "pm_t1";
    private static final String T2 = "pm_t2";
    private static final String P1 = "pm_p1";
    private static final String P2 = "pm_p2";
    private static final String MEMBER = "pm_member";
    private static final String OTHER = "pm_other";

    @Autowired
    private MockMvc mvc;

    private static String adminToken;
    private static String memberToken;
    private static String t1Id;
    private static String t2Id;
    private static String p1Id;
    private static String p2Id;
    private static String memberId;
    private static String otherId;

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken != null) return;
        adminToken = login("admin", "123456");

        t1Id = createTenant(T1, "成员契约租户一");
        t2Id = createTenant(T2, "成员契约租户二");
        p1Id = createProject(t1Id, T1, P1, "成员契约项目一");
        p2Id = createProject(t2Id, T2, P2, "成员契约项目二");

        memberId = createUser(t1Id, T1, MEMBER, "契约成员");
        otherId = createUser(t2Id, T2, OTHER, "别租户成员");
        memberToken = login(MEMBER, "123456");
    }

    // ------------------------------------------------------------------
    // 1. 显示名
    // ------------------------------------------------------------------

    /**
     * <b>本类最重要的一条。</b>没有它，「接口通了」与「页面能用」是两件事：
     * 成员表只存 userId，不带名字时前端只能把「显示名」列渲染成内部主键。
     */
    @Test
    void listMembersCarriesDisplayName() throws Exception {
        Resp added = putMember(adminToken, T1, p1Id, memberId, "warehouse", "modeler");
        assertEquals(200, added.status(), "派角色失败: " + added.body());

        Resp res = call(get("/api/v1/projects/" + p1Id + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", T1));
        assertEquals(200, res.status(), "读成员失败: " + res.body());

        JsonNode row = findMember(MAPPER.readTree(res.body()), memberId);
        assertEquals("契约成员", row.path("displayName").asText(),
                "displayName 丢了 —— 前端那一列会退化成内部主键: " + res.body());
        assertEquals(MEMBER, row.path("username").asText(),
                "username 丢了: " + res.body());
        assertEquals("modeler", row.path("role").asText());
    }

    // ------------------------------------------------------------------
    // 2. 写权限
    // ------------------------------------------------------------------

    /** 项目里的 modeler 没有 {@code iam:member}，改不动成员；租户管理员可以。 */
    @Test
    void memberCannotWriteButAdminCan() throws Exception {
        assertEquals(200, putMember(adminToken, T1, p1Id, memberId, "warehouse", "modeler").status());

        Resp denied = putMember(memberToken, T1, p1Id, memberId, "warehouse", "viewer");
        assertEquals(403, denied.status(), "普通成员不该能改成员角色: " + denied.body());

        assertEquals(200, putMember(adminToken, T1, p1Id, memberId, "warehouse", "viewer").status());
        Resp res = call(get("/api/v1/projects/" + p1Id + "/members")
                .header("Authorization", "Bearer " + adminToken).header("X-Tenant-Code", T1));
        assertEquals("viewer", findMember(MAPPER.readTree(res.body()), memberId).path("role").asText(),
                "角色没落到库里: " + res.body());
    }

    // ------------------------------------------------------------------
    // 3. 角色码校验
    // ------------------------------------------------------------------

    /**
     * 写侧的角色码问产品角色表（V20）。拒绝时要把可用角色列出来 —— 否则调用方
     * 只知道「不行」，不知道去哪里建角色。
     */
    @Test
    void unknownRoleCodeIsRejected() throws Exception {
        Resp res = putMember(adminToken, T1, p1Id, memberId, "warehouse", "no_such_role");
        assertEquals(400, res.status(), "不存在的角色码应当被拒: " + res.body());
        assertTrue(res.body().contains("可用角色"),
                "拒绝信息里没告诉调用方可用角色有哪些: " + res.body());
    }

    // ------------------------------------------------------------------
    // 4. 只能拉本组织用户
    // ------------------------------------------------------------------

    @Test
    void crossTenantUserIsRejected() throws Exception {
        Resp res = putMember(adminToken, T1, p1Id, otherId, "warehouse", "viewer");
        assertEquals(400, res.status(), "别租户的用户不该能加进本项目: " + res.body());
        assertTrue(res.body().contains("本组织用户"), "错误信息没点明原因: " + res.body());
    }

    // ------------------------------------------------------------------
    // 5. 跨租户项目
    // ------------------------------------------------------------------

    /**
     * <b>路径选择的核心论据。</b>请求走 T1 的上下文去读 T2 的项目必须 403 ——
     * 成立才说明「路径里不带 tenantId」是安全的（归属校验在 {@code requireProject} 里）。
     */
    @Test
    void crossTenantProjectIsForbidden() throws Exception {
        Resp res = call(get("/api/v1/projects/" + p2Id + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", T1));
        assertEquals(403, res.status(), "T1 的上下文读到了 T2 的项目成员: " + res.body());

        // 反向：在自己的上下文里读得到，证明上一条不是被别的判据挡的
        Resp ok = call(get("/api/v1/projects/" + p2Id + "/members")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", T2));
        assertEquals(200, ok.status(), "T2 的上下文应当读得到 T2 的项目: " + ok.body());
    }

    // ------------------------------------------------------------------
    // 6. 删除 = 清掉所有产品
    // ------------------------------------------------------------------

    /**
     * 「移出项目」的语义是<b>这个人不再属于本项目</b>，不是「摘掉某个产品的角色」——
     * 页面上那句警告文案依赖它。
     */
    @Test
    void deleteRemovesEveryProductRow() throws Exception {
        assertEquals(200, putMember(adminToken, T1, p1Id, memberId, "warehouse", "modeler").status());
        assertEquals(200, putMember(adminToken, T1, p1Id, memberId, "metadata", "viewer").status());

        JsonNode before = MAPPER.readTree(call(get("/api/v1/projects/" + p1Id + "/members")
                .header("Authorization", "Bearer " + adminToken).header("X-Tenant-Code", T1)).body());
        assertEquals(2, countOf(before, memberId), "应当有两行（warehouse + metadata）: " + before);

        Resp del = call(delete("/api/v1/projects/" + p1Id + "/members/" + memberId)
                .header("Authorization", "Bearer " + adminToken).header("X-Tenant-Code", T1));
        assertEquals(200, del.status(), "移出成员失败: " + del.body());

        JsonNode after = MAPPER.readTree(call(get("/api/v1/projects/" + p1Id + "/members")
                .header("Authorization", "Bearer " + adminToken).header("X-Tenant-Code", T1)).body());
        assertEquals(0, countOf(after, memberId),
                "删了某个产品就不算删干净 —— 那行会残留成幽灵成员: " + after);
    }

    // ------------------------------------------------------------------
    // 7. 角色选项
    // ------------------------------------------------------------------

    /** 角色下拉的选项来自产品角色表，不是前端写死的三值。 */
    @Test
    void memberRolesExposeBuiltins() throws Exception {
        Resp res = call(get("/api/v1/projects/" + p1Id + "/member-roles")
                .header("Authorization", "Bearer " + adminToken).header("X-Tenant-Code", T1));
        assertEquals(200, res.status(), "读角色选项失败: " + res.body());
        JsonNode rows = MAPPER.readTree(res.body());
        assertTrue(rows.isArray() && rows.size() >= 3, "内置角色不该少于三个: " + res.body());

        StringBuilder codes = new StringBuilder();
        for (JsonNode r : rows) codes.append(r.path("code").asText()).append(' ');
        for (String builtin : new String[]{"admin", "modeler", "viewer"}) {
            assertTrue(codes.toString().contains(builtin), "缺内置角色 " + builtin + ": " + codes);
        }
        // 权限词清单不该随角色选项一起发出去（那是管理面「产品角色」页要看的）
        for (JsonNode r : rows) {
            assertEquals(0, r.path("perms").size(), "角色选项里不该带 perms: " + r);
        }
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private Resp putMember(String token, String tenantCode, String projectId, String userId,
                           String product, String role) throws Exception {
        return call(put("/api/v1/projects/" + projectId + "/members/" + userId)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", tenantCode)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"" + product + "\",\"role\":\"" + role + "\"}"));
    }

    private static JsonNode findMember(JsonNode array, String userId) {
        for (JsonNode row : array) {
            if (userId.equals(row.path("userId").asText())) return row;
        }
        throw new AssertionError("成员列表里没有 " + userId + ": " + array);
    }

    private static int countOf(JsonNode array, String userId) {
        int n = 0;
        for (JsonNode row : array) {
            if (userId.equals(row.path("userId").asText())) n++;
        }
        return n;
    }

    private String createTenant(String code, String name) throws Exception {
        Resp res = call(post("/api/platform/tenants")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\",\"adminUserId\":\"admin\"}"));
        assertEquals(200, res.status(), "建租户失败(" + code + "): " + res.body());
        return MAPPER.readTree(res.body()).path("id").asText();
    }

    private String createProject(String tenantId, String tenantCode, String code, String name) throws Exception {
        Resp res = call(post("/api/v1/tenants/" + tenantId + "/projects")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", tenantCode)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"));
        assertEquals(200, res.status(), "建项目失败(" + code + "): " + res.body());
        String id = MAPPER.readTree(res.body()).path("id").asText();
        assertNotEquals("", id, "建项目没返回 id: " + res.body());
        return id;
    }

    private String createUser(String tenantId, String tenantCode, String username, String display) throws Exception {
        Resp res = call(post("/api/tenants/" + tenantId + "/users")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", tenantCode)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"displayName\":\"" + display + "\","
                        + "\"password\":\"123456\",\"tenantRole\":\"member\"}"));
        assertEquals(200, res.status(), "建用户失败(" + username + "): " + res.body());
        return MAPPER.readTree(res.body()).path("id").asText();
    }

    private String login(String username, String password) throws Exception {
        Resp res = call(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, res.status(), "登录失败(" + username + "): " + res.body());
        return MAPPER.readTree(res.body()).path("token").asText();
    }

    private Resp call(MockHttpServletRequestBuilder rb) throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }

    private record Resp(int status, String body) {
    }
}
