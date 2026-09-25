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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门户菜单的两条线：管理面（平台管理员配）与消费面（租户成员读）。
 *
 * <p>这里最要紧的一条是 {@link #memberSeesMenuWithoutPlatformRights()}：菜单读取
 * <b>不能</b>挂在 {@code requirePlatform()} 下面。真挂错了，普通成员的侧栏会永远是空的，
 * 而所有接口都返回 200 —— 这次门户集成的目的（让平台成员用上被嵌入的服务）直接落空，
 * 却没有任何报错。功能测试不写这一条，就只能靠人点页面发现。
 *
 * <p>许可过滤同样在这里守：{@link #menuDisappearsWhenLicenseIsRevoked()} ——
 * 「菜单画错」是 push 时代专门修过的缺陷，改 pull 之后它靠这条过滤兜住。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_nav_menu;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=nav-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class NavMenuTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_CODE = "nav_t1";
    private static final String FRONTEND_URL = "http://127.0.0.1:5182";
    private static final String MEMBER_USERNAME = "nav_member";

    @Autowired
    private MockMvc mvc;

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
                    .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"菜单验证租户\",\"adminUserId\":\"admin\"}"));
            assertEquals(200, created.status(), "创建租户失败: " + created.body());
            tenantId = MAPPER.readTree(created.body()).path("id").asText();

            // 登记 metadata 的页面地址 —— 消费面返回的 frontendUrl 就是从这儿来的
            Resp svc = call(post("/api/v1/platform/services")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"product\":\"metadata\",\"frontendUrl\":\"" + FRONTEND_URL + "\"}"));
            assertEquals(200, svc.status(), "登记服务失败: " + svc.body());

            // 一个普通租户成员 —— 消费面的关键回归对象
            Resp user = call(post("/api/tenants/" + tenantId + "/users")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Tenant-Code", TENANT_CODE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + MEMBER_USERNAME + "\",\"displayName\":\"菜单成员\","
                            + "\"password\":\"123456\",\"tenantRole\":\"member\"}"));
            assertEquals(200, user.status(), "建成员失败: " + user.body());
            memberToken = login(MEMBER_USERNAME, "123456");
        }

        // 同一套库跑所有用例，而菜单与许可是可变的：每个用例从「空菜单 + 全许可」重来，
        // 否则用例之间会互相看见对方留下的菜单项（已踩过：重复路径全部 400）。
        for (JsonNode row : adminList()) {
            call(delete("/api/v1/platform/nav-items/" + row.path("id").asText())
                    .header("Authorization", "Bearer " + adminToken));
        }
        setModules("[\"warehouse\",\"metadata\"]");
    }

    // ------------------------------------------------------------------

    /** 管理员配一条菜单，消费面读到它，且带上产品的前端地址。 */
    @Test
    void menuReachesTenantWithFrontendUrl() throws Exception {
        JsonNode created = createItem("{\"product\":\"metadata\",\"label\":\"数据地图\","
                + "\"icon\":\"DatabaseOutlined\",\"path\":\"/lineage/tables\"}");
        assertEquals("metadata", created.path("product").asText());
        assertEquals("/lineage/tables", created.path("path").asText());

        JsonNode menu = menu(adminToken);
        assertEquals(1, menu.size(), "菜单应有一项: " + menu);
        assertEquals("数据地图", menu.get(0).path("label").asText());
        assertEquals("/lineage/tables", menu.get(0).path("path").asText(),
                "消费面给的必须是【子应用内】路径，拼完整路由是前端的事: " + menu);
        assertEquals(FRONTEND_URL, menu.get(0).path("frontendUrl").asText(),
                "菜单项没带上产品的前端地址 —— 前端只能再查一次服务表，多一种失败模式: " + menu);
    }

    /**
     * <b>普通成员也要能读到菜单。</b>
     *
     * <p>这条断言的是「消费面没有挂在平台管理员门禁下面」。挂错了的表现是
     * 接口 403 或空数组，而管理面一切正常 —— 只有拿成员身份试才会现形。
     */
    @Test
    void memberSeesMenuWithoutPlatformRights() throws Exception {
        createItem("{\"product\":\"metadata\",\"label\":\"数据地图\",\"path\":\"/lineage/tables\"}");

        JsonNode menu = menu(memberToken);
        assertEquals(1, menu.size(),
                "普通成员读不到菜单 —— 消费面很可能挂在 requirePlatform() 下面了: " + menu);

        // 同一身份读管理面必须被拒，否则上一条可能只是「门禁根本没生效」的假绿
        Resp adminSide = call(get("/api/v1/platform/nav-items").header("Authorization", "Bearer " + memberToken));
        assertEquals(403, adminSide.status(), "普通成员不该能读管理面菜单: " + adminSide.body());
    }

    /** 许可里摘掉该产品 → 菜单消失（「菜单画错」回归）。加回去 → 菜单恢复。 */
    @Test
    void menuDisappearsWhenLicenseIsRevoked() throws Exception {
        createItem("{\"product\":\"metadata\",\"label\":\"数据地图\",\"path\":\"/lineage/tables\"}");
        assertEquals(1, menu(memberToken).size(), "前置条件：许可里有 metadata 时应能看到菜单");

        setModules("[\"warehouse\"]");
        assertEquals(0, menu(memberToken).size(),
                "许可已摘掉 data metadata，菜单还显示 —— 点进去就是被 403 顶回来，正是「菜单画错」");

        setModules("[\"warehouse\",\"metadata\"]");
        assertEquals(1, menu(memberToken).size(), "许可加回来之后菜单应当恢复");
    }

    /** 停用的菜单项不出现在消费面，但管理面还看得见（能改回来）。 */
    @Test
    void disabledItemIsHiddenFromMenuButListedForAdmin() throws Exception {
        JsonNode created = createItem("{\"product\":\"metadata\",\"label\":\"数据地图\",\"path\":\"/lineage/tables\"}");
        String id = created.path("id").asText();

        Resp patched = call(patch("/api/v1/platform/nav-items/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false}"));
        assertEquals(200, patched.status(), "停用失败: " + patched.body());

        assertEquals(0, menu(adminToken).size(), "停用的菜单项不该出现在侧栏");
        assertEquals(1, adminList().size(), "管理面必须还看得见它，否则停用之后就再也改不回来了");
    }

    /** 未知产品码：写入时就拒绝，别等到菜单永远不出现才发现。 */
    @Test
    void unknownProductIsRejectedOnWrite() throws Exception {
        Resp res = call(post("/api/v1/platform/nav-items")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"lineage\",\"label\":\"血缘\",\"path\":\"/x\"}"));
        assertEquals(400, res.status(), "不认识的产品码应当被拒绝: " + res.body());
        assertTrue(res.body().contains("metadata"), "报错要说清可用值: " + res.body());
    }

    /** 同一产品下重复路径：400，不是 500。 */
    @Test
    void duplicatePathIsRejected() throws Exception {
        createItem("{\"product\":\"metadata\",\"label\":\"数据地图\",\"path\":\"/lineage/tables\"}");
        Resp again = call(post("/api/v1/platform/nav-items")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"metadata\",\"label\":\"重复\",\"path\":\"/lineage/tables\"}"));
        assertEquals(400, again.status(), "重复路径应当是可预期的用户错误（400），不是 500: " + again.body());
    }

    // ------------------------------------------------------------------
    // V18：归属壳（scope）、分组、权限词
    // ------------------------------------------------------------------

    /** 新建时不传 scope → 落到工作台壳。存量行靠这个默认值零回填，不能改。 */
    @Test
    void scopeDefaultsToWorkbench() throws Exception {
        JsonNode created = createItem("{\"product\":\"metadata\",\"label\":\"数据地图\",\"path\":\"/lineage/tables\"}");
        assertEquals("workbench", created.path("scope").asText(),
                "不传 scope 应当落到工作台壳 —— 存量菜单本来就是工作台级产品入口: " + created);
    }

    /** 未知归属壳：写入时就拒绝（写进去只会静默不显示，最难查）。 */
    @Test
    void unknownScopeIsRejectedOnWrite() throws Exception {
        Resp res = call(post("/api/v1/platform/nav-items")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"metadata\",\"label\":\"血缘\",\"path\":\"/x\",\"scope\":\"shell\"}"));
        assertEquals(400, res.status(), "未知归属壳应当被拒绝: " + res.body());
        assertTrue(res.body().contains("workbench"), "报错要说清可用值: " + res.body());
    }

    /**
     * 同一条子应用路径可以分别挂两个壳 —— V18 放宽唯一约束就是为了这个。
     *
     * <p>不改约束的表现：管理员把「数据地图设置」同时配进工作台与项目壳时拿到 400
     * 「已经有指向 /lineage/settings/map 的菜单项了」，看起来像数据重复，实际是约束没跟上。
     */
    @Test
    void samePathCanLiveInBothShells() throws Exception {
        createItem("{\"product\":\"metadata\",\"label\":\"血缘\",\"path\":\"/lineage/tables\",\"scope\":\"project\"}");
        JsonNode second = createItem("{\"product\":\"metadata\",\"label\":\"血缘\",\"path\":\"/lineage/tables\","
                + "\"scope\":\"workbench\"}");
        assertEquals("workbench", second.path("scope").asText());

        assertEquals(2, menu(adminToken).size(), "两个壳各挂一份，消费面应当都返回");
    }

    /** 消费面要带上 scope / 分组 / 权限词 —— 壳靠它们分流两个壳、画分组、决定置灰。 */
    @Test
    void menuCarriesScopeGroupAndPerm() throws Exception {
        createItem("{\"product\":\"metadata\",\"scope\":\"project\",\"groupTitle\":\"数据地图\","
                + "\"label\":\"数据目录\",\"path\":\"/lineage/catalogs\",\"perm\":\"catalog:admin\"}");

        JsonNode first = menu(adminToken).get(0);
        assertEquals("project", first.path("scope").asText(), "壳靠这一项分辨挂哪边: " + first);
        assertEquals("数据地图", first.path("groupTitle").asText(), "丢了分组侧栏会变成一长条: " + first);
        assertEquals("catalog:admin", first.path("perm").asText(),
                "权限词要带出来，壳才能「留在原地置灰」而不是抹掉: " + first);
    }

    /** 批量新建：成功的落库、已存在的归 skipped（重复点击是正常操作）、不合法的归 failed 并带 1 起序号。 */
    @Test
    void batchCreateReportsEachRow() throws Exception {
        createItem("{\"product\":\"metadata\",\"label\":\"血缘\",\"path\":\"/lineage/tables\",\"scope\":\"project\"}");

        Resp res = call(post("/api/v1/platform/nav-items/batch")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":["
                        + "{\"product\":\"metadata\",\"label\":\"全文检索\",\"path\":\"/lineage/search\",\"scope\":\"project\"},"
                        + "{\"product\":\"metadata\",\"label\":\"血缘\",\"path\":\"/lineage/tables\",\"scope\":\"project\"},"
                        + "{\"product\":\"lineage\",\"label\":\"坏产品码\",\"path\":\"/x\"}"
                        + "]}"));
        assertEquals(200, res.status(), "批量新建失败: " + res.body());

        JsonNode body = MAPPER.readTree(res.body());
        assertEquals(1, body.path("created").size(), "应当只落一条: " + body);
        assertEquals("/lineage/search", body.path("created").get(0).path("path").asText(), body.toString());
        assertEquals(1, body.path("skipped").size(), "已配置的那条应当跳过而不是失败: " + body);
        assertEquals(1, body.path("failed").size(), "未知产品码应当归失败: " + body);
        assertEquals(3, body.path("failed").get(0).path("index").asInt(),
                "失败项的序号是 1 起的，前端才能直接显示「第 N 项」: " + body);

        assertEquals(2, menu(adminToken).size(), "库里应当有原先那条 + 批量新增那条: " + body);
    }

    /** 批量接口也在平台管理员门禁下面（普通成员不能往菜单表里写）。 */
    @Test
    void batchCreateRequiresPlatformAdmin() throws Exception {
        Resp res = call(post("/api/v1/platform/nav-items/batch")
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"product\":\"metadata\",\"label\":\"血缘\",\"path\":\"/lineage/tables\"}]}"));
        assertEquals(403, res.status(), "普通成员不该能批量写菜单: " + res.body());
    }

    /** 删掉菜单项后消费面也读不到。 */
    @Test
    void deletedItemIsGone() throws Exception {
        String id = createItem("{\"product\":\"metadata\",\"label\":\"数据地图\",\"path\":\"/lineage/tables\"}")
                .path("id").asText();
        assertEquals(200, call(delete("/api/v1/platform/nav-items/" + id)
                .header("Authorization", "Bearer " + adminToken)).status());
        assertEquals(0, menu(adminToken).size());
    }

    /** 没有许可行的租户：返回空菜单而不是报错（侧栏空着也比整个壳 500 好）。 */
    @Test
    void tenantWithoutLicenseGetsEmptyMenuNotError() throws Exception {
        createItem("{\"product\":\"metadata\",\"label\":\"数据地图\",\"path\":\"/lineage/tables\"}");
        Resp created = call(post("/api/platform/tenants")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"nav_t2\",\"name\":\"无许可租户\",\"adminUserId\":\"admin\"}"));
        assertEquals(200, created.status(), "创建租户失败: " + created.body());
        String otherId = MAPPER.readTree(created.body()).path("id").asText();

        // 新租户建出来时带默认许可，这里显式清空，模拟「还没开通任何产品」
        Resp patched = call(patch("/api/platform/tenants/" + otherId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"modules\":[]}"));
        assertEquals(200, patched.status(), "清空许可失败: " + patched.body());

        Resp res = call(get("/api/v1/nav")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", "nav_t2"));
        assertEquals(200, res.status(), "没有许可的租户应当拿到空菜单，而不是报错: " + res.body());
        assertTrue(res.body().trim().equals("[]"), "应当是空数组: " + res.body());
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

    private JsonNode createItem(String json) throws Exception {
        Resp res = call(post("/api/v1/platform/nav-items")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
        assertEquals(200, res.status(), "建菜单失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private JsonNode menu(String token) throws Exception {
        Resp res = call(get("/api/v1/nav")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE));
        assertEquals(200, res.status(), "读菜单失败: " + res.body());
        JsonNode node = MAPPER.readTree(res.body());
        assertTrue(node.isArray(), "菜单应当是数组: " + res.body());
        return node;
    }

    private JsonNode adminList() throws Exception {
        Resp res = call(get("/api/v1/platform/nav-items").header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "读管理面菜单失败: " + res.body());
        JsonNode node = MAPPER.readTree(res.body());
        assertFalse(!node.isArray(), "管理面应当返回数组: " + res.body());
        return node;
    }

    private record Resp(int status, String body) {
    }

    private Resp call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }
}
