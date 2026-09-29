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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 菜单功能扩展（V29）：<b>入口页</b>（引用式容器）与<b>外链菜单</b>。
 *
 * <p>与 {@code NavNodeTest} 分开的理由：那一类测的是「一棵父子树」的增删改查，
 * 这两类测的是**另一种关系**（多对多引用）与**另一种节点**（指向平台外的地址、
 * 带凭据、有自己的可见性开关）。合成一个文件会让 setup 里既有「建树」又有「配凭据」。
 *
 * <h2>这一层要钉住的四件事</h2>
 *
 * <ol>
 *   <li><b>被挂的菜单在原位置也还在</b> —— 这是需求 1 的核心，也是「引用而非父子」的
 *       唯一可观测差别。少了这条断言，实现退化成父子关系也照样全绿。</li>
 *   <li><b>凭据「写了就不回显」</b>，而「空值 = 保持原值」—— 后半条更容易漏：
 *       表单里那一格在编辑态本来就是空的，把空当清空的话，改一次标题就会把 token 抹掉。</li>
 *   <li><b>外链的可见性走独立开关</b>，两档，与 {@code admin_only} 互斥。</li>
 *   <li><b>token 拼进查询串</b>，且不破坏目标地址原有的参数。</li>
 * </ol>
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_nav_entry;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=nav-entry-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456",
        // 加密密钥固定住（配置项就是 `dwai.llmSecret`，不是 `dwai.llm.secret`）：
        // 凭据的加解密要跨请求复用同一把钥匙 —— 否则本类里「读回来的 token 还是那个」
        // 就只是同一把随机钥匙撞出来的巧合
        "dwai.llmSecret=nav-entry-test-secret"
})
@AutoConfigureMockMvc
class NavEntryExternalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_CODE = "nave_t1";

    /** V23/V27 的种子 id —— 清库时留着（理由见 {@code NavNodeTest.SEED_IDS}）。 */
    private static final Set<String> SEED_IDS = Set.of(
            "nav-sys", "nav-sys-users", "nav-sys-roles", "nav-sys-projects",
            "nav-sys-knowledge", "nav-sys-settings", "nav-sys-modules", "nav-sys-compute",
            "nav-proj", "nav-proj-members");

    private static final String EXTERNAL_URL = "https://grafana.example.com/d/abc?orgId=1";

    @Autowired
    private MockMvc mvc;

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
                    .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"入口页验证租户\",\"adminUserId\":\"admin\"}"));
            assertEquals(200, created.status(), "创建租户失败: " + created.body());
            tenantId = MAPPER.readTree(created.body()).path("id").asText();

            createUser("nave_member", "member");
            createUser("nave_admin", "admin");
            memberToken = login("nave_member", "123456");
            tenantAdminToken = login("nave_admin", "123456");
        }

        // **直删，不走 DELETE 接口**：本类用例建的多是 org 自有节点（入口页 / 外链），
        // 而那一类现在**只能停用**（守卫见 `NavNodeService.delete()`）—— 走接口会被 400 挡下，
        // 行留在库里，症状是「别的用例随机变红」（下一条用例撞上残留的 uk_nav_node），
        // 不是本用例报错。清理不是被测行为，绕开业务守卫是对的。
        jdbc.update("DELETE FROM nav_entry_links");
        for (String id : new ArrayList<>(ownedNodeIds())) {
            jdbc.update("DELETE FROM nav_nodes WHERE id = ?", id);
        }
        // 关联行单独清一次：上面的直删也会连带清，但**故意漏一行**也测不出来 ——
        // 留着上一个用例的关联，下一个用例读到多余的 items 会表现为「表里多了一行」。
        jdbc.update("DELETE FROM nav_entry_links");
    }

    // ------------------------------------------------------------------
    // 需求 1：入口页（引用式容器）
    // ------------------------------------------------------------------

    /**
     * 需求 1 的核心：把两个菜单挂进入口页，<b>它们在原位置也还在</b>。
     *
     * <p>这一条是「引用」与「父子」的分水岭：换成父子关系实现，侧栏里原来那两条会消失、
     * 只在入口页下出现，而「入口页的表里有几项」照样是对的 —— 只测表内容的话全绿。
     */
    @Test
    void entryPageListsLinkedMenusAndTheyStayInPlace() throws Exception {
        String dir = create("{\"scope\":\"project\",\"title\":\"分组\"}").path("id").asText();
        String a = create("{\"scope\":\"project\",\"parentId\":\"" + dir + "\",\"title\":\"菜单A\","
                + "\"path\":\"/x/a\"}").path("id").asText();
        String b = create("{\"scope\":\"project\",\"title\":\"菜单B\",\"path\":\"/x/b\"}").path("id").asText();

        String entry = create("{\"scope\":\"project\",\"title\":\"数据总览\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + a + "\",\"" + b + "\"]}").path("id").asText();

        // 管理面回显挂了哪些（编辑抽屉要拿它回填多选树）
        JsonNode admin = findById(adminTree(null), entry);
        assertNotNull(admin, "管理面应当有这个入口页: " + adminTree(null));
        assertEquals(2, admin.path("links").size(), admin.toString());
        // 回显是**一份带类型的列表**（V31 之前是两个扁平数组）：前端靠 kind 分流，
        // 不靠 id 的形状猜 —— 猜错会把清单 id 当 nav id 提交回来
        assertEquals("node", admin.path("links").get(0).path("kind").asText(), admin.toString());
        assertEquals(a, admin.path("links").get(0).path("target").asText(), admin.toString());
        assertTrue(admin.path("entryPage").asBoolean(), "entryPage 要回传: " + admin);
        // path 由服务端写成模板（`{node}` 留给前端替换成节点 id）—— 管理员填不了，
        // 因为 id 是服务端生成的
        assertEquals("/org/project/{code}/entry/{node}", admin.path("path").asText(),
                "入口页的 path 应当是模板，不是空串（空串 = 目录节点，管理页会显示成目录）: " + admin);

        // 消费面：表内容
        JsonNode page = entryPage(entry, memberToken);
        assertEquals("数据总览", page.path("label").asText(), page.toString());
        List<String> labels = labelsOf(page.path("items"));
        assertEquals(List.of("菜单A", "菜单B"), labels, "表里应当有挂进来的两条: " + page);

        // 核心断言：被挂的菜单在原位置也还在（引用，不是搬走）
        JsonNode nav = navTree(memberToken);
        assertNotNull(findByPath(nav, "/x/a"), "被挂的菜单在原位置也应当在: " + nav);
        assertNotNull(findByPath(nav, "/x/b"), "被挂的菜单在原位置也应当在: " + nav);
    }

    /**
     * 挂在入口页里的项，判权结论与它在侧栏里一致 —— 因为走的是同一个 {@code render()}。
     *
     * <p>这里用一个「被停用」的 target：它在侧栏里不出现，在表里也不该出现。另写一套取数
     * 的话，这里是「表里看得到、侧栏看不到」，而两边都各自看着没问题。
     */
    @Test
    void entryPageItemsGoThroughTheSameVisibilityRules() throws Exception {
        String visible = create("{\"scope\":\"project\",\"title\":\"可见\",\"path\":\"/x/on\"}")
                .path("id").asText();
        String hidden = create("{\"scope\":\"project\",\"title\":\"停用的\",\"path\":\"/x/off\","
                + "\"enabled\":false}").path("id").asText();
        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + visible + "\",\"" + hidden + "\"]}").path("id").asText();

        List<String> labels = labelsOf(entryPage(entry, memberToken).path("items"));
        assertEquals(List.of("可见"), labels, "停用的菜单不该出现在表里（与侧栏同一判据）");
    }

    /**
     * Tab 顺序 = <b>挂的顺序</b>（V31，用户裁定「行顺序 = Tab 顺序」）。
     *
     * <p>这推翻了 V29 的「跟随 target 自己的 {@code sortOrder}」。动机是 Tab 名可以自己起
     * 之后，两条同名菜单只能靠顺序区分（用户截图里两个 Tab 都叫「概况」）。
     *
     * <p>这里刻意让 target 自己的排序与挂的顺序<b>相反</b>：两条断言能同时成立的写法
     * 只有一种真相，不然改回旧行为这一条也照样绿。
     */
    @Test
    void entryPageOrderFollowsTheLinkOrder() throws Exception {
        String later = create("{\"scope\":\"project\",\"title\":\"排后面\",\"path\":\"/x/z\","
                + "\"sortOrder\":20}").path("id").asText();
        String earlier = create("{\"scope\":\"project\",\"title\":\"排前面\",\"path\":\"/x/y\","
                + "\"sortOrder\":10}").path("id").asText();

        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + later + "\",\"" + earlier + "\"]}").path("id").asText();

        assertEquals(List.of("排后面", "排前面"), labelsOf(entryPage(entry, memberToken).path("items")),
                "Tab 顺序应当跟着挂的顺序，不再跟着 target 自己的 sortOrder");
    }

    /**
     * <b>V31 之前存的老入口页顺序不变</b> —— 迁移刻意不回填 {@code sort_order}，
     * 读侧的稳定排序必须让它们退回旧行为（本站菜单按 target 自己的排序）。
     *
     * <p>这条兼容不是洁癖：真库里有已经配好的入口页（用户报问题的那几张表就是），
     * 迁移后它们 {@code sort_order} 全是 0；若排序不稳定或按 id 兜底，
     * 管理员升个版本就会发现自己排好的 Tab 顺序乱了，而他什么都没动过。
     *
     * <p>老数据的状态只能直接改库造出来：走接口建的关联行现在都带真实行号。
     */
    @Test
    void legacyLinksWithoutSortOrderKeepTheOldOrder() throws Exception {
        String later = create("{\"scope\":\"project\",\"title\":\"排后面\",\"path\":\"/x/z\","
                + "\"sortOrder\":20}").path("id").asText();
        String earlier = create("{\"scope\":\"project\",\"title\":\"排前面\",\"path\":\"/x/y\","
                + "\"sortOrder\":10}").path("id").asText();

        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + later + "\",\"" + earlier + "\"]}").path("id").asText();

        // 模拟 V31 迁移后的老行：sort_order 落成默认的 0，label 为 NULL
        jdbc.update("update nav_entry_links set sort_order = 0, label = null where entry_id = ?", entry);

        assertEquals(List.of("排前面", "排后面"), labelsOf(entryPage(entry, memberToken).path("items")),
                "老行（sort_order 全 0）排完应当还是旧顺序，升级不该动到已有的 Tab 顺序");
    }

    /**
     * Tab 可以自己起名字，<b>空 = 用被挂菜单自己的标题</b>（V31）。
     *
     * <p>这是用户报的原问题：一个入口页挂两条名字相同的菜单时，光看表分不出谁是谁。
     * 这里两条 target 标题一模一样，只有改了名的那一条能靠 label 区分 ——
     * 若覆盖逻辑写反（拿 target 的标题盖掉自定义名），两条会双双变回「概况」，断言立刻红。
     */
    @Test
    void linkLabelOverridesTheTargetTitle() throws Exception {
        String a = create("{\"scope\":\"project\",\"title\":\"概况\",\"path\":\"/x/a\"}").path("id").asText();
        // 第二条款意挂到**另一个父节点**下：`uk_nav_node (scope, parent_id, title)` 让同层
        // 重名建不出来，顶层也管（V23：空串是普通值，约束对顶层同样生效）。这里要造的是
        // 「两条**同名菜单**」，不是「同层同名」—— 两条都建在顶层的话，第二次 create 必然 400。
        String holder = create("{\"scope\":\"project\",\"title\":\"归档\"}").path("id").asText();
        String b = create("{\"scope\":\"project\",\"parentId\":\"" + holder + "\",\"title\":\"概况\",\"path\":\"/x/b\"}")
                .path("id").asText();

        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true,"
                + "\"links\":[{\"kind\":\"node\",\"target\":\"" + a + "\",\"label\":\"销售概况\"},"
                + "{\"kind\":\"node\",\"target\":\"" + b + "\"}]}").path("id").asText();

        assertEquals(List.of("销售概况", "概况"), labelsOf(entryPage(entry, memberToken).path("items")),
                "改名的那条用自定义名，没改名的用菜单自己的标题");

        // 回显要带上 label，否则编辑一次就把它抹掉了
        JsonNode admin = findById(adminTree(null), entry);
        assertNotNull(admin, "管理面应当有这个入口页");
        assertEquals("销售概况", admin.path("links").get(0).path("label").asText(), admin.toString());
        assertEquals("", admin.path("links").get(1).path("label").asText(), admin.toString());
    }

    /** Tab 名最长 64（与 {@code nav_nodes.title} 同宽）—— 超了在写入时就拒，别等落库报错。 */
    @Test
    void linkLabelHasALengthLimit() throws Exception {
        String a = create("{\"scope\":\"project\",\"title\":\"概况\",\"path\":\"/x/a\"}").path("id").asText();

        Resp res = postNode("{\"scope\":\"project\",\"title\":\"总览2\","
                + "\"entryPage\":true,\"links\":[{\"kind\":\"node\",\"target\":\"" + a + "\","
                + "\"label\":\"" + "字".repeat(65) + "\"}]}");
        assertEquals(400, res.status(), res.body());
    }

    /**
     * {@code links} 与老的 {@code linkTargets} / {@code productLinks} 不能同时传。
     *
     * <p>同时传时「以哪一份为准」没有唯一说法，而挑错的那一次会<b>静默丢掉</b>管理员
     * 刚排好的 Tab 顺序 —— 所以宁可 400。
     */
    @Test
    void linksCannotBeMixedWithTheLegacyFields() throws Exception {
        String a = create("{\"scope\":\"project\",\"title\":\"菜单A\",\"path\":\"/x/a\"}").path("id").asText();

        Resp res = postNode("{\"scope\":\"project\",\"title\":\"总览3\","
                + "\"entryPage\":true,\"links\":[{\"kind\":\"node\",\"target\":\"" + a + "\"}],"
                + "\"linkTargets\":[\"" + a + "\"]}");
        assertEquals(400, res.status(), res.body());
    }

    /** {@code kind} 写错要当场拒：认不出来时按 node 处理会把清单 id 送到 nav_nodes 里查。 */
    @Test
    void unknownLinkKindIsRejected() throws Exception {
        Resp res = postNode("{\"scope\":\"project\",\"title\":\"总览4\","
                + "\"entryPage\":true,\"links\":[{\"kind\":\"menu\",\"target\":\"nav-x\"}]}");
        assertEquals(400, res.status(), res.body());
    }

    /** 空数组 = 清空（{@code links} 是两类一起的全量替换，与它替代的两个字段同一口径）。 */
    @Test
    void emptyLinksClearsEverything() throws Exception {
        String a = create("{\"scope\":\"project\",\"title\":\"菜单A\",\"path\":\"/x/a\"}").path("id").asText();
        String entry = create("{\"scope\":\"project\",\"title\":\"总览5\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + a + "\"]}").path("id").asText();
        assertEquals(1, entryPage(entry, memberToken).path("items").size());

        Resp res = patchNode(entry, "{\"links\":[]}");
        assertEquals(200, res.status(), res.body());
        assertEquals(0, entryPage(entry, memberToken).path("items").size(), "空数组该把挂的菜单清光");
    }

    /** 空入口页是合法状态（建好了还没挂东西），它**不是**目录节点：path 有模板、页面打得开。 */
    @Test
    void emptyEntryPageIsStillAPage() throws Exception {
        String entry = create("{\"scope\":\"project\",\"title\":\"空总览\",\"entryPage\":true}")
                .path("id").asText();

        JsonNode page = entryPage(entry, memberToken);
        assertEquals(0, page.path("items").size(), page.toString());
        JsonNode admin = findById(adminTree(null), entry);
        assertNotEquals("", admin.path("path").asText(),
                "没有 path 的话它会被当成目录节点渲染成不可点，而它明明有页面: " + admin);
    }

    // ------------------------------------------------------------------
    // 需求 1 的校验：挂谁、谁来挂
    // ------------------------------------------------------------------

    @Test
    void entryPageCannotLinkItselfOrAnotherEntryPage() throws Exception {
        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true}")
                .path("id").asText();

        Resp self = patchNode(entry, "{\"linkTargets\":[\"" + entry + "\"]}");
        assertEquals(400, self.status(), "入口页不该能把自己挂进来: " + self.body());

        String other = create("{\"scope\":\"project\",\"title\":\"另一个总览\",\"entryPage\":true}")
                .path("id").asText();
        Resp nested = patchNode(entry, "{\"linkTargets\":[\"" + other + "\"]}");
        assertEquals(400, nested.status(),
                "表里再嵌一张表说不清该显示什么，这一条同时把多级循环全挡掉: " + nested.body());
    }

    /** 跨壳挂会让「按壳取树」时那一支整个丢掉 —— 与父子关系同一个理由。 */
    @Test
    void entryPageCannotLinkAcrossShells() throws Exception {
        String wb = create("{\"scope\":\"workbench\",\"title\":\"工作台项\",\"path\":\"/org/workbench/x\"}")
                .path("id").asText();
        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true}")
                .path("id").asText();

        Resp res = patchNode(entry, "{\"linkTargets\":[\"" + wb + "\"]}");
        assertEquals(400, res.status(), "不能把工作台壳的菜单挂进项目壳的入口页: " + res.body());
        assertTrue(res.body().contains("workbench"), "报错要说清它在哪个壳里: " + res.body());
    }

    /** 挂一个不存在的 id 要**报错而不是静默忽略** —— 静默会让管理员以为挂上了。 */
    @Test
    void linkingAnUnknownNodeIsRejected() throws Exception {
        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true}")
                .path("id").asText();
        Resp res = patchNode(entry, "{\"linkTargets\":[\"nope\"]}");
        assertEquals(400, res.status(), res.body());
    }

    /** 不是入口页却传了 {@code linkTargets}：报错而不是静默忽略。 */
    @Test
    void linksOnANonEntryNodeAreRejected() throws Exception {
        String plain = create("{\"scope\":\"project\",\"title\":\"普通\",\"path\":\"/x/p\"}")
                .path("id").asText();
        String target = create("{\"scope\":\"project\",\"title\":\"目标\",\"path\":\"/x/t\"}")
                .path("id").asText();

        Resp res = patchNode(plain, "{\"linkTargets\":[\"" + target + "\"]}");
        assertEquals(400, res.status(), res.body());
    }

    /** 传空列表 = 清空；不传 = 不改（全量替换语义的两半）。 */
    @Test
    void emptyListClearsLinksAndMissingFieldKeepsThem() throws Exception {
        String target = create("{\"scope\":\"project\",\"title\":\"目标\",\"path\":\"/x/t\"}")
                .path("id").asText();
        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + target + "\"]}").path("id").asText();
        assertEquals(1, entryPage(entry, memberToken).path("items").size());

        // 不传 linkTargets：只改标题，挂的东西不该受影响
        assertEquals(200, patchNode(entry, "{\"title\":\"总览改名\"}").status());
        assertEquals(1, entryPage(entry, memberToken).path("items").size(),
                "不传 linkTargets 时不该动关联（否则改一次标题就把表清空了）");

        // 传空列表：明确清空
        assertEquals(200, patchNode(entry, "{\"linkTargets\":[]}").status());
        assertEquals(0, entryPage(entry, memberToken).path("items").size());
        assertEquals(0, linkRows(entry), "关联行要真删掉，不是留着不显示");
    }

    // ------------------------------------------------------------------
    // 需求 2：外链菜单
    // ------------------------------------------------------------------

    /** 外跳：侧栏项直接带目标地址与 {@code external} 标记（走叶子的 {@code <a target="_blank">}）。 */
    @Test
    void jumpExternalCarriesTheUrlToTheSidebar() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"Grafana\",\"externalUrl\":\"" + EXTERNAL_URL
                + "\",\"openMode\":\"jump\"}").path("id").asText();

        JsonNode node = findById(navTree(memberToken), id);
        assertNotNull(node, "外链应当出现在侧栏里: " + navTree(memberToken));
        assertTrue(node.path("external").asBoolean(), node.toString());
        assertEquals(EXTERNAL_URL, node.path("href").asText(), node.toString());
        assertEquals("", node.path("path").asText(), "外跳没有壳内地址");
        assertFalse(node.path("hasToken").asBoolean(), "没配 token 时应当是 false: " + node);
    }

    /** 内嵌：侧栏项指向**壳内那一页**（前端在那页里放 iframe），带 `{node}` 模板。 */
    @Test
    void embedExternalRoutesInsideTheShell() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"Grafana\",\"externalUrl\":\"" + EXTERNAL_URL
                + "\",\"openMode\":\"embed\"}").path("id").asText();

        JsonNode node = findById(navTree(memberToken), id);
        assertNotNull(node, node + "");
        assertEquals("/org/project/{code}/external/{node}", node.path("path").asText(), node.toString());
    }

    /**
     * 外链的可见性是**独立开关**，只有两档 —— 与 {@code admin_only} 不是一回事，
     * 所以这里单独验一次它对普通成员的效果（整条不返回，而不是置灰）。
     */
    @Test
    void tenantAdminOnlyExternalIsHiddenFromMembers() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"内部看板\",\"externalUrl\":\"" + EXTERNAL_URL
                + "\",\"visibility\":\"tenant_admin\"}").path("id").asText();

        assertNull(findById(navTree(memberToken), id), "普通成员不该看到它: " + navTree(memberToken));
        assertNotNull(findById(navTree(tenantAdminToken), id),
                "租户管理员应当看到它: " + navTree(tenantAdminToken));
    }

    /** 外链与 {@code admin_only} 互斥：两个开关叠在一起时「为什么看不到」没有唯一答案。 */
    @Test
    void externalCannotUseAdminOnly() throws Exception {
        Resp res = postNode("{\"scope\":\"project\",\"title\":\"外链\",\"externalUrl\":\"" + EXTERNAL_URL
                + "\",\"adminOnly\":true}");
        assertEquals(400, res.status(), res.body());
    }

    /** 外链地址只允许 http / https —— 它会落到 {@code :href} 与 iframe 的 src 上。 */
    @Test
    void externalUrlMustBeHttpOrHttps() throws Exception {
        assertEquals(400, postNode("{\"scope\":\"project\",\"title\":\"坏链\","
                + "\"externalUrl\":\"javascript:alert(1)\"}").status(),
                "javascript: 会被 :href 执行，这是本次新增的唯一一处「管理员可控的任意 URL」");
        assertEquals(400, postNode("{\"scope\":\"project\",\"title\":\"坏链2\","
                + "\"externalUrl\":\"file:///etc/passwd\"}").status());
        // 大小写不敏感（管理员手填 URL 时常见）
        assertEquals(200, postNode("{\"scope\":\"project\",\"title\":\"大写\","
                + "\"externalUrl\":\"HTTPS://example.com\"}").status());
    }

    /** 入口页与外链互斥、两者都与产品码互斥。 */
    @Test
    void entryExternalAndProductAreMutuallyExclusive() throws Exception {
        Resp both = postNode("{\"scope\":\"project\",\"title\":\"都要\",\"entryPage\":true,"
                + "\"externalUrl\":\"" + EXTERNAL_URL + "\"}");
        assertEquals(400, both.status(), both.body());

        assertEquals(400, postNode("{\"scope\":\"project\",\"title\":\"入口带产品\",\"entryPage\":true,"
                + "\"product\":\"metadata\"}").status());
        assertEquals(400, postNode("{\"scope\":\"project\",\"title\":\"外链带产品\","
                + "\"externalUrl\":\"" + EXTERNAL_URL + "\",\"product\":\"metadata\"}").status());
    }

    // ------------------------------------------------------------------
    // 需求 2：token 的拼接与「写了就不回显」
    // ------------------------------------------------------------------

    /**
     * token 由**服务端**拼进查询串，且不破坏目标地址原有的参数。
     *
     * <p>{@code orgId=1} 那一半是重点：一律用 {@code ?} 拼的话，原参数会被挤进 token 的值里，
     * 目标站收到一个面目全非的 token、同时丢掉 {@code orgId} —— 而请求本身是 200。
     */
    @Test
    void tokenIsAppendedToExistingQueryString() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"Grafana\",\"externalUrl\":\"" + EXTERNAL_URL
                + "\",\"authMode\":\"token\",\"token\":\"s3cr3t/a+b\"}").path("id").asText();

        JsonNode target = externalTarget(id, memberToken);
        assertEquals("https://grafana.example.com/d/abc?orgId=1&token=s3cr3t%2Fa%2Bb",
                target.path("url").asText(), target.toString());

        // 侧栏里只给布尔，不给明文 —— 否则每个页面的侧栏请求都会把凭据下发一遍
        JsonNode node = findById(navTree(memberToken), id);
        assertTrue(node.path("hasToken").asBoolean(), node.toString());
        assertFalse(node.toString().contains("s3cr3t"), "侧栏树里不该出现 token 明文: " + node);
    }

    /** 目标地址没有查询串时用 {@code ?}；没配认证方式时不拼任何东西。 */
    @Test
    void tokenSeparatorAndNoAuth() throws Exception {
        String plain = create("{\"scope\":\"project\",\"title\":\"裸链\","
                + "\"externalUrl\":\"https://example.com/x\",\"authMode\":\"token\","
                + "\"token\":\"abc\"}").path("id").asText();
        assertEquals("https://example.com/x?token=abc",
                externalTarget(plain, memberToken).path("url").asText());

        String none = create("{\"scope\":\"project\",\"title\":\"不授权\","
                + "\"externalUrl\":\"https://example.com/y\"}").path("id").asText();
        assertEquals("https://example.com/y", externalTarget(none, memberToken).path("url").asText());
    }

    /**
     * <b>空值 = 保持原值</b>（照 {@code ComputeService} 的惯例）。
     *
     * <p>后半条最容易漏：表单里 token 那一格在编辑态永远是空的（服务端不回显明文），
     * 把「空」当清空的话，管理员改一次标题就会把 token 抹掉，而页面看不出任何变化 ——
     * 直到某天外链打不开。
     */
    @Test
    void blankTokenKeepsTheStoredOne() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"Grafana\",\"externalUrl\":\"" + EXTERNAL_URL
                + "\",\"authMode\":\"token\",\"token\":\"keep-me\"}").path("id").asText();

        assertEquals(200, patchNode(id, "{\"title\":\"Grafana 改名\",\"token\":\"\"}").status());
        JsonNode node = findById(navTree(memberToken), id);
        assertTrue(node.path("hasToken").asBoolean(), "空 token 不该把已存的抹掉: " + node);
        assertTrue(externalTarget(id, memberToken).path("url").asText().contains("keep-me"),
                "拼出来的地址里应当还是原来那个 token");
    }

    /** 「复制」接口是平台管理员专属，且是全仓唯一的明文出口 —— 消费面没有对应接口。 */
    @Test
    void credentialIsPlatformAdminOnlyAndReturnsPlaintext() throws Exception {
        String id = create("{\"scope\":\"project\",\"title\":\"Grafana\",\"externalUrl\":\"" + EXTERNAL_URL
                + "\",\"authMode\":\"basic\",\"basicUser\":\"ops\",\"password\":\"p@ss word\"}")
                .path("id").asText();

        Resp res = call(get("/api/v1/platform/nav-nodes/" + id + "/credential")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), res.body());
        JsonNode cred = MAPPER.readTree(res.body());
        assertEquals("ops", cred.path("basicUser").asText(), res.body());
        assertEquals("p@ss word", cred.path("password").asText(), "复制要的是明文: " + res.body());
        assertEquals("", cred.path("token").asText(), "没配 token 时回空串而不是 404: " + res.body());

        // 平台管理员专属
        assertEquals(403, call(get("/api/v1/platform/nav-nodes/" + id + "/credential")
                .header("Authorization", "Bearer " + tenantAdminToken)).status());

        // 消费面拿不到明文：侧栏只有 hasPassword，外链接口只给拼好的地址
        assertFalse(navTree(memberToken).toString().contains("p@ss word"));
    }

    /** 外链接口的判权与侧栏同一条：看不见 → 403；不是外链 → 404。 */
    @Test
    void externalTargetEnforcesVisibility() throws Exception {
        String hidden = create("{\"scope\":\"project\",\"title\":\"内部看板\",\"externalUrl\":\"" + EXTERNAL_URL
                + "\",\"visibility\":\"tenant_admin\",\"authMode\":\"token\",\"token\":\"t\"}")
                .path("id").asText();
        assertEquals(403, externalTargetResp(hidden, memberToken).status(),
                "看不见这条外链的人直接拿到带 token 的地址，等于绕过了可见性开关");
        assertEquals(200, externalTargetResp(hidden, tenantAdminToken).status());

        String plain = create("{\"scope\":\"project\",\"title\":\"普通\",\"path\":\"/x/p\"}")
                .path("id").asText();
        assertEquals(404, externalTargetResp(plain, memberToken).status());
        assertEquals(404, externalTargetResp("nope", memberToken).status());
    }

    /** 入口页的消费面：非入口页 / 不存在 → 404（看不见的入口页不必让调用方知道它存在）。 */
    @Test
    void entryPageEndpointRejectsNonEntryNodes() throws Exception {
        String plain = create("{\"scope\":\"project\",\"title\":\"普通\",\"path\":\"/x/p\"}")
                .path("id").asText();
        assertEquals(404, entryPageResp(plain, memberToken).status());
        assertEquals(404, entryPageResp("nope", memberToken).status());

        // admin_only 的入口页：普通成员 404（不是 403）
        String adminOnly = create("{\"scope\":\"project\",\"title\":\"管理员总览\",\"entryPage\":true,"
                + "\"adminOnly\":true}").path("id").asText();
        assertEquals(404, entryPageResp(adminOnly, memberToken).status(),
                "看不见的入口页不该泄露「它存在」");
        assertEquals(200, entryPageResp(adminOnly, tenantAdminToken).status());
    }

    // ------------------------------------------------------------------
    // 删除：连带清理两类关联行
    // ------------------------------------------------------------------

    /**
     * 删掉**被挂**的菜单时，关联行也要清 —— 这一条最容易漏。
     *
     * <p>删的是 target，容器在别处、看着完全没事，只有进那个入口页才会发现表里多了一行空。
     * 而渲染时会跳过找不到的 target（防御），所以「表里多一行」这个症状本身还看不见 ——
     * 真正的坏处是脏数据留在库里，以及管理页回显出来的 {@code linkTargets} 指向幽灵节点。
     */
    @Test
    void deletingALinkedTargetCleansTheLinkRow() throws Exception {
        // 目标建成**从产品来的**（手工复制档：product 非空、mounted 关着）—— 这一条是
        // 「删掉被挂的菜单」，而 org 自己的菜单现在删不掉（见 NavNodeService.delete 的守卫），
        // 用 org 自有节点当目标会先被 400 挡下，测不到关联行这一段。
        // 入口页挂谁没有「必须是 org 自有」的限制，产品节点照样挂得进来。
        String target = create("{\"scope\":\"project\",\"title\":\"目标\",\"product\":\"warehouse\"}")
                .path("id").asText();
        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + target + "\"]}").path("id").asText();
        assertEquals(1, linkRows(entry));

        assertEquals(200, call(delete("/api/v1/platform/nav-nodes/" + target)
                .header("Authorization", "Bearer " + adminToken)).status());

        assertEquals(0, linkRows(entry), "被挂的菜单没了，关联行要跟着清掉");
        assertEquals(0, entryPage(entry, memberToken).path("items").size());
    }

    /**
     * 停用入口页本身：**关联行原样留着** —— 停用是可逆的，回来时挂的东西还得在。
     *
     * <p>这条原本是 {@code deletingTheEntryPageCleansItsLinkRows}（删掉入口页时清掉它挂出去的
     * 关联行）。入口页属于 org 自己的菜单，现在<b>删不掉了、只能停用</b>（见
     * {@code NavNodeService.delete()} 的守卫，以及 {@code NavNodeTest#orgOwnedMenuCannotBeDeletedOnlyDisabled}），
     * 那个前提不再成立。改成验「停用不动关联」：原来那份覆盖面（关联行的生命周期）保住了，
     * 也正好把「删」与「停用」的区别说清 —— 删要清关联，停用不能清。
     *
     * <p>（{@code delete()} 里清 {@code entry_id} 的那段仍然留着：级联删一支时若其中含着入口页，
     * 现在会被守卫整个拒掉，所以正常路径走不到它；但那是守卫的结论，不是「这段代码没用」。）
     */
    @Test
    void disablingTheEntryPageKeepsItsLinkRows() throws Exception {
        String target = create("{\"scope\":\"project\",\"title\":\"目标\",\"path\":\"/x/t\"}")
                .path("id").asText();
        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + target + "\"]}").path("id").asText();
        assertEquals(1, linkRows(entry));

        // 先钉住「删不掉」：这是本次需求的守卫，顺带在这条用例里挡一道。
        assertEquals(400, call(delete("/api/v1/platform/nav-nodes/" + entry)
                .header("Authorization", "Bearer " + adminToken)).status(), "入口页不该删得掉");
        assertEquals(1, linkRows(entry), "被拒之后关联行也要还在");

        assertEquals(200, patchNode(entry, "{\"enabled\":false}").status());
        assertEquals(1, linkRows(entry), "停用不该动关联行 —— 那是可逆的，回来时挂的东西还要在");
        assertEquals(404, entryPageResp(entry, memberToken).status(), "停用后入口页对消费面不可见");
        assertNotNull(findByPath(navTree(memberToken), "/x/t"), "被挂的菜单自己要还在: " + navTree(memberToken));

        assertEquals(200, patchNode(entry, "{\"enabled\":true}").status());
        assertEquals(1, linkRows(entry));
        assertEquals(1, entryPage(entry, memberToken).path("items").size(), "启用后挂的东西照旧");
    }

    /** 从入口页改成普通节点：模板 path 要清掉（那一列从来不是管理员填的），关联也要清。 */
    @Test
    void turningAnEntryPageIntoAPlainNodeClearsTemplatePathAndLinks() throws Exception {
        String target = create("{\"scope\":\"project\",\"title\":\"目标\",\"path\":\"/x/t\"}")
                .path("id").asText();
        String entry = create("{\"scope\":\"project\",\"title\":\"总览\",\"entryPage\":true,"
                + "\"linkTargets\":[\"" + target + "\"]}").path("id").asText();

        Resp res = patchNode(entry, "{\"entryPage\":false,\"path\":\"/x/plain\"}");
        assertEquals(200, res.status(), res.body());
        assertEquals("/x/plain", MAPPER.readTree(res.body()).path("path").asText(),
                "改回普通节点后 path 应当是管理员填的那个");
        assertEquals(0, linkRows(entry), "不再是入口页，挂出去的东西不该留着");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static List<String> labelsOf(JsonNode items) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : items) out.add(row.path("label").asText());
        return out;
    }

    /** 关联表里的行数（直接查库 —— 这是「真删掉了」唯一能证到底的方式）。 */
    private int linkRows(String entryId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nav_entry_links WHERE entry_id = ?", Integer.class, entryId);
        return n == null ? -1 : n;
    }

    private JsonNode entryPage(String id, String token) throws Exception {
        Resp res = entryPageResp(id, token);
        assertEquals(200, res.status(), "读入口页失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private Resp entryPageResp(String id, String token) throws Exception {
        return call(get("/api/v1/nav/entry/" + id)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE));
    }

    private JsonNode externalTarget(String id, String token) throws Exception {
        Resp res = externalTargetResp(id, token);
        assertEquals(200, res.status(), "读外链地址失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private Resp externalTargetResp(String id, String token) throws Exception {
        return call(get("/api/v1/nav/external/" + id)
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE));
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

    private JsonNode create(String json) throws Exception {
        Resp res = postNode(json);
        assertEquals(200, res.status(), "建节点失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private Resp postNode(String json) throws Exception {
        return call(post("/api/v1/platform/nav-nodes")
                .header("Authorization", "Bearer " + adminToken)
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

    private JsonNode navTree(String token) throws Exception {
        Resp res = call(get("/api/v1/nav")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "读菜单树失败: " + res.body());
        JsonNode node = MAPPER.readTree(res.body());
        assertTrue(node.isArray(), "菜单应当是数组: " + res.body());
        return node;
    }

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

    private static JsonNode findById(JsonNode nodes, String id) {
        for (JsonNode row : nodes) {
            if (id.equals(row.path("id").asText())) return row;
            JsonNode hit = findById(row.path("children"), id);
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
}
