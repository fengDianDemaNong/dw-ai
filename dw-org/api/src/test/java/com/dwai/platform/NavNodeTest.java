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
            // V27 加的两个工作台入口。**必须列进来**：清理逻辑按「不在 SEED_IDS 里 = 本用例
            // 自己建的」来删，漏了它们会被当垃圾删掉，而后面的用例再读侧栏就少两行 ——
            // 症状是「别的用例随机变红」，不是本类报错。
            "nav-sys-modules", "nav-sys-compute",
            "nav-proj", "nav-proj-members");

    /** 工作台壳的 org 自有入口（非管理员也看得到的那一条）—— 好几条用例拿它当锚点。 */
    private static final String SYS_PROJECTS = "/org/workbench/projects";

    @Autowired
    private MockMvc mvc;

    /** 清理用（直删绕开业务守卫），见 {@code setUp} 里的说明。 */
    @Autowired
    private JdbcTemplate jdbc;

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
        //
        // **直删，不走 DELETE 接口**：本类用例建的多是 org 自有节点，而那一类现在**只能停用**
        // （守卫见 `NavNodeService.delete()`）—— 走接口会被 400 挡下，行留在库里，
        // 症状是「别的用例随机变红」（下一条用例撞上残留的 uk_nav_node），不是本用例报错。
        // 清理不是被测行为，绕开业务守卫是对的。
        jdbc.update("DELETE FROM nav_entry_links");
        for (String id : new ArrayList<>(ownedNodeIds())) {
            jdbc.update("DELETE FROM nav_nodes WHERE id = ?", id);
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

    /**
     * 删一个节点连带整棵子树，返回的条数就是前端二次确认里的数字。
     *
     * <p>这一支建的是**从产品来的**节点（手工复制档：`product` 非空、`mounted` 关着）。
     * 不能建 org 自有的 —— 那一类现在只能停用（见 {@link #orgOwnedMenuCannotBeDeleted}），
     * 会在这里被 400 挡下。级联本身与节点是哪一类无关，换成产品类不损失覆盖面。
     */
    @Test
    void deleteCascadesToSubtreeAndReportsTheCount() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"要删的\",\"product\":\"warehouse\"}").path("id").asText();
        String sub = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"子\",\"product\":\"warehouse\"}")
                .path("id").asText();
        create("{\"scope\":\"project\",\"parentId\":\"" + sub + "\",\"title\":\"孙\",\"product\":\"warehouse\",\"path\":\"/x/sun\"}");

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
     * org 自己的菜单（页面 / 目录 / 入口页 / 外链）<b>删不掉</b>，只能停用；
     * 从产品来的（挂载 / 手工复制）照常删得掉。
     *
     * <p>判据是「{@code product} 是否为空」。这四类都要各测一遍，而不是只测页面那一类：
     * 前端给的删除按钮走 {@code sourceOf}（`entryPage` / `externalUrl` 各是一个分支），
     * 后端守卫走 {@code isProductNode} —— 两条判据一旦有一个漂移，
     * 表现就是「界面上有删除、点了 400」或反过来「能删的却只给停用」，各自看着都像对的。
     *
     * <p>顺带守住「守卫没有过宽」：产品类那两条必须 200，否则这个功能就成了「什么都删不掉」。
     */
    @Test
    void orgOwnedMenuCannotBeDeletedOnlyDisabled() throws Exception {
        String page = create("{\"scope\":\"project\",\"title\":\"自有页面\",\"path\":\"/org/workbench/projects\"}")
                .path("id").asText();
        String dir = create("{\"scope\":\"project\",\"title\":\"自有目录\"}").path("id").asText();
        String entry = create("{\"scope\":\"project\",\"title\":\"自有入口页\",\"entryPage\":true}").path("id").asText();
        String ext = create("{\"scope\":\"project\",\"title\":\"自有外链\","
                + "\"externalUrl\":\"https://grafana.example.com/d/abc\"}").path("id").asText();

        for (String id : List.of(page, dir, entry, ext)) {
            Resp res = del(id);
            assertEquals(400, res.status(), "org 自己的菜单不该删得掉（" + id + "）: " + res.body());
            assertNotNull(findById(adminTree(null), id), "被拒之后这一行必须还在（" + id + "）");
        }

        // 守卫没有过宽：从产品来的照样删得掉 —— 手工复制档（product 非空、mounted 关着）。
        String manual = create("{\"scope\":\"project\",\"title\":\"产品来的\",\"product\":\"warehouse\"}")
                .path("id").asText();
        assertEquals(200, del(manual).status(), "产品节点应当照常能删");

        // 并且「拒了之后还能停用」—— 这才是给管理员的那条出路，光拒不做等于没路走。
        assertEquals(200, patchNode(page, "{\"enabled\":false}").status());
        assertNull(findByLabel(navTree(memberToken), "自有页面"), "停用后侧栏里不该还有它");
        assertEquals(200, patchNode(page, "{\"enabled\":true}").status(), "停用要能再启用");
    }

    /**
     * <b>级联也是个后门，要一起堵</b>：把一个 org 自有的菜单挂在产品节点下面，
     * 再删那个产品节点 —— 只判「要删的这一条自己」的话，守卫就被绕过去了，
     * 而管理员看到的是「我删的是产品那一行」。
     *
     * <p>文案要报出**是哪一个**子节点挡住了：只回「不能删除」的话，管理员对着
     * 一整棵子树不知道该处理谁。
     */
    @Test
    void deleteCannotSmuggleOutOrgOwnedChildInSubtree() throws Exception {
        String parent = create("{\"scope\":\"project\",\"title\":\"产品父\",\"product\":\"warehouse\"}")
                .path("id").asText();
        String kid = create("{\"scope\":\"project\",\"parentId\":\"" + parent + "\","
                + "\"title\":\"挡路的自有菜单\",\"path\":\"/org/workbench/projects\"}").path("id").asText();

        Resp res = del(parent);
        assertEquals(400, res.status(), "子树里混着 org 自有的菜单时，整支都不该删得掉: " + res.body());
        assertTrue(res.body().contains("挡路的自有菜单"),
                "要说清是哪一个子节点挡住了 —— 否则管理员对着整支不知道处理谁: " + res.body());
        assertNotNull(findById(adminTree(null), parent), "被拒之后父节点也要还在");
        assertNotNull(findById(adminTree(null), kid), "被拒之后子节点要还在");
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
    // 排序：同层序号 + 上移 / 下移
    // ------------------------------------------------------------------

    /*
     * 下面几条一律用 **ASCII 标题**（A/B/C、L1/R1）：并列时「谁在前」由 {@code title} 的
     * **字符序**兜底，而这个序各库不同 —— H2 按码点（`乙` U+4E59 < `甲` U+7532），
     * MySQL 按 collation，对中文给出的先后未必一致。先前用「甲乙丙」写的版本就栽在这儿：
     * 失败信息是「期望 [甲, 丙, 乙]，实际 [乙, 丙, 甲]」—— 看着像排序逻辑错了，其实是字符序。
     *
     * 顺带一提，「并列看字符序」本身就是这一版要修的病根：同层全 0 时先后不由管理员决定。
     * 缺省改成「同层最大 + 10」之后不再产生新并列，但老数据里的还在，所以
     * `moveNormalizesTiedSortValuesAcrossTheWholeLevel` 专门盯住「一次移动把它归一」。
     */

    /**
     * 不传 {@code sortOrder} 时**落在同层末尾**，值是 10/20/30。
     *
     * <p>这条同时钉两件事：缺省不再是 0（原先同层全 0 → 并列 → 谁在前由 {@code title} 的
     * 字符序说了算，管理员没填过的两条菜单，先后不由他决定），以及量纲就是
     * {@code SORT_STEP} 的倍数 —— 别的用例（{@link #moveSwapsWithinTheSameLevelAndRenumbers}）
     * 拿它当基准。
     */
    @Test
    void siblingsGetSequentialSortOrderByDefault() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"排序：缺省\"}").path("id").asText();
        JsonNode a = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"A\"}");
        JsonNode b = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"B\"}");
        JsonNode c = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"C\"}");

        assertEquals(10, a.path("sortOrder").asInt(), a.toString());
        assertEquals(20, b.path("sortOrder").asInt(), b.toString());
        assertEquals(30, c.path("sortOrder").asInt(), c.toString());
        assertEquals(List.of("A", "B", "C"), titlesUnder(dir), "顺序就是建立的先后");
    }

    /**
     * 显式传的 {@code sortOrder} 仍然说了算。
     *
     * <p>这是一条**护栏**：{@code dw-org/ui/e2e/workbench.spec.ts} 里「侧栏按登记的顺序排」
     * 那条用例靠显式值构造反例，改缺省规则不能把「显式优先」一起改掉。
     */
    @Test
    void explicitSortOrderStillWins() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"排序：显式\"}").path("id").asText();
        create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"A\"}");
        // 5 比缺省的 10 小 → 后建的反而排前面
        create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"B\",\"sortOrder\":5}");
        assertEquals(List.of("B", "A"), titlesUnder(dir));
    }

    /** 上移 / 下移一格：换位 + 整层重编号（值恰好回到 10/20/30）。 */
    @Test
    void moveSwapsWithinTheSameLevelAndRenumbers() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"排序：移动\"}").path("id").asText();
        create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"A\"}");
        create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"B\"}");
        String c = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"C\"}").path("id").asText();

        Resp up = moveNode(c, -1);
        assertEquals(200, up.status(), up.body());
        assertTrue(MAPPER.readTree(up.body()).path("changed").asBoolean(), up.body());
        assertEquals(List.of("A", "C", "B"), titlesUnder(dir));
        assertSortValues(dir, 10, 20, 30);

        Resp down = moveNode(c, 1);
        assertEquals(200, down.status(), down.body());
        assertEquals(List.of("A", "B", "C"), titlesUnder(dir), "再下移一格就回到原样");
        assertSortValues(dir, 10, 20, 30);
    }

    /** 已经在首 / 末位时是 {@code changed:false} 且**一个字都不写**（不是错误）。 */
    @Test
    void moveAtTheEdgeChangesNothing() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"排序：边界\"}").path("id").asText();
        String a = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"A\"}").path("id").asText();
        String b = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"B\"}").path("id").asText();

        Resp upFirst = moveNode(a, -1);
        assertEquals(200, upFirst.status(), upFirst.body());
        assertFalse(MAPPER.readTree(upFirst.body()).path("changed").asBoolean(), upFirst.body());

        Resp downLast = moveNode(b, 1);
        assertEquals(200, downLast.status(), downLast.body());
        assertFalse(MAPPER.readTree(downLast.body()).path("changed").asBoolean(), downLast.body());

        assertEquals(List.of("A", "B"), titlesUnder(dir), "到头了就不该动");
        assertSortValues(dir, 10, 20);
    }

    /**
     * 一次移动把**整层**归一：老数据的并列（全 0）与导入带来的外来值都在这一次写里消失。
     *
     * <p>只交换两个值的话并列会留下来，而并列之下「谁在前」由 {@code title} 决定 ——
     * 管理员点了上移却没动，是最难查的那一类 bug。
     */
    @Test
    void moveNormalizesTiedSortValuesAcrossTheWholeLevel() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"排序：归一\"}").path("id").asText();
        String a = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"A\"}").path("id").asText();
        String b = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"B\"}").path("id").asText();
        String c = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"C\"}").path("id").asText();

        // 造出老数据那种并列：两条都改回 0。此时顺序靠 title 兜底，看着仍是 A,B,C
        assertEquals(200, patchNode(a, "{\"sortOrder\":0}").status());
        assertEquals(200, patchNode(b, "{\"sortOrder\":0}").status());
        assertSortValues(dir, 0, 0, 30);

        assertEquals(200, moveNode(c, -1).status());
        assertSortValues(dir, 10, 20, 30);
        assertEquals(List.of("A", "C", "B"), titlesUnder(dir));
    }

    /** 移动只碰自己的兄弟：另一个父节点下的行、另一个壳里的行都不动。 */
    @Test
    void moveOnlyTouchesItsOwnSiblings() throws Exception {
        String one = create("{\"scope\":\"project\",\"title\":\"排序：左\"}").path("id").asText();
        String two = create("{\"scope\":\"project\",\"title\":\"排序：右\"}").path("id").asText();
        String left = create("{\"scope\":\"project\",\"parentId\":\"" + one + "\",\"title\":\"L1\"}").path("id").asText();
        create("{\"scope\":\"project\",\"parentId\":\"" + one + "\",\"title\":\"L2\"}");
        create("{\"scope\":\"project\",\"parentId\":\"" + two + "\",\"title\":\"R1\"}");
        create("{\"scope\":\"project\",\"parentId\":\"" + two + "\",\"title\":\"R2\"}");

        assertEquals(200, moveNode(left, 1).status());

        assertEquals(List.of("L2", "L1"), titlesUnder(one));
        assertEquals(List.of("R1", "R2"), titlesUnder(two), "另一个父节点下的顺序不该被动");
        assertSortValues(two, 10, 20);
    }

    /**
     * 顶层（{@code parentId} 为空）也要能移动。
     *
     * <p>单拎出来是因为这一档的「同层」判据是**同壳**而不是同父 —— 按 {@code parentId} 分组
     * 时最容易漏掉的就是 {@code parentId = ''} 这一支。
     */
    @Test
    void topLevelNodesCanMoveToo() throws Exception {
        create("{\"scope\":\"workbench\",\"title\":\"排序：顶层T1\"}");
        String y = create("{\"scope\":\"workbench\",\"title\":\"排序：顶层T2\"}").path("id").asText();

        assertEquals(200, moveNode(y, -1).status());

        List<String> titles = topLevelTitles("workbench");
        int atY = titles.indexOf("排序：顶层T2");
        int atX = titles.indexOf("排序：顶层T1");
        assertTrue(atY >= 0 && atX >= 0 && atY < atX, "上移之后 T2 应当排在 T1 前面: " + titles);
    }

    /** 越界之外的两种坏输入：不存在的 id（404）与非法 delta（400），以及门禁。 */
    @Test
    void moveRejectsBadDeltaAndUnknownId() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"排序：坏输入\"}").path("id").asText();

        assertEquals(404, moveNode("nav-not-a-real-id", 1).status());
        assertEquals(400, moveNode(id, 0).status(), "delta=0 不是「不动」，是不认识的输入");
        assertEquals(400, moveNode(id, 2).status());
        assertEquals(400, moveNode(id, -2).status());
        assertEquals(403, call(post("/api/v1/platform/nav-nodes/" + id + "/move")
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":1}")).status(), "移动是平台配置动作，普通成员不该能调");
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

    /** 删一个节点，**不断言状态码** —— 守卫用例要看的就是那个非 200 的返回。 */
    private Resp del(String id) throws Exception {
        return call(delete("/api/v1/platform/nav-nodes/" + id)
                .header("Authorization", "Bearer " + adminToken));
    }

    /** 把一个节点在同层里上移 / 下移一格。**不断言状态码** —— 越界与非法 delta 要看那个返回。 */
    private Resp moveNode(String id, int delta) throws Exception {
        return call(post("/api/v1/platform/nav-nodes/" + id + "/move")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":" + delta + "}"));
    }

    /** 某个父节点下的标题顺序（断言「谁在前」用）。 */
    private List<String> titlesUnder(String parentId) throws Exception {
        List<String> out = new ArrayList<>();
        findById(adminTree("project"), parentId).path("children")
                .forEach(n -> out.add(n.path("label").asText()));
        return out;
    }

    /** 某个壳的**顶层**（{@code parentId} 为空）标题顺序。 */
    private List<String> topLevelTitles(String scope) throws Exception {
        List<String> out = new ArrayList<>();
        adminTree(scope).forEach(n -> out.add(n.path("label").asText()));
        return out;
    }

    /** 某个父节点下各行的 {@code sortOrder} —— 重编号（消除并列）那几条用例靠它。 */
    private void assertSortValues(String parentId, int... expected) throws Exception {
        JsonNode kids = findById(adminTree("project"), parentId).path("children");
        List<Integer> got = new ArrayList<>();
        kids.forEach(n -> got.add(n.path("sortOrder").asInt()));
        List<Integer> want = new ArrayList<>();
        for (int v : expected) want.add(v);
        assertEquals(want, got, kids.toString());
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
