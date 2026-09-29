package com.dwai.platform;

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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 模块策略（{@code tenant_licenses.module_policies}，V27）与它在侧栏上的效果。
 *
 * <h2>这一层要钉住的三件事</h2>
 *
 * <ol>
 *   <li><b>「没配过 = 不判」是向后兼容的命门</b>：老租户没有这一行，升级后侧栏必须逐字不变。
 *       把生效默认也按 {@code role_holders} 判，会悄悄拿走一批人今天看得到的入口，
 *       而表现只是「侧栏少了一条」—— 不会有任何报错。第一条用例专门守它。</li>
 *   <li><b>{@code visibleTo} 是项目内的概念</b>：工作台壳（没有 {@code X-Project-Id}）判不了，
 *       此时<b>不判</b>而不是判否 —— 判否会让工作台里挂着的产品入口对所有人消失。</li>
 *   <li><b>「始终出现」的例外</b>：不可见时置灰并说明，而不是抹掉。它的落地靠的是既有的
 *       {@code empty_policy='always'}（见 {@code NavNodeService.Render#shows}），不是硬编产品码
 *       —— 所以用例按配置构造，<b>断言里没有一处依赖「数据地图」这个产品名</b>。</li>
 * </ol>
 *
 * <p>{@code enabled} 与 {@code visibleTo} 的区别也在用例里：前者<b>与项目上下文无关</b>
 * （本组织关掉的模块谁都看不见），后者只在有项目上下文时才有意义。
 *
 * <p>产品菜单节点的 id 是服务端生成的，所以一律按 {@code label} 定位。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_module_policy;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=module-policy-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class ModulePolicyContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_CODE = "mpol_t1";
    private static final String NODE_TITLE = "血缘入口";
    private static final String ALWAYS_TITLE = "始终出现的入口";

    /** 种子的 id —— 清库时必须留着（理由见 {@code NavNodeTest.SEED_IDS}）。 */
    private static final Set<String> SEED_IDS = Set.of(
            "nav-sys", "nav-sys-users", "nav-sys-roles", "nav-sys-projects",
            "nav-sys-knowledge", "nav-sys-settings", "nav-sys-modules", "nav-sys-compute",
            "nav-proj", "nav-proj-members");

    @Autowired
    private MockMvc mvc;

    /** 清理用（直删绕开业务守卫），见 {@code setUp} 里的说明。 */
    @Autowired
    private JdbcTemplate jdbc;

    private static String adminToken;
    private static String tenantAdminToken;
    private static String memberToken;
    private static String outsiderToken;
    private static String tenantId;
    private static String projectId;

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken == null) {
            adminToken = login("admin", "123456");
            Resp created = call(post("/api/platform/tenants")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"模块策略验证租户\","
                            + "\"adminUserId\":\"admin\"}"));
            assertEquals(200, created.status(), "创建租户失败: " + created.body());
            tenantId = MAPPER.readTree(created.body()).path("id").asText();

            // 两个租户端点都要求管理员身份（ModulePolicyService 里是 requireTenantAdmin），
            // 所以这份 token 是整个类的主力，不是可有可无的替身。
            createUser("mpol_admin", "admin");
            String memberUserId = createUser("mpol_member", "member");
            createUser("mpol_outsider", "member");
            tenantAdminToken = login("mpol_admin", "123456");
            memberToken = login("mpol_member", "123456");
            outsiderToken = login("mpol_outsider", "123456");

            projectId = createProject("mpol_p1", "策略验证项目");
            // member 在项目里有 warehouse 的成员行（**没有** metadata 的）——
            // 「持有该产品角色的人」与「是项目成员」两档的差别就靠它。
            Resp added = putMember(projectId, memberUserId, "warehouse", "viewer");
            assertEquals(200, added.status(), "派角色失败: " + added.body());
        }

        // 树与策略都是可变的、同一个库跑所有用例：每个用例从干净状态重来。
        //
        // **直删，不走 DELETE 接口**：本类建的是 org 自有节点，而那一类现在**只能停用**
        // （守卫见 `NavNodeService.delete()`）—— 走接口会被 400 挡下，行留在库里，
        // 症状是「别的用例随机变红」（下一条用例撞上残留的 uk_nav_node），不是本用例报错。
        // 清理不是被测行为，绕开业务守卫是对的。
        jdbc.update("DELETE FROM nav_entry_links");
        for (String id : new ArrayList<>(ownedNodeIds())) {
            jdbc.update("DELETE FROM nav_nodes WHERE id = ?", id);
        }
        setModules("[\"warehouse\",\"metadata\"]");
        assertEquals(200, putPolicies("[]").status(), "清理策略失败");
    }

    // ------------------------------------------------------------------
    // 向后兼容：没有策略行 = 不判
    // ------------------------------------------------------------------

    /**
     * <b>本次改动最重要的一条</b>：{@code module_policies} 还是 NULL 时，
     * 一个平台已开通、但他在其下<b>没有角色</b>的产品入口必须照常出现。
     *
     * <p>它同时守住 {@code visibleTo} 的默认档：若把「没配过」当成 {@code role_holders}，
     * 这条会红 —— member 只在 warehouse 下有成员行，对 metadata 连角色都取不到
     * （{@code roleOf} 抛 FORBIDDEN，{@code currentRole} 回落 empty）。
     */
    @Test
    void noPolicyRowLeavesSidebarUnchanged() throws Exception {
        createProductNode("metadata", "/lineage/tables");

        assertNotNull(findByLabel(navTree(memberToken, projectId), NODE_TITLE),
                "没有策略行时产品节点必须照常出现（向后兼容的命门）: " + navTree(memberToken, projectId));
        assertNotNull(findByLabel(navTree(memberToken, null), NODE_TITLE),
                "工作台壳同理: " + navTree(memberToken, null));
    }

    /** 本组织关掉的模块，谁都看不见 —— {@code enabled} 与角色、与项目上下文都无关。 */
    @Test
    void disabledModuleDisappearsForEveryone() throws Exception {
        createProductNode("metadata", "/lineage/tables");
        putPolicies("[{\"product\":\"metadata\",\"enabled\":false,\"visibleTo\":\"all_members\"}]");

        assertNull(findByLabel(navTree(memberToken, projectId), NODE_TITLE),
                "关掉之后成员不该看到: " + navTree(memberToken, projectId));
        assertNull(findByLabel(navTree(tenantAdminToken, projectId), NODE_TITLE),
                "租户管理员也不该看到（关的是整个模块，不是「给谁看」）: "
                        + navTree(tenantAdminToken, projectId));
        assertNull(findByLabel(navTree(tenantAdminToken, null), NODE_TITLE),
                "工作台壳里同样不该有: " + navTree(tenantAdminToken, null));
    }

    // ------------------------------------------------------------------
    // 可见范围四档
    // ------------------------------------------------------------------

    /** {@code tenant_admin}：管理员在、成员不在。 */
    @Test
    void visibleToTenantAdminHidesFromMember() throws Exception {
        createProductNode("metadata", "/lineage/tables");
        putPolicies("[{\"product\":\"metadata\",\"enabled\":true,\"visibleTo\":\"tenant_admin\"}]");

        assertNotNull(findByLabel(navTree(tenantAdminToken, projectId), NODE_TITLE),
                "租户管理员应当看到: " + navTree(tenantAdminToken, projectId));
        assertNull(findByLabel(navTree(memberToken, projectId), NODE_TITLE),
                "普通成员不该看到: " + navTree(memberToken, projectId));
        // 反过来：工作台壳（没有项目上下文）不判这一档 —— 判否会让工作台里的产品入口对所有人消失
        assertNotNull(findByLabel(navTree(memberToken, null), NODE_TITLE),
                "工作台壳没有项目上下文，visibleTo 不判: " + navTree(memberToken, null));
    }

    /** {@code project_admin}：他持有的是 warehouse 的 viewer，不是管理角色。 */
    @Test
    void visibleToProjectAdminExcludesPlainRoleHolder() throws Exception {
        createProductNode("metadata", "/lineage/tables");
        putPolicies("[{\"product\":\"metadata\",\"enabled\":true,\"visibleTo\":\"project_admin\"}]");

        assertNull(findByLabel(navTree(memberToken, projectId), NODE_TITLE),
                "viewer 不是管理角色，不该看到: " + navTree(memberToken, projectId));
        assertNotNull(findByLabel(navTree(tenantAdminToken, projectId), NODE_TITLE),
                "租户管理员在每一档都看得到: " + navTree(tenantAdminToken, projectId));
    }

    /**
     * {@code all_members} 与 {@code role_holders} 的<b>唯一差别</b>：
     * 前者只要求是项目成员，后者还要求在本产品下有角色。
     *
     * <p>两档判成同一档是最容易犯、也最难发现的错（配的人自己是管理员，看什么都正常）。
     * 这里用同一个用户、同一份成员关系，只换档名 —— 结果必须不同。
     */
    @Test
    void allMembersIsWiderThanRoleHolders() throws Exception {
        createProductNode("metadata", "/lineage/tables");

        putPolicies("[{\"product\":\"metadata\",\"enabled\":true,\"visibleTo\":\"role_holders\"}]");
        assertNull(findByLabel(navTree(memberToken, projectId), NODE_TITLE),
                "role_holders：他在 metadata 下没有角色，不该看到: " + navTree(memberToken, projectId));

        putPolicies("[{\"product\":\"metadata\",\"enabled\":true,\"visibleTo\":\"all_members\"}]");
        assertNotNull(findByLabel(navTree(memberToken, projectId), NODE_TITLE),
                "all_members：他是项目成员，应当看到: " + navTree(memberToken, projectId));
    }

    /** 不是项目成员的人在 {@code all_members} 下也看不到 —— 「全部成员」仍以项目为界。 */
    @Test
    void allMembersStillRequiresProjectMembership() throws Exception {
        createProductNode("metadata", "/lineage/tables");
        putPolicies("[{\"product\":\"metadata\",\"enabled\":true,\"visibleTo\":\"all_members\"}]");

        assertNull(findByLabel(navTree(outsiderToken, projectId), NODE_TITLE),
                "不在项目里的人不该看到: " + navTree(outsiderToken, projectId));
    }

    // ------------------------------------------------------------------
    // 始终出现的例外：置灰说明而不是抹掉
    // ------------------------------------------------------------------

    /**
     * 承诺了「始终出现」的产品节点（{@code empty_policy='always'}，即数据地图那一支）在模块
     * 不可见时<b>保留并置灰</b>；对照组是同样挂 metadata、但没承诺始终出现的节点，它应当整条消失。
     *
     * <p>断言里<b>不出现「数据地图」这个产品码</b>：判据挂在 {@code empty_policy} 配置上。
     */
    @Test
    void alwaysNodeStaysVisibleButDisabled() throws Exception {
        create("{\"scope\":\"project\",\"title\":\"" + ALWAYS_TITLE + "\",\"product\":\"metadata\","
                + "\"emptyPolicy\":\"always\",\"path\":\"/lineage/tables\"}");
        createProductNode("metadata", "/lineage/other");

        putPolicies("[{\"product\":\"metadata\",\"enabled\":false,\"visibleTo\":\"all_members\"}]");

        JsonNode tree = navTree(memberToken, projectId);
        JsonNode kept = findByLabel(tree, ALWAYS_TITLE);
        assertNotNull(kept, "承诺始终出现的节点不该消失: " + tree);
        assertTrue(kept.path("disabled").asBoolean(), "它应当被置灰: " + kept);
        assertEquals("", kept.path("path").asText(),
                "置灰项不能带可点路径 —— 点进去恰好是个 403，比置灰更糟: " + kept);
        assertTrue(kept.path("disabledReason").asText().contains("模块管理"),
                "说明要指出去哪儿改: " + kept);

        assertNull(findByLabel(tree, NODE_TITLE),
                "没承诺始终出现的产品节点应当整条消失: " + tree);
    }

    /** 成因要分开说：本组织没启用 ≠ 你不在可见范围内 —— 后者照「未开通」去平台后台查会查不到。 */
    @Test
    void disabledReasonDistinguishesDisabledFromNotVisible() throws Exception {
        create("{\"scope\":\"project\",\"title\":\"" + ALWAYS_TITLE + "\",\"product\":\"metadata\","
                + "\"emptyPolicy\":\"always\",\"path\":\"/lineage/tables\"}");

        putPolicies("[{\"product\":\"metadata\",\"enabled\":true,\"visibleTo\":\"tenant_admin\"}]");
        String notVisible = findByLabel(navTree(memberToken, projectId), ALWAYS_TITLE)
                .path("disabledReason").asText();
        assertTrue(notVisible.contains("范围"), "看不见时说的是「不在范围内」: " + notVisible);

        putPolicies("[{\"product\":\"metadata\",\"enabled\":false,\"visibleTo\":\"all_members\"}]");
        String disabled = findByLabel(navTree(memberToken, projectId), ALWAYS_TITLE)
                .path("disabledReason").asText();
        assertTrue(disabled.contains("未启用"), "关掉时说的是「未启用」: " + disabled);
    }

    // ------------------------------------------------------------------
    // 读写两侧的越界与状态
    // ------------------------------------------------------------------

    /** 列表只列平台已开通的模块，并且标出哪些是「从未配过」。 */
    @Test
    void listOnlyShowsLicensedModulesAndMarksExplicit() throws Exception {
        JsonNode rows = getModules();
        assertEquals(2, rows.size(), "许可里只有 warehouse 与 metadata: " + rows);
        for (JsonNode r : rows) {
            assertFalse(r.path("explicit").asBoolean(), "还没写过策略，应当都不是 explicit: " + r);
            assertTrue(r.path("enabled").asBoolean(), "显示初值应当是全开: " + r);
        }
        assertEquals("all_members", rowOf(rows, "warehouse").path("visibleTo").asText(),
                "warehouse 的显示初值是 all_members（照原型）");
        assertEquals("role_holders", rowOf(rows, "metadata").path("visibleTo").asText(),
                "其余模块的显示初值是 role_holders（照原型）");

        putPolicies("[{\"product\":\"metadata\",\"enabled\":false,\"visibleTo\":\"tenant_admin\"}]");
        JsonNode after = rowOf(getModules(), "metadata");
        assertTrue(after.path("explicit").asBoolean(), "写过了就应当标 explicit: " + after);
        assertFalse(after.path("enabled").asBoolean(), after.toString());
        assertEquals("tenant_admin", after.path("visibleTo").asText(), after.toString());

        // 没配过的那个仍是 explicit=false —— 页面的「只提交动过的行」就靠它
        assertFalse(rowOf(getModules(), "warehouse").path("explicit").asBoolean(),
                "没动过的模块不该被这次保存带上 explicit");
    }

    /** 平台没开通的模块不能写（400）—— 静默忽略会让人以为设置生效了。 */
    @Test
    void writingUnlicensedModuleIsRejected() throws Exception {
        setModules("[\"warehouse\"]");
        Resp res = putPolicies("[{\"product\":\"metadata\",\"enabled\":false,\"visibleTo\":\"all_members\"}]");
        assertEquals(400, res.status(), "未开通的模块应当被拒: " + res.body());

        assertEquals(1, getModules().size(), "被拒之后不该顺手改了什么: " + getModules());
    }

    /** 不认识的可见范围档名不能写（400）—— 写进去会按某个没人预期的默认值走。 */
    @Test
    void unknownVisibleToIsRejected() throws Exception {
        Resp res = putPolicies("[{\"product\":\"metadata\",\"enabled\":true,\"visibleTo\":\"nobody\"}]");
        assertEquals(400, res.status(), "不认识的可见范围应当被拒: " + res.body());
    }

    /** 清空 = 回到「没配过」，侧栏不再判 —— 管理员要能撤回自己做过的一轮设置。 */
    @Test
    void clearingPoliciesRestoresDefault() throws Exception {
        createProductNode("metadata", "/lineage/tables");
        putPolicies("[{\"product\":\"metadata\",\"enabled\":false,\"visibleTo\":\"all_members\"}]");
        assertNull(findByLabel(navTree(memberToken, projectId), NODE_TITLE), "先确认它真的被关掉了");

        assertEquals(200, putPolicies("[]").status());
        assertNotNull(findByLabel(navTree(memberToken, projectId), NODE_TITLE),
                "清空策略后应当回到「不判」: " + navTree(memberToken, projectId));
        assertFalse(rowOf(getModules(), "metadata").path("explicit").asBoolean(),
                "清空之后又回到「没配过」");
    }

    /** 非管理员既不能读也不能写 —— 这两条端点改的是全组织的模块可见性。 */
    @Test
    void nonAdminCannotReadOrWritePolicies() throws Exception {
        assertEquals(403, call(put("/api/v1/tenants/" + tenantId + "/modules")
                .header("Authorization", "Bearer " + memberToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("[{\"product\":\"metadata\",\"enabled\":false,\"visibleTo\":\"all_members\"}]"))
                .status(), "普通成员不该能改模块策略");
        assertEquals(403, call(get("/api/v1/tenants/" + tenantId + "/modules")
                .header("Authorization", "Bearer " + memberToken)
                .header("X-Tenant-Code", TENANT_CODE)).status(),
                "普通成员不该能读模块策略");
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    private void createProductNode(String product, String path) throws Exception {
        create("{\"scope\":\"project\",\"title\":\"" + NODE_TITLE + "\",\"product\":\"" + product
                + "\",\"path\":\"" + path + "\"}");
    }

    private Resp setModules(String json) throws Exception {
        Resp res = call(patch("/api/platform/tenants/" + tenantId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"modules\":" + json + "}"));
        assertEquals(200, res.status(), "改许可失败: " + res.body());
        return res;
    }

    /** 读租户侧模块列表（要管理员身份 + 租户上下文，见 {@code ModulePolicyService.list}）。 */
    private JsonNode getModules() throws Exception {
        Resp res = call(get("/api/v1/tenants/" + tenantId + "/modules")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "读模块列表失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private Resp putPolicies(String json) throws Exception {
        return call(put("/api/v1/tenants/" + tenantId + "/modules")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private static JsonNode rowOf(JsonNode rows, String product) {
        for (JsonNode r : rows) {
            if (product.equals(r.path("product").asText())) return r;
        }
        throw new AssertionError("模块列表里没有 " + product + ": " + rows);
    }

    private Resp putMember(String pid, String userId, String product, String role) throws Exception {
        return call(put("/api/v1/projects/" + pid + "/members/" + userId)
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"" + product + "\",\"role\":\"" + role + "\"}"));
    }

    private String createProject(String code, String name) throws Exception {
        Resp res = call(post("/api/v1/tenants/" + tenantId + "/projects")
                .header("Authorization", "Bearer " + tenantAdminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"));
        assertEquals(200, res.status(), "建项目失败: " + res.body());
        String id = MAPPER.readTree(res.body()).path("id").asText();
        assertFalse(id.isEmpty(), "建项目没返回 id: " + res.body());
        return id;
    }

    /** 建用户并返回 id —— 派角色用的是 id 不是用户名。 */
    private String createUser(String username, String tenantRole) throws Exception {
        Resp res = call(post("/api/tenants/" + tenantId + "/users")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"displayName\":\"" + username + "\","
                        + "\"password\":\"123456\",\"tenantRole\":\"" + tenantRole + "\"}"));
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

    private JsonNode create(String json) throws Exception {
        Resp res = call(post("/api/v1/platform/nav-nodes")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
        assertEquals(200, res.status(), "建节点失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /**
     * 消费面（侧栏真源）。
     *
     * <p>{@code pid} 非空时带 {@code X-Project-Id} —— 这是「带项目上下文」与「工作台壳」
     * 两种读法的唯一区别，{@code visibleTo} 的判与不判全看它。
     */
    private JsonNode navTree(String token, String pid) throws Exception {
        var rb = get("/api/v1/nav")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE);
        if (pid != null) rb = rb.header("X-Project-Id", pid);
        Resp res = call(rb);
        assertEquals(200, res.status(), "读菜单树失败: " + res.body());
        JsonNode node = MAPPER.readTree(res.body());
        assertTrue(node.isArray(), "菜单应当是数组: " + res.body());
        return node;
    }

    /**
     * 深度优先按 {@code label} 找。
     *
     * <p>找 {@code label} 而不是 id：产品节点的 id 由服务端生成，测试没法预知。
     * 找不到返回 {@code null} —— 断言里正要区分「没有」这一种。
     */
    private static JsonNode findByLabel(JsonNode nodes, String label) {
        for (JsonNode row : nodes) {
            if (label.equals(row.path("label").asText())) return row;
            JsonNode hit = findByLabel(row.path("children"), label);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 管理面里「非种子」的节点 id：测试自己建的行，用例之间要清掉。 */
    private Set<String> ownedNodeIds() throws Exception {
        Set<String> out = new LinkedHashSet<>();
        collectOwned(adminTree(), out);
        return out;
    }

    private static void collectOwned(JsonNode nodes, Set<String> out) {
        for (JsonNode row : nodes) {
            String id = row.path("id").asText();
            if (!SEED_IDS.contains(id)) out.add(id);
            collectOwned(row.path("children"), out);
        }
    }

    private JsonNode adminTree() throws Exception {
        Resp res = call(get("/api/v1/platform/nav-nodes")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "读管理面菜单树失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private record Resp(int status, String body) {
    }

    private Resp call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }
}
