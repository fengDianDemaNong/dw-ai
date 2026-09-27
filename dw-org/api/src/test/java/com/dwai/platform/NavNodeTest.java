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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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

/**
 * 菜单树的增删改查与消费面过滤（{@code nav_nodes}，见 {@code V23__nav_nodes.sql}）。
 *
 * <p><b>合并自两个文件</b>：V23 之前「分组」与「菜单」是两张表、两个 service、两组端点
 * （{@code NavGroupTest} 管分组 CRUD，{@code NavMenuTest} 管菜单消费面）。合并成一张表之后
 * 再分两个文件测，就会把「同一棵树的两半」这件事在两个 setup 里各写一遍。
 *
 * <h2>这一层要钉住的东西</h2>
 *
 * <ol>
 *   <li><b>org 自有菜单现在在库里</b>（V23 的种子数据），不再由前端硬编码 ——
 *       所以「没选租户返回空」「租户没有许可行时 org 自己的入口还在」都得在这一层验。</li>
 *   <li><b>{@code admin_only}</b>：原先前端 {@code buildSysNav(isRealTenantAdmin)} 算的，
 *       现在是服务端算 —— 前端算的那份在「租户管理员被换掉、页面没刷新」时会显示错。</li>
 *   <li><b>层级</b>：任意深度，{@code parent_id} 自引用。防环必须是服务端的事
 *       （见 {@code NavNodeService.requireNoCycle}）。</li>
 *   <li><b>删一个节点连带整棵子树</b>，返回的条数就是前端二次确认里的那个数字。</li>
 * </ol>
 *
 * <p>跨进程的挂载展开在 {@code NavTreeMountTest}；这里一律用手工节点，不依赖桩服务器。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_nav_nodes;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=nav-node-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class NavNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_CODE = "navn_t1";

    /**
     * 种子的 id —— 清库时**必须留着**。
     *
     * <p>它们是工作台壳与项目壳的 org 自有菜单（用户管理 / 项目管理 / 成员管理 …）。
     * 用「product 非空就删」那种写法会把「测试自己建的 org 自有节点」漏掉；
     * 用「不是种子就删」则需要这份名单。
     *
     * <p>{@code nav-proj-back}（项目壳的「返回工作台」）不在名单里：V24 已把它从种子里删掉，
     * 那个入口现在只由左下角的用户面板提供。
     */
    private static final Set<String> SEED_IDS = Set.of(
            "nav-sys", "nav-sys-users", "nav-sys-roles", "nav-sys-projects",
            "nav-sys-knowledge", "nav-sys-settings",
            "nav-proj", "nav-proj-members");

    /** 工作台壳的 org 自有入口（非管理员也看得到的那一条）—— 好几条用例拿它当锚点。 */
    private static final String SYS_PROJECTS = "/org/workbench/projects";

    @Autowired
    private MockMvc mvc;

    private static String adminToken;
    private static String memberToken;
    private static String tenantAdminToken;
    private static String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken == null) {
            adminToken = login("admin", "123456");
            Resp created = call(post("/api/platform/tenants")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"菜单树验证租户\",\"adminUserId\":\"admin\"}"));
            assertEquals(200, created.status(), "创建租户失败: " + created.body());
            tenantId = MAPPER.readTree(created.body()).path("id").asText();

            createUser("navn_member", "member");
            createUser("navn_admin", "admin");
            memberToken = login("navn_member", "123456");
            tenantAdminToken = login("navn_admin", "123456");
        }

        // 同一套库跑所有用例，而树是可变的：每个用例从「只剩种子」重来，
        // 否则用例之间会互相看见对方留下的行（同层同名会直接撞唯一约束）。
        for (String id : new ArrayList<>(ownedNodeIds())) {
            call(delete("/api/v1/platform/nav-nodes/" + id).header("Authorization", "Bearer " + adminToken));
        }
    }

    // ------------------------------------------------------------------
    // 管理面：树的增删改查
    // ------------------------------------------------------------------

    /** 建一棵三层树，管理面读回来就是嵌套的（不是平铺列表加 parentId）。 */
    @Test
    void treeIsCreatedAndReadBackAsNested() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"数据分析\",\"icon\":\"DatabaseOutlined\"}")
                .path("id").asText();
        String sub = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"离线\",\"sortOrder\":10}")
                .path("id").asText();
        create("{\"scope\":\"project\",\"parentId\":\"" + sub + "\",\"title\":\"明细\",\"path\":\"/x/detail\"}");

        JsonNode node = findById(adminTree(null), dir);
        assertNotNull(node, "顶层应当有「数据分析」: " + adminTree(null));
        assertEquals(1, node.path("children").size(), node.toString());
        JsonNode subNode = node.path("children").get(0);
        assertEquals("离线", subNode.path("label").asText(), subNode.toString());
        assertEquals(1, subNode.path("children").size(), "三层应当完整带回来: " + subNode);
        assertEquals("/x/detail", subNode.path("children").get(0).path("path").asText(), subNode.toString());
        // 目录节点没有路径 —— 空串是有意的，不是「漏填」
        assertEquals("", node.path("path").asText(), "目录节点 path 必须是空串: " + node);
    }

    /** 同一层里不能有两个同名菜单（{@code uk_nav_node}）；不同层可以同名。 */
    @Test
    void duplicateTitleInSameParentIsRejected() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"A\"}").path("id").asText();
        create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"重名\",\"path\":\"/a\"}");

        Resp dup = postNode("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"重名\",\"path\":\"/b\"}");
        assertEquals(400, dup.status(), "同层重名应当被拒而不是 500: " + dup.body());

        // 换一层就合法 —— 唯一键是 (scope, parent_id, title)，不是 (scope, title)
        String other = create("{\"scope\":\"project\",\"title\":\"B\"}").path("id").asText();
        Resp ok = postNode("{\"scope\":\"project\",\"parentId\":\"" + other + "\",\"title\":\"重名\",\"path\":\"/c\"}");
        assertEquals(200, ok.status(), "不同父节点下同名是合法的: " + ok.body());
    }

    @Test
    void unknownScopeAndProductAndPolicyAreRejected() throws Exception {
        assertEquals(400, postNode("{\"scope\":\"nope\",\"title\":\"X\"}").status());
        assertEquals(400, postNode("{\"scope\":\"project\",\"title\":\"X\",\"product\":\"nope\"}").status());
        assertEquals(400, postNode("{\"scope\":\"project\",\"title\":\"X\",\"emptyPolicy\":\"maybe\"}").status());
        assertEquals(400, postNode("{\"scope\":\"project\",\"title\":\"   \"}").status());
        assertEquals(400, postNode("{\"scope\":\"project\"}").status(), "没有 title 应当被拒");
    }

    @Test
    void overlongTitleIsRejectedExplicitly() throws Exception {
        String long64 = "长".repeat(65);
        Resp res = postNode("{\"scope\":\"project\",\"title\":\"" + long64 + "\"}");
        assertEquals(400, res.status(), "超长标题要在写入时拒，不能靠列宽兜底: " + res.body());
        assertTrue(res.body().contains("64"), "报错要带上限，管理员才知道该删到多短: " + res.body());
    }

    @Test
    void scopeIsImmutable() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"搬家\"}").path("id").asText();
        Resp res = call(patch("/api/v1/platform/nav-nodes/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"workbench\"}"));
        assertEquals(400, res.status(), "scope 是身份的一部分，不能改: " + res.body());
    }

    /** 父节点必须存在，且必须在同一个壳里 —— 跨壳挂的后果是「这一支在侧栏里整片消失、无报错」。 */
    @Test
    void parentMustExistAndShareTheSameShell() throws Exception {
        assertEquals(400, postNode("{\"scope\":\"project\",\"parentId\":\"nope\",\"title\":\"X\"}").status());

        String workbenchDir = create("{\"scope\":\"workbench\",\"title\":\"WB\"}").path("id").asText();
        Resp cross = postNode("{\"scope\":\"project\",\"parentId\":\"" + workbenchDir + "\",\"title\":\"X\"}");
        assertEquals(400, cross.status(), "不能把项目壳的节点挂到工作台壳的目录下: " + cross.body());
    }

    /**
     * 不能把一个节点挂到它自己的子孙下面。
     *
     * <p>不拦的话，一次手误就能让整棵子树从侧栏里消失 —— 组树时从顶层往下走，
     * 环上的节点永远不会被访问到，而管理页里它们明明还在。
     */
    @Test
    void cycleIsRejected() throws Exception {
        String a = create("{\"scope\":\"project\",\"title\":\"A\"}").path("id").asText();
        String b = create("{\"scope\":\"project\",\"parentId\":\"" + a + "\",\"title\":\"B\"}").path("id").asText();
        String c = create("{\"scope\":\"project\",\"parentId\":\"" + b + "\",\"title\":\"C\"}").path("id").asText();

        Resp self = patchNode(a, "{\"parentId\":\"" + c + "\"}");
        assertEquals(400, self.status(), "把祖先挂到自己的子孙下应当被拒: " + self.body());

        Resp own = patchNode(b, "{\"parentId\":\"" + b + "\"}");
        assertEquals(400, own.status(), "把自己挂到自己下面应当被拒: " + own.body());
    }

    /** 删一个节点连带整棵子树，返回的条数就是前端二次确认里的数字。 */
    @Test
    void deleteCascadesToSubtreeAndReportsTheCount() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"要删的\"}").path("id").asText();
        String sub = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"子\"}").path("id").asText();
        create("{\"scope\":\"project\",\"parentId\":\"" + sub + "\",\"title\":\"孙\",\"path\":\"/x/sun\"}");

        Resp res = call(delete("/api/v1/platform/nav-nodes/" + dir)
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "删除失败: " + res.body());
        assertEquals(2, MAPPER.readTree(res.body()).path("subtree").asInt(),
                "返回的应当是连带删掉的子孙条数（不含自己）: " + res.body());

        JsonNode tree = adminTree("project");
        assertNull(findById(tree, dir), "父节点应当没了: " + tree);
        assertNull(findById(tree, sub), "子孙应当一起没了 —— 留下来就是永远够不着的孤儿: " + tree);
    }

    @Test
    void listCanBeFilteredByScope() throws Exception {
        create("{\"scope\":\"project\",\"title\":\"只在项目壳\"}");

        JsonNode workbench = adminTree("workbench");
        for (JsonNode row : workbench) {
            // 种子把两个壳都填满了，所以只能断言「筛出来的顶层里没有刚建的那条」
            assertFalse("只在项目壳".equals(row.path("label").asText()), "工作台壳里不该有它: " + row);
        }
        assertNotNull(findByLabel(adminTree("project"), "只在项目壳"), "项目壳里应当有它");
    }

    /** {@code admin_only} 只能用在 org 自有节点上：产品节点的可见性由许可与角色决定。 */
    @Test
    void adminOnlyRejectedOnProductNodes() throws Exception {
        Resp res = postNode("{\"scope\":\"project\",\"title\":\"产品页\",\"product\":\"metadata\","
                + "\"path\":\"/lineage/x\",\"adminOnly\":true}");
        assertEquals(400, res.status(), "两个可见性规则叠在一起时「为什么看不到」就没有唯一答案了: " + res.body());
    }

    /** 挂载必须有 {@code ref} —— 没有它就是一个永远空着的目录。 */
    @Test
    void mountedWithoutRefIsRejected() throws Exception {
        Resp res = postNode("{\"scope\":\"project\",\"title\":\"挂空\",\"product\":\"metadata\",\"mounted\":true}");
        assertEquals(400, res.status(), "挂载而不说挂什么应当被拒: " + res.body());
        assertTrue(res.body().contains("ref"), "报错要指出缺的是哪个字段: " + res.body());

        Resp notProduct = postNode("{\"scope\":\"project\",\"title\":\"挂自己\",\"mounted\":true,\"ref\":\"x\"}");
        assertEquals(400, notProduct.status(), "org 自有节点没有可展开的内容: " + notProduct.body());
    }

    @Test
    void writesRequirePlatformAdmin() throws Exception {
        assertEquals(403, postNodeAs(memberToken, "{\"scope\":\"project\",\"title\":\"X\"}").status());
        String id = create("{\"scope\":\"project\",\"title\":\"Y\"}").path("id").asText();
        assertEquals(403, call(patch("/api/v1/platform/nav-nodes/" + id)
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Z\"}")).status());
        assertEquals(403, call(delete("/api/v1/platform/nav-nodes/" + id)
                .header("Authorization", "Bearer " + memberToken)).status());
        assertEquals(403, call(get("/api/v1/platform/nav-nodes")
                .header("Authorization", "Bearer " + memberToken)).status());
    }

    // ------------------------------------------------------------------
    // 消费面
    // ------------------------------------------------------------------

    /** org 自有菜单现在是**库里的种子**，普通成员也应当看到工作台壳里的「项目管理」。 */
    @Test
    void orgOwnedSeedAppearsForPlainMember() throws Exception {
        JsonNode tree = navTree(memberToken);
        JsonNode sys = findByLabel(tree, "系统管理");
        assertNotNull(sys, "工作台壳的「系统管理」应当在种子里: " + tree);
        // 传 children 而不是节点本身：findByPath 的入参是一层数组（见它的实现）
        assertNotNull(findByPath(sys.path("children"), SYS_PROJECTS), "「项目管理」所有人都该看到: " + sys);
    }

    /** {@code admin_only} 的节点对普通成员隐藏、对租户管理员出现 —— 判在服务端。 */
    @Test
    void adminOnlyNodesAreHiddenFromPlainMembers() throws Exception {
        JsonNode forMember = findById(navTree(memberToken), "nav-sys-users");
        assertNull(forMember, "普通成员不该看到「用户管理」: " + navTree(memberToken));

        JsonNode forAdmin = findById(navTree(tenantAdminToken), "nav-sys-users");
        assertNotNull(forAdmin, "租户管理员应当看到「用户管理」: " + navTree(tenantAdminToken));
    }

    /**
     * org 自有节点判不动权限时<b>置灰而不是隐藏</b>（V18 迁移的既有口径：
     * 一个空侧栏会让人以为壳坏了）。
     *
     * <p>种子里的「成员管理」挂着 {@code iam:member}，而这个成员没有任何项目角色，
     * 所以它在项目壳里应当出现、但带着 {@code disabled}。
     */
    @Test
    void orgOwnedNodeWithoutPermIsDisabledNotHidden() throws Exception {
        JsonNode members = findById(navTree(memberToken), "nav-proj-members");
        assertNotNull(members, "「成员管理」不该被抹掉 —— 什么都不显示会让人以为壳里没有这一项: "
                + navTree(memberToken));
        assertTrue(members.path("disabled").asBoolean(), "它应当被置灰: " + members);
        assertEquals("/org/project/{code}/members", members.path("path").asText(),
                "项目码是运行期的，模板原样带出去让前端替换: " + members);
    }

    /** 产品节点按租户许可过滤：没开这个产品，它的入口一条都不该有。 */
    @Test
    void productNodesAreFilteredByLicense() throws Exception {
        create("{\"scope\":\"project\",\"title\":\"血缘入口\",\"product\":\"metadata\",\"path\":\"/lineage/tables\"}");

        setModules("[]");
        assertNull(findByLabel(navTree(memberToken), "血缘入口"), "产品没开通时它的入口不该出现");

        setModules("[\"metadata\"]");
        assertNotNull(findByLabel(navTree(memberToken), "血缘入口"), "开通之后应当出现");
    }

    /** 停用的节点不出现在侧栏，但管理面照旧看得到（不然就没法重新启用它）。 */
    @Test
    void disabledNodeIsHiddenFromSidebarButListedForAdmin() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"停用的\",\"path\":\"/x/off\"}").path("id").asText();
        assertEquals(200, patchNode(id, "{\"enabled\":false}").status());

        assertNull(findByLabel(navTree(memberToken), "停用的"), "停用的节点不该出现");
        assertNotNull(findById(adminTree(null), id), "管理面要能看到它，否则没法再启用");
    }

    /**
     * 空目录策略：<b>一个可用子项都没有的目录</b>怎么办。
     *
     * <p>{@code hide}（默认）= 整枝不出现，{@code always} = 保留并置灰说明。
     * 这条策略在 V23 之前挂在「分组」上，现在挂在目录节点自己身上 —— 因为目录就是分组。
     */
    @Test
    void emptyDirectoryFollowsItsPolicy() throws Exception {
        create("{\"scope\":\"project\",\"title\":\"空目录-隐藏\"}");
        String always = create("{\"scope\":\"project\",\"title\":\"空目录-保留\",\"emptyPolicy\":\"always\"}")
                .path("id").asText();

        JsonNode tree = navTree(memberToken);
        assertNull(findByLabel(tree, "空目录-隐藏"), "默认策略下空目录不该出现: " + tree);

        JsonNode kept = findById(tree, always);
        assertNotNull(kept, "always 策略下空目录要保留: " + tree);
        assertEquals(1, kept.path("children").size(), kept.toString());
        assertTrue(kept.path("children").get(0).path("disabled").asBoolean(),
                "保留下来的是一个置灰的占位项，要说明为什么点不开: " + kept);
    }

    /** 有子项的目录照常渲染，不受 empty_policy 影响。 */
    @Test
    void nonEmptyDirectoryRendersItsChildren() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"有内容的\"}").path("id").asText();
        create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"子项\",\"path\":\"/x/kid\"}");

        JsonNode node = findById(navTree(memberToken), dir);
        assertNotNull(node, "目录应当出现");
        assertEquals(1, node.path("children").size(), node.toString());
        assertEquals("子项", node.path("children").get(0).path("label").asText(), node.toString());
    }

    /** 一个产品都没开通（连许可行都没有）时，**org 自己的入口仍在**，返回的不是空列表。 */
    @Test
    void tenantWithoutLicenseStillGetsOrgOwnedNodes() throws Exception {
        JsonNode tree = navTree(memberToken);
        assertTrue(tree.size() > 0, "org 自有菜单不该被许可过滤掉 —— 那会连「项目管理」都进不去: " + tree);
        assertNotNull(findByLabel(tree, "系统管理"), tree.toString());
    }

    /** 没选租户 → 空列表而不是 500：侧栏是每个页面都要画的东西。 */
    @Test
    void noTenantYieldsEmptyTreeNotError() throws Exception {
        Resp res = call(get("/api/v1/nav").header("Authorization", "Bearer " + memberToken));
        assertEquals(200, res.status(), "没选租户不该把整个壳带下水: " + res.body());
        assertEquals(0, MAPPER.readTree(res.body()).size(), res.body());
    }

    /** 停用节点改路径这类「搬家」操作不该影响别的分支。 */
    @Test
    void movingANodeKeepsItsSubtree() throws Exception {
        String from = create("{\"scope\":\"project\",\"title\":\"原位置\"}").path("id").asText();
        String to = create("{\"scope\":\"project\",\"title\":\"新位置\"}").path("id").asText();
        String leaf = create("{\"scope\":\"project\",\"parentId\":\"" + from + "\",\"title\":\"跟着搬\",\"path\":\"/x/move\"}")
                .path("id").asText();

        assertEquals(200, patchNode(leaf, "{\"parentId\":\"" + to + "\"}").status());
        assertNotNull(findById(navTree(memberToken), leaf), "搬完之后这一项还应当在树里");
        JsonNode dest = findById(adminTree("project"), to);
        assertEquals(1, dest.path("children").size(), dest.toString());
    }

    // ------------------------------------------------------------------

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

    private void setModules(String json) throws Exception {
        Resp res = call(patch("/api/platform/tenants/" + tenantId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"modules\":" + json + "}"));
        assertEquals(200, res.status(), "改许可失败: " + res.body());
    }

    private JsonNode create(String json) throws Exception {
        Resp res = postNode(json);
        assertEquals(200, res.status(), "建节点失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private Resp postNode(String json) throws Exception {
        return postNodeAs(adminToken, json);
    }

    private Resp postNodeAs(String token, String json) throws Exception {
        return call(post("/api/v1/platform/nav-nodes")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private Resp patchNode(String id, String json) throws Exception {
        return call(patch("/api/v1/platform/nav-nodes/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private JsonNode adminTree(String scope) throws Exception {
        String url = "/api/v1/platform/nav-nodes" + (scope == null ? "" : "?scope=" + scope);
        Resp res = call(get(url).header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "读管理面菜单树失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /** 消费面（侧栏真源）。菜单是每个页面都要画的东西，所以这里一律用普通成员身份读。 */
    private JsonNode navTree(String token) throws Exception {
        Resp res = call(get("/api/v1/nav")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "读菜单树失败: " + res.body());
        JsonNode node = MAPPER.readTree(res.body());
        assertTrue(node.isArray(), "菜单应当是数组: " + res.body());
        return node;
    }

    /** 管理面里「非种子」的节点 id：测试自己建的行，用例之间要清掉。 */
    private Set<String> ownedNodeIds() throws Exception {
        Set<String> out = new LinkedHashSet<>();
        collectOwned(adminTree(null), out);
        return out;
    }

    private static void collectOwned(JsonNode nodes, Set<String> out) {
        for (JsonNode row : nodes) {
            String id = row.path("id").asText();
            if (!SEED_IDS.contains(id)) out.add(id);
            collectOwned(row.path("children"), out);
        }
    }

    /** 深度优先找一个节点（树是嵌套的，顶层那个循环找不到深处的）。 */
    private static JsonNode findById(JsonNode nodes, String id) {
        for (JsonNode row : nodes) {
            if (id.equals(row.path("id").asText())) return row;
            JsonNode hit = findById(row.path("children"), id);
            if (hit != null) return hit;
        }
        return null;
    }

    private static JsonNode findByLabel(JsonNode nodes, String label) {
        for (JsonNode row : nodes) {
            if (label.equals(row.path("label").asText())) return row;
            JsonNode hit = findByLabel(row.path("children"), label);
            if (hit != null) return hit;
        }
        return null;
    }

    private static JsonNode findByPath(JsonNode nodes, String path) {
        for (JsonNode row : nodes) {
            if (path.equals(row.path("path").asText())) return row;
            JsonNode hit = findByPath(row.path("children"), path);
            if (hit != null) return hit;
        }
        return null;
    }

    private record Resp(int status, String body) {
    }

    private Resp call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 断言用的辅助：把树摊平成一串路径，方便「这几条应当在 / 不应当在」的断言。 */
    static List<String> pathsOf(JsonNode nodes) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : nodes) {
            String path = row.path("path").asText();
            if (!path.isEmpty()) out.add(path);
            out.addAll(pathsOf(row.path("children")));
        }
        return out;
    }
}
