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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 菜单分组（V19）：管理面的 CRUD + 改名级联，消费面的读取。
 *
 * <p>这个类的重点是三条别的测试看不到的坏法：
 *
 * <ol>
 *   <li>{@link #renameCascadesToNavItems()} —— 分组表与菜单项之间<b>没有外键</b>（软约束，
 *       见 {@code V19__nav_groups.sql}）。不级联的话，管理员改完名侧栏仍显示旧名，
 *       而新名会以一个「已登记但空」的分组出现，看起来就是改名没生效。
 *   <li>{@link #alwaysGroupSurvivesTenantWithoutLicense()} —— 消费面<b>刻意不做许可过滤</b>。
 *       这条钉住它：以后谁「顺手」补一个许可过滤，{@code always} 空组策略会静默失效，
 *       而所有接口仍然 200。
 *   <li>{@link #unregisteredGroupNameStillWorks()} —— 软约束的另一个方向：菜单项里
 *       可以写一个没登记过的分组名。仓建设的「建模中心」等分组是按项目分层动态生成的，
 *       天生进不了静态分组表；一旦这里开始拦，动态分组当场就坏了。
 * </ol>
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_nav_group;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=nav-group-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class NavGroupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_CODE = "navg_t1";
    private static final String MEMBER_USERNAME = "navg_member";

    @Autowired
    private MockMvc mvc;

    /** 只有 {@link #groupsForBlankTenantIsEmpty()} 用它 —— 那条的前置条件走 HTTP 构造不出来。 */
    @Autowired
    private com.dwai.platform.meta.NavGroupService groupsService;

    private static String adminToken;
    private static String memberToken;
    private static String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken == null) {
            adminToken = login("admin", "123456");

            Resp created = call(post("/api/platform/tenants")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"分组验证租户\",\"adminUserId\":\"admin\"}"));
            assertEquals(200, created.status(), "创建租户失败: " + created.body());
            tenantId = MAPPER.readTree(created.body()).path("id").asText();

            Resp user = call(post("/api/tenants/" + tenantId + "/users")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Tenant-Code", TENANT_CODE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + MEMBER_USERNAME + "\",\"displayName\":\"分组成员\","
                            + "\"password\":\"123456\",\"tenantRole\":\"member\"}"));
            assertEquals(200, user.status(), "建成员失败: " + user.body());
            memberToken = login(MEMBER_USERNAME, "123456");
        }

        // 同一套库跑所有用例，而分组与菜单是可变的：每个用例从「空分组 + 空菜单 + 全许可」重来，
        // 否则用例之间会互相看见对方留下的行（同名分组会直接撞唯一约束）。
        for (JsonNode row : adminGroups()) {
            call(delete("/api/v1/platform/nav-groups/" + row.path("id").asText())
                    .header("Authorization", "Bearer " + adminToken));
        }
        for (JsonNode row : adminItems()) {
            call(delete("/api/v1/platform/nav-items/" + row.path("id").asText())
                    .header("Authorization", "Bearer " + adminToken));
        }
        setModules("[\"warehouse\",\"metadata\"]");
    }

    // ------------------------------------------------------------------
    // 管理面 CRUD
    // ------------------------------------------------------------------

    /** 建、列、改、删一圈走通；缺省值是可预期的（排序 0、策略 hide = 现状行为）。 */
    @Test
    void groupCrudWorksEndToEnd() throws Exception {
        JsonNode created = createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}");
        assertTrue(created.path("id").asText().startsWith("grp-"), "id 应当按 nav- 那套惯例生成: " + created);
        assertEquals(0, created.path("sortOrder").asInt(), "不传排序应当是 0: " + created);
        assertEquals("hide", created.path("emptyPolicy").asText(),
                "不传策略应当落到 hide —— 与现状（空分组不渲染）一致，升级后才不会悄悄变行为: " + created);

        String id = created.path("id").asText();
        Resp patched = call(patch("/api/v1/platform/nav-groups/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sortOrder\":10,\"emptyPolicy\":\"always\"}"));
        assertEquals(200, patched.status(), "改分组失败: " + patched.body());
        assertEquals(10, MAPPER.readTree(patched.body()).path("sortOrder").asInt());
        assertEquals("always", MAPPER.readTree(patched.body()).path("emptyPolicy").asText());

        assertEquals(1, adminGroups().size(), "列表里应当有这一条");
        assertEquals(200, call(delete("/api/v1/platform/nav-groups/" + id)
                .header("Authorization", "Bearer " + adminToken)).status());
        assertEquals(0, adminGroups().size(), "删掉之后列表应当为空");
    }

    /** 未知归属壳：写入时就拒绝。写进去的表现是分组建好了却永远匹配不到菜单项。 */
    @Test
    void unknownScopeIsRejected() throws Exception {
        Resp res = call(post("/api/v1/platform/nav-groups")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"shell\",\"product\":\"metadata\",\"title\":\"数据地图\"}"));
        assertEquals(400, res.status(), "未知归属壳应当被拒绝: " + res.body());
        assertTrue(res.body().contains("workbench"), "报错要说清可用值: " + res.body());
    }

    /** 未知空组策略：写入时就拒绝。写进去的表现是接口 200、行为却按没人预期的默认值走。 */
    @Test
    void unknownEmptyPolicyIsRejected() throws Exception {
        Resp res = call(post("/api/v1/platform/nav-groups")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\","
                        + "\"emptyPolicy\":\"keep\"}"));
        assertEquals(400, res.status(), "未知空组策略应当被拒绝: " + res.body());
        assertTrue(res.body().contains("always"), "报错要说清可用值: " + res.body());
    }

    /** 未知产品码：与菜单项同一套白名单（不新开一份，避免两处漂移）。 */
    @Test
    void unknownProductIsRejected() throws Exception {
        Resp res = call(post("/api/v1/platform/nav-groups")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"project\",\"product\":\"lineage\",\"title\":\"血缘\"}"));
        assertEquals(400, res.status(), "未知产品码应当被拒绝: " + res.body());
        assertTrue(res.body().contains("metadata"), "报错要说清可用值: " + res.body());
    }

    /** 空分组名 / 超长分组名：都显式拒绝，不靠列宽兜底。 */
    @Test
    void blankOrOverlongTitleIsRejected() throws Exception {
        Resp blank = call(post("/api/v1/platform/nav-groups")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"   \"}"));
        assertEquals(400, blank.status(), "空分组名应当被拒绝: " + blank.body());

        String long64 = "字".repeat(65);
        Resp over = call(post("/api/v1/platform/nav-groups")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"" + long64 + "\"}"));
        assertEquals(400, over.status(),
                "超长分组名应当被拒绝而不是被截断 —— 截断会让分组名与菜单项里写的那个悄悄对不上: " + over.body());
    }

    /** 同壳同产品下重名 → 400（可预期的用户错误），不是 500。 */
    @Test
    void duplicateGroupIsRejected() throws Exception {
        createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}");
        Resp again = call(post("/api/v1/platform/nav-groups")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}"));
        assertEquals(400, again.status(), "同名分组应当是可预期的用户错误（400），不是 500: " + again.body());

        // 换个壳就是另一个分组，必须能共存 —— 两个壳各有一套侧栏
        createGroup("{\"scope\":\"workbench\",\"product\":\"metadata\",\"title\":\"数据地图\"}");
        assertEquals(2, adminGroups().size());
    }

    /** 壳与产品是分组身份的一部分，改身份要显式拒掉，否则与「改名」语义撞车。 */
    @Test
    void scopeAndProductAreImmutable() throws Exception {
        String id = createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}")
                .path("id").asText();

        Resp res = call(patch("/api/v1/platform/nav-groups/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"workbench\"}"));
        assertEquals(400, res.status(), "改归属壳应当被拒绝: " + res.body());
    }

    /** 列表按壳 + 产品过滤（菜单管理页顶部那两个筛选）。 */
    @Test
    void listCanBeFilteredByScopeAndProduct() throws Exception {
        createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}");
        createGroup("{\"scope\":\"workbench\",\"product\":\"warehouse\",\"title\":\"仓建设\"}");

        assertEquals(1, filtered("?scope=project").size());
        assertEquals(1, filtered("?product=warehouse").size());
        assertEquals(1, filtered("?scope=project&product=metadata").size());
        assertEquals(0, filtered("?scope=project&product=warehouse").size());
        assertEquals(2, adminGroups().size());
    }

    /** 管理面在平台管理员门禁下面：普通成员不能建分组。 */
    @Test
    void writesRequirePlatformAdmin() throws Exception {
        Resp res = call(post("/api/v1/platform/nav-groups")
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}"));
        assertEquals(403, res.status(), "普通成员不该能建分组: " + res.body());
    }

    // ------------------------------------------------------------------
    // 改名级联 / 删除回报
    // ------------------------------------------------------------------

    /**
     * 改名要级联到菜单项的 {@code group_title}。
     *
     * <p>侧栏的分组标题来自菜单项里那个字符串，分组表只是元数据。不级联的话，
     * 管理员改完名侧栏仍显示旧名，新名会以一个「已登记但空」的分组出现。
     */
    @Test
    void renameCascadesToNavItems() throws Exception {
        String id = createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}")
                .path("id").asText();
        createItem("{\"product\":\"metadata\",\"scope\":\"project\",\"groupTitle\":\"数据地图\","
                + "\"label\":\"数据目录\",\"path\":\"/lineage/catalogs\"}");
        // 另一个产品下同名的分组：级联不该波及它（那是另一个分组）
        createItem("{\"product\":\"warehouse\",\"scope\":\"project\",\"groupTitle\":\"数据地图\","
                + "\"label\":\"数仓概览\",\"path\":\"/model/overview\"}");

        Resp res = call(patch("/api/v1/platform/nav-groups/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"数据地图（新）\"}"));
        assertEquals(200, res.status(), "改名失败: " + res.body());
        assertEquals(1, MAPPER.readTree(res.body()).path("renamedItems").asInt(),
                "应当回报级联了 1 条菜单项: " + res.body());

        JsonNode items = adminItems();
        assertEquals("数据地图（新）", itemGroupOf(items, "metadata"),
                "同壳同产品下的菜单项应当跟着改名 —— 不级联侧栏就还显示旧名: " + items);
        assertEquals("数据地图", itemGroupOf(items, "warehouse"),
                "另一个产品下同名的分组不该被这次改名波及: " + items);

        // 消费面（侧栏真源）也应当是新名
        JsonNode menu = menu(adminToken);
        boolean renamed = false;
        for (JsonNode row : menu) {
            if ("/lineage/catalogs".equals(row.path("path").asText())) {
                renamed = "数据地图（新）".equals(row.path("groupTitle").asText());
            }
        }
        assertTrue(renamed, "消费面返回的分组名也必须是新名: " + menu);
    }

    /** 只改排序/策略时不该动菜单项。 */
    @Test
    void updatingOrderDoesNotTouchItems() throws Exception {
        String id = createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}")
                .path("id").asText();
        createItem("{\"product\":\"metadata\",\"scope\":\"project\",\"groupTitle\":\"数据地图\","
                + "\"label\":\"数据目录\",\"path\":\"/lineage/catalogs\"}");

        Resp res = call(patch("/api/v1/platform/nav-groups/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"sortOrder\":5}"));
        assertEquals(0, MAPPER.readTree(res.body()).path("renamedItems").asInt(),
                "没改名就不该回报级联: " + res.body());
    }

    /**
     * 删分组不阻塞、不清空菜单项的分组名，只回报还有几条写着这个名字。
     *
     * <p>顺手清空 {@code group_title} 等于<b>静默改菜单</b>：管理员删的是一个分组配置，
     * 不该连带菜单的观感一起变。
     */
    @Test
    void deleteReportsReferencedAndKeepsItemGroupTitle() throws Exception {
        String id = createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}")
                .path("id").asText();
        createItem("{\"product\":\"metadata\",\"scope\":\"project\",\"groupTitle\":\"数据地图\","
                + "\"label\":\"数据目录\",\"path\":\"/lineage/catalogs\"}");

        Resp res = call(delete("/api/v1/platform/nav-groups/" + id)
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "删除失败: " + res.body());
        assertEquals(1, MAPPER.readTree(res.body()).path("referenced").asInt(),
                "应当回报仍有 1 条菜单写着这个名字: " + res.body());

        assertEquals(0, adminGroups().size(), "分组本身应当被删掉");
        assertEquals(1, adminItems().size(), "菜单项不该被连带删掉");
        assertEquals("数据地图", itemGroupOf(adminItems(), "metadata"),
                "菜单项的分组名不该被静默清空 —— 它们照常显示: " + adminItems());
    }

    // ------------------------------------------------------------------
    // 消费面
    // ------------------------------------------------------------------

    /**
     * 普通成员能读分组元数据（消费面），但写管理面被拒。
     *
     * <p>读挂错到 {@code requirePlatform()} 下面的表现是：接口 403/空数组，管理面一切正常，
     * 只有拿成员身份试才会现形 —— 而那意味着侧栏读不到组间顺序与空组策略。
     */
    @Test
    void memberCanReadGroupsButNotWrite() throws Exception {
        createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\",\"sortOrder\":3}");

        JsonNode groups = groups(memberToken);
        assertEquals(1, groups.size(), "普通成员读不到分组元数据: " + groups);
        assertEquals("数据地图", groups.get(0).path("title").asText());
        assertEquals(3, groups.get(0).path("sortOrder").asInt());

        Resp adminSide = call(get("/api/v1/platform/nav-groups").header("Authorization", "Bearer " + memberToken));
        assertEquals(403, adminSide.status(), "普通成员不该能读管理面分组: " + adminSide.body());
    }

    /**
     * <b>这条钉住「消费面不做许可过滤」这个决策。</b>
     *
     * <p>规范要「数据地图」在租户<b>未开通</b>时也出现在侧栏（置灰并说明），
     * 那正是 {@code always} 空组策略的用途。消费面若按许可先滤一道，那个分组
     * 永远不会出现，空组策略就成了死代码 —— 而所有接口仍然 200，没有任何报错。
     *
     * <p>对照着断言：同一时刻菜单是空的（说明许可确实摘掉了），分组仍在。
     */
    @Test
    void alwaysGroupSurvivesTenantWithoutLicense() throws Exception {
        createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\","
                + "\"emptyPolicy\":\"always\"}");

        setModules("[]");
        assertEquals(0, menu(memberToken).size(), "前置条件：许可摘空后菜单应当是空的");

        JsonNode groups = groups(memberToken);
        assertEquals(1, groups.size(),
                "租户没开通该产品时，分组元数据仍要返回 —— 否则 always 空组策略永远没有机会生效: " + groups);
        assertEquals("always", groups.get(0).path("emptyPolicy").asText());
    }

    /**
     * 拿不到租户时返回空列表而不是抛异常。
     *
     * <p>直接调 service 而不是发请求：standard 模式下没传 {@code X-Tenant-Code} 会回落到
     * 隐式默认租户，<b>走 HTTP 根本构造不出「没有租户」这个前置条件</b>（只有 multi 模式下
     * 不带任何租户头才会）。侧栏是每个页面都要画的东西，让它因为「没选租户」而 500
     * 会把整个壳带下水 —— 这个守卫值得单独钉一条。
     */
    @Test
    void groupsForBlankTenantIsEmpty() {
        assertTrue(groupsService.groupsFor(null).isEmpty(), "没有租户时应当是空列表，不是异常");
        assertTrue(groupsService.groupsFor("   ").isEmpty(), "空白租户名同样按「没有租户」处理");
    }

    /**
     * <b>软约束的另一个方向</b>：菜单项里可以写一个从没登记过的分组名。
     *
     * <p>仓建设的「建模中心」等分组是按项目分层动态生成的，天生进不了静态分组表。
     * 一旦这里开始拦（比如给 {@code group_title} 加外键、或在写入时校验「分组必须已登记」），
     * 动态分组当场就坏了 —— 而且报的还是「未知分组」这种看起来很像配置问题的错。
     */
    @Test
    void unregisteredGroupNameStillWorks() throws Exception {
        createGroup("{\"scope\":\"project\",\"product\":\"metadata\",\"title\":\"数据地图\"}");

        JsonNode created = createItem("{\"product\":\"metadata\",\"scope\":\"project\","
                + "\"groupTitle\":\"建模中心\",\"label\":\"建模列表\",\"path\":\"/lineage/models\"}");
        assertEquals("建模中心", created.path("groupTitle").asText(),
                "没登记过的分组名必须仍能落库 —— 动态分组就靠这条路: " + created);

        JsonNode menu = menu(adminToken);
        assertEquals("建模中心", menu.get(0).path("groupTitle").asText(),
                "消费面要照常把它带出来，前端按字符串分组即可: " + menu);
    }

    // ------------------------------------------------------------------

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

    private JsonNode createGroup(String json) throws Exception {
        Resp res = call(post("/api/v1/platform/nav-groups")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
        assertEquals(200, res.status(), "建分组失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private JsonNode createItem(String json) throws Exception {
        Resp res = call(post("/api/v1/platform/nav-items")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
        assertEquals(200, res.status(), "建菜单失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private JsonNode adminGroups() throws Exception {
        return filtered("");
    }

    private JsonNode filtered(String query) throws Exception {
        Resp res = call(get("/api/v1/platform/nav-groups" + query)
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "读管理面分组失败: " + res.body());
        JsonNode node = MAPPER.readTree(res.body());
        assertTrue(node.isArray(), "管理面应当返回数组: " + res.body());
        return node;
    }

    private JsonNode adminItems() throws Exception {
        Resp res = call(get("/api/v1/platform/nav-items").header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "读管理面菜单失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /** 消费面（侧栏真源）读分组：这个路径是 {@code NavController} 的，不是管理面。 */
    private JsonNode groups(String token) throws Exception {
        Resp res = call(get("/api/v1/nav/groups")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "读消费面分组失败: " + res.body());
        JsonNode node = MAPPER.readTree(res.body());
        assertTrue(node.isArray(), "分组应当是数组: " + res.body());
        return node;
    }

    private JsonNode menu(String token) throws Exception {
        Resp res = call(get("/api/v1/nav")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "读菜单失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /** 从管理面菜单列表里取某个产品的分组名，用来断言级联真的落到了 {@code nav_items} 上。 */
    private static String itemGroupOf(JsonNode items, String product) {
        for (JsonNode row : items) {
            if (product.equals(row.path("product").asText())) return row.path("groupTitle").asText();
        }
        return "<<没找到 " + product + " 的菜单项>>";
    }

    private record Resp(int status, String body) {
    }

    private Resp call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }
}
