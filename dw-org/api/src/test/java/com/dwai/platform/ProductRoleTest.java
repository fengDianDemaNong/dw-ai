package com.dwai.platform;

import com.dwai.platform.meta.support.Perms;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 产品角色（V20）：管理面 CRUD、不变量，以及「角色真的决定了判权」。
 *
 * <h2>这个类里最重要的两条</h2>
 *
 * <ol>
 *   <li>{@link #seededBuiltinsMatchTheHardcodedMatrix()} —— 迁移灌的种子必须与
 *       {@code Perms.java} 的硬编码矩阵<b>逐词相等</b>。这是「升级后行为不变」的全部依据：
 *       判权改成「先查表、查不到回落 Perms」之后，老成员拿到的是表里那份数据，
 *       种子抄错一个词，某个人的某个权限就静默变了，而没有任何接口会报错。</li>
 *   <li>{@link #customRoleActuallyDrivesAuthzCheck()} —— 建一个 {@code Perms} 里
 *       根本没有的角色码，派给人，然后拿 {@code /internal/v1/authz/check} 断言
 *       它<b>按新角色判</b>。没有这条，这个功能只是「一张能编辑的表」，
 *       与「判权真的走表」是两件事，而后者才是需求 2 的目的。</li>
 * </ol>
 *
 * <p>另外三条边界各钉一个具体的坏法：种子缺失（历史成员集体判否）、
 * 「每个产品恰有一个管理角色」被破坏（租户管理员在自己产品里进不去）、
 * 以及角色码写死三值（产品专属角色码派不下去，需求 2 无处落地）。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_product_role;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=prole-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class ProductRoleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODULE_TOKEN = "prole-module-token";

    private static final String TENANT_CODE = "prole_t1";
    private static final String PROJECT_CODE = "prole_p1";
    /** 第二个项目：用来钉「菜单结论跟着**当前项目**的角色走」，见 {@link #menuFollowsTheRoleOfTheCurrentProject()}。 */
    private static final String PROJECT2_CODE = "prole_p2";
    private static final String MEMBER_USERNAME = "prole_member";

    /** 仓建设的出厂词表 —— 与迁移里的种子、`Perms.WAREHOUSE` 三者必须一致。 */
    private static final String WAREHOUSE_WORDS = """
            [{"value":"spec:read","label":"查看规范"},{"value":"spec:write","label":"编辑规范"},
             {"value":"model:read","label":"查看模型"},{"value":"model:write","label":"编辑模型"},
             {"value":"model:publish","label":"发布模型"},{"value":"iam:member","label":"成员管理"}]
            """;

    /**
     * 桩清单。`model:publish` <b>刻意不出现在任何菜单上</b> —— 它只在页面内的
     * 发布按钮上判。词表必须显式声明才能覆盖它，这条桩数据就是那条断言的素材。
     */
    private static final String WAREHOUSE_MENU = """
            {"product":"warehouse","version":"0.1.0","perms":%s,"menus":[
              {"id":"warehouse:project:/model","scope":"project","group":"","path":"/model",
               "label":"概况","icon":"DashboardOutlined","perm":"","sort":10},
              {"id":"warehouse:project:/model/members","scope":"project","group":"","path":"/model/members",
               "label":"项目成员","icon":"TeamOutlined","perm":"iam:member","sort":20}]}
            """.formatted(WAREHOUSE_WORDS);

    private static HttpServer server;
    private static String base;

    @Autowired
    private MockMvc mvc;

    private static String adminToken;
    private static String memberToken;
    private static String tenantId;
    private static String userId;
    private static String projectId;
    private static String project2Id;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/warehouse/menu.json", exchange -> {
            byte[] bytes = WAREHOUSE_MENU.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException ignored) {
                // 无所谓
            }
        });
        server.setExecutor(null);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken == null) {
            adminToken = login("admin", "123456");

            Resp created = call(post("/api/platform/tenants")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"产品角色验证租户\",\"adminUserId\":\"admin\"}"));
            assertEquals(200, created.status(), "创建租户失败: " + created.body());
            tenantId = MAPPER.readTree(created.body()).path("id").asText();

            Resp project = call(post("/api/v1/tenants/" + tenantId + "/projects")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Tenant-Code", TENANT_CODE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + PROJECT_CODE + "\",\"name\":\"角色验证项目\"}"));
            assertEquals(200, project.status(), "建项目失败: " + project.body());
            projectId = MAPPER.readTree(project.body()).path("id").asText();

            Resp project2 = call(post("/api/v1/tenants/" + tenantId + "/projects")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Tenant-Code", TENANT_CODE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + PROJECT2_CODE + "\",\"name\":\"角色验证项目二号\"}"));
            assertEquals(200, project2.status(), "建第二个项目失败: " + project2.body());
            project2Id = MAPPER.readTree(project2.body()).path("id").asText();

            Resp user = call(post("/api/tenants/" + tenantId + "/users")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Tenant-Code", TENANT_CODE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"" + MEMBER_USERNAME + "\",\"displayName\":\"角色成员\","
                            + "\"password\":\"123456\",\"tenantRole\":\"member\"}"));
            assertEquals(200, user.status(), "建成员失败: " + user.body());
            userId = MAPPER.readTree(user.body()).path("id").asText();
            memberToken = login(MEMBER_USERNAME, "123456");
        }

        // 词表与菜单候选来自桩服务器。端口每次都是新的（HttpServer 让内核分配），
        // 所以每轮都要重新登记 —— registry.put 是覆盖语义。
        register("warehouse", base + "/warehouse");
        // metadata 刻意不登记：它的词表拿不到，正好覆盖「拿不到词表时只校验形状」那条路。
        resetRoles();
        // 菜单与角色一样是可变的：每个用例从空菜单重来，否则上一个用例的菜单项会
        // 出现在下一个用例的侧栏断言里（同一个库跑所有用例）。
        clearNavItems();
    }

    /**
     * 把角色表恢复成「只有种子」的状态。
     *
     * <p>两个细节都是被红过的用例逼出来的：
     *
     * <ol>
     *   <li>先把管理标记还给内置的 {@code admin}，<b>再</b>删自定义角色。顺序反了的话，
     *       某个用例把标记切给自定义角色之后，那个角色会被「管理角色不能删」钉住
     *       （400），残留到下一个用例里 —— 症状是列表数量对不上、管理角色不是 admin。
     *   <li>找内置 admin 靠 {@code code == "admin"} 而<b>不是</b> {@code isAdmin}：
     *       标记被切走之后那一行的 {@code isAdmin} 已经是 false，按标记找等于找不到。
     *       身份是 {@code (product, code)}，标记是可以被改的那一个。
     * </ol>
     */
    private void resetRoles() throws Exception {
        String[] products = {"warehouse", "metadata"};
        for (String product : products) {
            for (JsonNode row : roles(product)) {
                if (row.path("builtin").asBoolean() && "admin".equals(row.path("code").asText())
                        && !row.path("isAdmin").asBoolean()) {
                    call(patch("/api/v1/platform/product-roles/" + row.path("id").asText())
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"isAdmin\":true}"));
                }
            }
        }
        // 删两轮：一轮之后本不该再有残留，第二轮只是让「清干净了」不依赖清理逻辑自身正确
        for (int pass = 0; pass < 2; pass++) {
            for (String product : products) {
                for (JsonNode row : roles(product)) {
                    if (!row.path("builtin").asBoolean()) {
                        call(delete("/api/v1/platform/product-roles/" + row.path("id").asText())
                                .header("Authorization", "Bearer " + adminToken));
                    }
                }
            }
        }
        // 成员关系也清干净，否则派角色那条用例第二次跑会拿到上一轮的行
        clearMemberships();
    }

    // ------------------------------------------------------------------
    // 种子 == 硬编码矩阵
    // ------------------------------------------------------------------

    /**
     * <b>「升级后行为不变」的全部依据。</b>
     *
     * <p>对每个产品：表里的三档内置角色，其权限词集合必须与 {@code Perms.has}
     * 对「该产品种子里出现过的全部词」逐个回答的结果<b>完全相等</b>。
     *
     * <p>两个方向都查：种子里多一个 {@code Perms} 不认的词（凭空多给权限）、
     * 少一个（凭空收回权限）。用「种子自己的词」当论域而不是另抄一份常量清单 ——
     * 抄常量清单会引入第四个可能漂移的地方。
     */
    @Test
    void seededBuiltinsMatchTheHardcodedMatrix() throws Exception {
        for (String product : new String[]{"warehouse", "metadata"}) {
            JsonNode list = roles(product);
            assertEquals(3, list.size(), product + " 应当有三档内置角色: " + list);

            Set<String> universe = new LinkedHashSet<>();
            for (JsonNode role : list) {
                assertTrue(role.path("builtin").asBoolean(), "种子角色应当都是内置的: " + role);
                for (JsonNode p : role.path("perms")) universe.add(p.path("value").asText());
            }
            assertEquals(1, countAdmin(list), product + " 应当恰有一个管理角色: " + list);

            for (JsonNode role : list) {
                String code = role.path("code").asText();
                Set<String> fromTable = values(role.path("perms"));
                Set<String> fromPerms = new LinkedHashSet<>();
                for (String word : universe) {
                    if (Perms.has(product, code, word)) fromPerms.add(word);
                }
                assertEquals(fromPerms, fromTable,
                        "「" + product + " / " + code + "」的种子权限与 Perms.java 的硬编码矩阵不一致 —— "
                                + "升级后这个角色的人权限会静默改变，而没有任何接口会报错");
            }
        }
    }

    /** 每个产品恰有一个 is_admin；内置的那三个里，管理角色只能是 admin。 */
    @Test
    void exactlyOneAdminRolePerProduct() throws Exception {
        for (String product : new String[]{"warehouse", "metadata"}) {
            JsonNode list = roles(product);
            assertEquals(1, countAdmin(list), product + " 应当恰有一个管理角色: " + list);
            for (JsonNode row : list) {
                if (row.path("isAdmin").asBoolean()) {
                    assertEquals("admin", row.path("code").asText(),
                            "管理角色应当是 code=admin 那个（AccessService.roleOf 对租户管理员"
                                    + "短路返回字符串 \"admin\"，靠它接上）: " + row);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 词表来自产品自己
    // ------------------------------------------------------------------

    /**
     * 词表必须<b>显式声明</b>，不能从菜单聚合。
     *
     * <p>桩清单的 {@code perms} 里有 {@code model:publish}，而所有候选菜单上都没有它
     * （它只在页面内的发布按钮上判）。聚合菜单的做法会漏掉它，
     * 于是「规范管理员」这个角色永远配不出发布权限 —— 而接口一切正常。
     */
    @Test
    void wordListIsSelfDeclaredNotAggregatedFromMenus() throws Exception {
        JsonNode res = productPerms("warehouse");
        Set<String> words = values(res.path("perms"));
        assertTrue(words.contains("model:publish"),
                "词表里应当有 model:publish —— 它不在任何菜单上，只有显式声明才能覆盖: " + res);

        Set<String> onMenus = new LinkedHashSet<>();
        for (JsonNode m : row(candidates(), "warehouse").path("menus")) {
            if (!m.path("perm").asText().isBlank()) onMenus.add(m.path("perm").asText());
        }
        assertFalse(onMenus.contains("model:publish"),
                "前置条件变了：model:publish 现在挂在菜单上了，这条用例不再证明「必须显式声明」");

        for (JsonNode p : res.path("perms")) {
            assertFalse(p.path("label").asText().isBlank(),
                    "词表要带人话标签，组织侧的下拉直接显示它: " + p);
        }
    }

    /** 未登记页面地址的产品：词表为空（而不是报错），前端据此回落手填。 */
    @Test
    void wordListIsEmptyForUnregisteredProduct() throws Exception {
        JsonNode res = productPerms("metadata");
        assertTrue(res.path("perms").isArray() && res.path("perms").isEmpty(),
                "拿不到词表时应当是空数组：前端据此回落手填，而不是把管理员卡死: " + res);
    }

    // ------------------------------------------------------------------
    // CRUD 与校验
    // ------------------------------------------------------------------

    /** 建、列、改、删一圈；缺省值可预期（非内置、非管理角色）。 */
    @Test
    void crudWorksEndToEnd() throws Exception {
        JsonNode created = createRole("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\"分析师\","
                + "\"hint\":\"只读\",\"sortOrder\":30,\"perms\":[\"spec:read\",\"model:read\"]}");
        assertTrue(created.path("id").asText().startsWith("prole-"), "id 应当按 prole- 惯例生成: " + created);
        assertFalse(created.path("builtin").asBoolean(), "管理面建的一律不是内置: " + created);
        assertFalse(created.path("isAdmin").asBoolean(), "不传管理标记应当是普通角色: " + created);
        assertEquals(2, created.path("permCount").asInt(), created.toString());
        // 权限词要带标签回来（取自产品自报的词表），管理页直接显示「查看规范」
        assertEquals("查看规范", created.path("perms").get(0).path("label").asText(), created.toString());

        String id = created.path("id").asText();
        Resp patched = call(patch("/api/v1/platform/product-roles/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"高级分析师\",\"perms\":[\"spec:read\"]}"));
        assertEquals(200, patched.status(), "改角色失败: " + patched.body());
        assertEquals("高级分析师", MAPPER.readTree(patched.body()).path("label").asText());
        assertEquals(1, MAPPER.readTree(patched.body()).path("permCount").asInt(),
                "perms 是全量替换语义: " + patched.body());

        assertEquals(4, roles("warehouse").size(), "3 个内置 + 1 个自定义");
        Resp removed = call(delete("/api/v1/platform/product-roles/" + id)
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(200, removed.status(), "删角色失败: " + removed.body());
        assertEquals(0, MAPPER.readTree(removed.body()).path("referenced").asInt(),
                "没人在用时也回报 0（前端照样显示「无人在用」）: " + removed.body());
        assertEquals(3, roles("warehouse").size(), "删掉之后只剩内置");
    }

    /** 不传 perms = 不改权限（而不是清空）。清空必须显式传空数组。 */
    @Test
    void omittedPermsMeansKeepNotClear() throws Exception {
        String id = createRole("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\"分析师\","
                + "\"perms\":[\"spec:read\",\"model:read\"]}").path("id").asText();

        Resp renamed = call(patch("/api/v1/platform/product-roles/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"分析师（改名）\"}"));
        assertEquals(200, renamed.status(), renamed.body());
        assertEquals(2, MAPPER.readTree(renamed.body()).path("permCount").asInt(),
                "只改名字不该动权限 —— 否则改个错别字就收回了权限: " + renamed.body());

        Resp cleared = call(patch("/api/v1/platform/product-roles/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"perms\":[]}"));
        assertEquals(0, MAPPER.readTree(cleared.body()).path("permCount").asInt(),
                "显式传空数组应当清空: " + cleared.body());
    }

    /** 未知产品码 / 形状不合法的角色码 / 超长角色名：写入时拒绝。 */
    @Test
    void invalidInputIsRejected() throws Exception {
        assertEquals(400, createRoleRaw("{\"product\":\"lineage\",\"code\":\"x1\",\"label\":\"X\"}").status(),
                "未知产品码应当被拒（与菜单同一套白名单）");

        for (String bad : new String[]{"Administrator", "1analyst", "a", "有中文", "with-dash"}) {
            Resp res = createRoleRaw("{\"product\":\"warehouse\",\"code\":\"" + bad + "\",\"label\":\"X\"}");
            assertEquals(400, res.status(),
                    "角色码「" + bad + "」应当被拒 —— 它要写进 project_members.role VARCHAR(32)，"
                            + "超长会被方言截断或不报错地存进去，判权两边就对不上了: " + res.body());
        }

        Resp over = createRoleRaw("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\""
                + "字".repeat(65) + "\"}");
        assertEquals(400, over.status(), "超长角色名应当被拒而不是被截断: " + over.body());
    }

    /**
     * 权限词必须在这个产品自报的词表里。
     *
     * <p>这是「杜绝假开关」的关键一条：写进库一个产品不认识的词，判权<b>永远判否</b>，
     * 而管理页上看起来配好了、接口也 200。
     */
    @Test
    void permOutsideWordListIsRejected() throws Exception {
        Resp res = createRoleRaw("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\"分析师\","
                + "\"perms\":[\"catalog:read\"]}");
        assertEquals(400, res.status(), "仓建设的角色不该能配数据地图的权限词: " + res.body());
        assertTrue(res.body().contains("spec:read"), "报错要列出该产品认的词，便于管理员对照: " + res.body());

        // 形状错（动作不在五档）在任何情况下都拒 —— 「编辑/查看」是按钮文案，不是权限词
        Resp shape = createRoleRaw("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\"分析师\","
                + "\"perms\":[\"catalog:edit\"]}");
        assertEquals(400, shape.status(), "形状不合法应当被拒: " + shape.body());
    }

    /** 产品与角色码是身份的一部分，改它要显式拒掉（照 NavGroup 拒绝改 scope/product 的写法）。 */
    @Test
    void identityFieldsAreImmutable() throws Exception {
        String id = createRole("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\"分析师\"}")
                .path("id").asText();

        Resp product = call(patch("/api/v1/platform/product-roles/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"metadata\"}"));
        assertEquals(400, product.status(), "改产品应当被拒: " + product.body());

        Resp code = call(patch("/api/v1/platform/product-roles/" + id)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"analyst2\"}"));
        assertEquals(400, code.status(),
                "改角色码应当被拒 —— 它写在成员记录里，改了持有者会立刻失去权限: " + code.body());
    }

    /** 同产品下重名角色码 → 400（可预期的用户错误），不是 500。 */
    @Test
    void duplicateCodeIsRejected() throws Exception {
        createRole("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\"分析师\"}");
        Resp again = createRoleRaw("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\"另一个\"}");
        assertEquals(400, again.status(), "重名角色码应当是可预期的用户错误（400）: " + again.body());

        // 换个产品就是另一个角色，必须能共存
        createRole("{\"product\":\"metadata\",\"code\":\"analyst\",\"label\":\"血缘分析\"}");
    }

    // ------------------------------------------------------------------
    // 不变量
    // ------------------------------------------------------------------

    /**
     * 「每个产品恰有一个管理角色」这条不变量，三个方向各钉一次。
     *
     * <p>破坏它的后果是管理员自己进不去：租户管理员在该产品里被 {@code AccessService.roleOf}
     * 短路映射到管理角色，没有管理角色 = 谁都判否。
     */
    @Test
    void adminInvariantIsPreserved() throws Exception {
        JsonNode builtinAdmin = adminRole("warehouse");
        String adminId = builtinAdmin.path("id").asText();

        Resp off = call(patch("/api/v1/platform/product-roles/" + adminId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isAdmin\":false}"));
        assertEquals(400, off.status(), "不允许把管理标记从当前管理角色上摘掉: " + off.body());

        Resp del = call(delete("/api/v1/platform/product-roles/" + adminId)
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(400, del.status(), "管理角色不能删: " + del.body());

        // 内置角色（非管理的那两个）也不能删
        for (JsonNode row : roles("warehouse")) {
            if (row.path("builtin").asBoolean() && !row.path("isAdmin").asBoolean()) {
                assertEquals(400, call(delete("/api/v1/platform/product-roles/" + row.path("id").asText())
                        .header("Authorization", "Bearer " + adminToken)).status(),
                        "内置角色不能删: " + row);
            }
        }

        // 把管理标记给别的角色：它上位，原来那个自动降级（「我要 B 当管理员」隐含 A 不再是）
        String customId = createRole("{\"product\":\"warehouse\",\"code\":\"chief\",\"label\":\"首席\","
                + "\"perms\":[\"spec:read\"]}").path("id").asText();
        Resp promoted = call(patch("/api/v1/platform/product-roles/" + customId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isAdmin\":true}"));
        assertEquals(200, promoted.status(), "把管理标记给另一个角色应当成功: " + promoted.body());
        assertTrue(MAPPER.readTree(promoted.body()).path("isAdmin").asBoolean());
        assertEquals(1, countAdmin(roles("warehouse")), "切换之后仍然只能有一个管理角色");
        assertFalse(roleById(adminId).path("isAdmin").asBoolean(), "原管理角色应当已自动降级");
    }

    /** 删角色不阻塞、不清成员的 role 列，只回报还有几条在用它。 */
    @Test
    void deleteReportsHoldersWithoutTouchingThem() throws Exception {
        String id = createRole("{\"product\":\"metadata\",\"code\":\"analyst\",\"label\":\"血缘分析\","
                + "\"perms\":[\"lineage:read\"]}").path("id").asText();
        assign("metadata", "analyst");

        Resp res = call(delete("/api/v1/platform/product-roles/" + id)
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "删角色失败: " + res.body());
        assertEquals(1, MAPPER.readTree(res.body()).path("referenced").asInt(),
                "应当回报仍有 1 条成员记录写着这个角色码: " + res.body());
        assertEquals("analyst", MAPPER.readTree(res.body()).path("code").asText());

        // 成员那一行没被静默改掉 —— 它此后判否（表里没这个角色了），这是可见且可解释的
        JsonNode authz = authz("metadata", "catalog:read");
        assertFalse(authz.path("allow").asBoolean(),
                "角色删掉之后应当判否（而不是悄悄回落成硬编码矩阵的权限）: " + authz);
    }

    // ------------------------------------------------------------------
    // 判权真的走表（需求 2 的目的）
    // ------------------------------------------------------------------

    /**
     * <b>建一个 {@code Perms} 里没有的角色码，派给人，然后断言它按新角色判。</b>
     *
     * <p>改动前这条链是断的：{@code putMember} 写死 {@code admin/modeler/viewer} 三值，
     * 产品专属角色码（设计稿里的 {@code catalog_admin} / {@code analyst}）根本存不进去。
     * 所以「派得上」本身就是断言的一部分。
     *
     * <p>两条判定一起断言：{@code catalog:read} 放行、{@code catalog:admin} 拒绝。
     * 只断言前者的话，「所有请求都放行」这个 bug 也是绿的。
     */
    @Test
    void customRoleActuallyDrivesAuthzCheck() throws Exception {
        // 前置：admin 这个角色码在 metadata 下是内置的，先确认它对人能放行 ——
        // 说明这条链路本身是通的，后面的 deny 不是「链路根本没走通」
        assign("metadata", "admin");
        assertTrue(authz("metadata", "catalog:admin").path("allow").asBoolean(),
                "前置条件：内置 admin 应当能过 catalog:admin");

        // 换成自定义角色码：只给读，不给 catalog:admin
        createRole("{\"product\":\"metadata\",\"code\":\"analyst\",\"label\":\"血缘分析\","
                + "\"perms\":[\"catalog:read\",\"lineage:read\"]}");
        assign("metadata", "analyst");

        JsonNode read = authz("metadata", "catalog:read");
        assertTrue(read.path("allow").asBoolean(),
                "自定义角色配了 catalog:read，判权却是拒绝的 —— 判权没有走产品角色表: " + read);
        assertEquals("analyst", read.path("role").asText(), "应当回报实际生效的角色码: " + read);

        JsonNode admin = authz("metadata", "catalog:admin");
        assertFalse(admin.path("allow").asBoolean(),
                "这个角色没有 catalog:admin，不该放行: " + admin);

        // 改这个角色的权限，下一次判权就该跟着变 —— 判权路径上不许有角色权限的缓存，
        // 「后台改完不生效、重启才好」是最难向管理员解释的一类故障
        String analystId = find("metadata", "analyst").path("id").asText();
        assertNotNull(analystId, "刚建的角色应当能在列表里找到");
        Resp patched = call(patch("/api/v1/platform/product-roles/" + analystId)
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"perms\":[\"catalog:read\",\"lineage:read\",\"catalog:admin\"]}"));
        assertEquals(200, patched.status(), patched.body());
        assertTrue(authz("metadata", "catalog:admin").path("allow").asBoolean(),
                "刚给这个角色加上的权限，判权必须立刻生效（表里那份是唯一真源）: " + patched.body());
    }

    /** 派一个没登记过的角色码：400，并指路去哪儿建（不再是写死三值的「角色不合法」）。 */
    @Test
    void assigningUnknownRoleCodeIsRejectedWithGuidance() throws Exception {
        Resp res = call(patch("/api/v1/tenants/" + tenantId + "/users/" + userId)
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberships\":[{\"projectId\":\"" + projectId + "\","
                        + "\"product\":\"metadata\",\"role\":\"nope\"}]}"));
        assertEquals(400, res.status(), "未知角色码应当被拒: " + res.body());
        assertTrue(res.body().contains("产品角色"),
                "报错要指路「在平台后台的『产品角色』里新增」: " + res.body());
    }

    /** 管理面在平台管理员门禁下面：普通成员读不到、也改不了。 */
    @Test
    void writesRequirePlatformAdmin() throws Exception {
        assertEquals(403, call(get("/api/v1/platform/product-roles")
                .header("Authorization", "Bearer " + memberToken)).status(),
                "普通成员不该能读产品角色（那是平台级配置）");
        assertEquals(403, call(get("/api/v1/platform/product-perms?product=warehouse")
                .header("Authorization", "Bearer " + memberToken)).status(),
                "普通成员不该能读词表 —— 这条路能探出各服务的内网地址");
        assertEquals(403, call(post("/api/v1/platform/product-roles")
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"warehouse\",\"code\":\"analyst\",\"label\":\"分析师\"}")).status(),
                "普通成员不该能建角色");
    }

    // ------------------------------------------------------------------

    /** 把成员派到某产品的某角色上，并断言这一步成功（需求 2 的前提）。 */
    /**
     * 侧栏按角色过滤：挂的权限词判不动，入口就不返回。
     *
     * <p>这条钉的是「菜单看得见」与「接口调得动」必须是同一条规则。分开写的后果是
     * 两种都只在真机上才发现：要么菜单看得见、点进去 403，要么有权但入口被藏起来。
     * 所以这里刻意用**自定义角色**（{@code nav_reader}，{@code Perms} 里根本没有这个码）
     * 验一遍 —— 判权走表，过滤也必须走表。
     *
     * <p>另外两条边界一起守：<b>不判权的菜单（没挂权限词）对谁都可见</b>，
     * 以及<b>不在这个项目里的人看不到项目入口</b>。
     */
    @Test
    void menuIsFilteredByRoleWords() throws Exception {
        seedMenus();

        // 前置：租户管理员三条都看得见 —— 过滤不能误伤管理员（他是该产品的管理角色）
        assertEquals(Set.of("全文检索", "临时表规则", "不判权的入口"), labelsOf(menu(adminToken, PROJECT_CODE)),
                "租户管理员应当看得见全部入口");

        createRole("{\"product\":\"metadata\",\"code\":\"nav_reader\",\"label\":\"只读检索\","
                + "\"hint\":\"只给读的那一个词\",\"perms\":[\"catalog:read\"]}");
        assignMemberships("[{\"projectId\":\"" + projectId + "\",\"product\":\"metadata\","
                + "\"role\":\"nav_reader\"}]");

        assertEquals(Set.of("全文检索", "不判权的入口"), labelsOf(menu(memberToken, PROJECT_CODE)),
                "只配了 catalog:read 的角色不该看见 catalog:admin 的入口 —— 那一点进去就是 403");

        // 不在项目里的人：连项目壳的入口都不该出现（没挂权限词的那条除外）
        assignMemberships("[]");
        assertEquals(Set.of("不判权的入口"), labelsOf(menu(memberToken, PROJECT_CODE)),
                "没加入这个项目的人不该看到项目入口，只有「不判权」的菜单对他可见");
    }

    /**
     * 同一个产品、同一个人，**换个项目结论就不同**：过滤用的是「当前项目下的角色」。
     *
     * <p>这条是前端那半边的依据：菜单结论既然跟着当前项目走，切项目就必须重拉一次
     * （`stores/app.ts` 的 `enterProject` / `leaveProject`）。少了这条断言，
     * 「用哪个项目的角色判」写错（比如一律取租户级角色）也不会有用例红。
     */
    @Test
    void menuFollowsTheRoleOfTheCurrentProject() throws Exception {
        seedMenus();
        assignMemberships("["
                + "{\"projectId\":\"" + projectId + "\",\"product\":\"metadata\",\"role\":\"viewer\"},"
                + "{\"projectId\":\"" + project2Id + "\",\"product\":\"metadata\",\"role\":\"admin\"}]");

        assertEquals(Set.of("全文检索", "不判权的入口"), labelsOf(menu(memberToken, PROJECT_CODE)),
                "在项目一里是只读角色：看不见 catalog:admin 的入口");

        assertEquals(Set.of("全文检索", "临时表规则", "不判权的入口"), labelsOf(menu(memberToken, PROJECT2_CODE)),
                "同一个人在项目二里是管理角色：同一个入口就该出现 —— 过滤用的是当前项目的角色");
    }

    /** 三条固定的菜单：一条读、一条管理、一条**不挂权限词**（进得来就看得见）。 */
    private void seedMenus() throws Exception {
        createMenuItem("{\"product\":\"metadata\",\"scope\":\"project\",\"title\":\"全文检索\","
                + "\"path\":\"/lineage/search\",\"perm\":\"catalog:read\"}");
        createMenuItem("{\"product\":\"metadata\",\"scope\":\"project\",\"title\":\"临时表规则\","
                + "\"path\":\"/lineage/settings\",\"perm\":\"catalog:admin\"}");
        createMenuItem("{\"product\":\"metadata\",\"scope\":\"project\",\"title\":\"不判权的入口\","
                + "\"path\":\"/lineage/free\"}");
    }

    private String createMenuItem(String json) throws Exception {
        Resp res = call(post("/api/v1/platform/nav-nodes")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
        assertEquals(200, res.status(), "建菜单失败: " + res.body());
        return MAPPER.readTree(res.body()).path("id").asText();
    }

    /**
     * 清掉本类建的产品菜单。
     *
     * <p><b>不能顺手把整棵树删光</b>：V23 起 {@code /nav-nodes} 里还有 org 自己的菜单种子
     * （{@code nav-sys-*} / {@code nav-proj-*}），删了它们，别的用例就找不到「项目管理」，
     * 而那种红看起来与本类毫无关系。判据用「{@code product} 非空」—— 种子的 product 都是空的。
     *
     * <p>返回的是<b>嵌套树</b>（V23 起管理面也返回树），所以收集 id 要递归。
     */
    private void clearNavItems() throws Exception {
        Resp list = call(get("/api/v1/platform/nav-nodes").header("Authorization", "Bearer " + adminToken));
        for (String id : productNodeIds(MAPPER.readTree(list.body()))) {
            call(delete("/api/v1/platform/nav-nodes/" + id)
                    .header("Authorization", "Bearer " + adminToken));
        }
    }

    private static List<String> productNodeIds(JsonNode nodes) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : nodes) {
            if (!row.path("product").asText().isBlank()) out.add(row.path("id").asText());
            out.addAll(productNodeIds(row.path("children")));
        }
        return out;
    }

    /** 消费面菜单：这个人、这个项目下该看到哪些入口（项目走 `X-Project-Code` 头，与壳一致）。 */
    private JsonNode menu(String token, String projectCode) throws Exception {
        Resp res = call(get("/api/v1/nav")
                .header("Authorization", "Bearer " + token)
                .header("X-Tenant-Code", TENANT_CODE)
                .header("X-Project-Code", projectCode));
        assertEquals(200, res.status(), "读菜单失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    /**
     * 菜单树里 <b>产品</b> 那一部分的标签（递归到叶子）。
     *
     * <p>只收 {@code product} 非空的节点：V23 之后 {@code /api/nav} 返回的是整棵树，
     * 里面还有 org 自己的菜单（项目壳的「项目」「成员管理」）。
     * 它们是壳的一部分，与「这个产品的角色判出什么」无关 —— 混进断言只会让人
     * 以为权限过滤坏了。
     *
     * <p>目录节点（没有 path）也不收：断言关心的是「哪些入口看得见」。
     */
    private static Set<String> labelsOf(JsonNode menu) {
        Set<String> out = new LinkedHashSet<>();
        collectLabels(menu, out);
        return out;
    }

    private static void collectLabels(JsonNode nodes, Set<String> out) {
        for (JsonNode row : nodes) {
            if (!row.path("product").asText().isBlank() && !row.path("path").asText().isBlank()) {
                out.add(row.path("label").asText());
            }
            collectLabels(row.path("children"), out);
        }
    }

    private void assign(String product, String role) throws Exception {
        assignMemberships("[{\"projectId\":\"" + projectId + "\",\"product\":\"" + product
                + "\",\"role\":\"" + role + "\"}]");
    }

    /**
     * 派角色（整体覆盖：这是 `memberships` 字段的语义，不是增量）。
     *
     * <p>要一次派多个项目就得一次传完 —— 分两次调后一次会把前一次覆盖掉，
     * 表现是「刚派好的项目里角色没了」，很难往这里想。
     */
    private void assignMemberships(String json) throws Exception {
        Resp res = call(patch("/api/v1/tenants/" + tenantId + "/users/" + userId)
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", TENANT_CODE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"memberships\":" + json + "}"));
        assertEquals(200, res.status(), "派角色失败: " + res.body());
    }

    private void clearMemberships() throws Exception {
        assignMemberships("[]");
    }

    /** 判权走模块间接口（真实调用方就是这么问的）。 */
    private JsonNode authz(String product, String action) throws Exception {
        Resp res = call(get("/internal/v1/authz/check")
                .header("X-Module-Token", MODULE_TOKEN)
                .queryParam("userId", userId)
                .queryParam("tenantCode", TENANT_CODE)
                .queryParam("projectCode", PROJECT_CODE)
                .queryParam("product", product)
                .queryParam("action", action));
        assertEquals(200, res.status(), "authz/check 调用失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private JsonNode roles(String product) throws Exception {
        Resp res = call(get("/api/v1/platform/product-roles").header("Authorization", "Bearer " + adminToken)
                .queryParam("product", product));
        assertEquals(200, res.status(), "读角色列表失败: " + res.body());
        JsonNode node = MAPPER.readTree(res.body());
        assertTrue(node.isArray(), "应当返回数组: " + res.body());
        return node;
    }

    private JsonNode adminRole(String product) throws Exception {
        for (JsonNode row : roles(product)) {
            if (row.path("isAdmin").asBoolean()) return row;
        }
        throw new AssertionError(product + " 没有管理角色 —— 不变量已经坏了");
    }

    private JsonNode find(String product, String code) throws Exception {
        for (JsonNode row : roles(product)) {
            if (code.equals(row.path("code").asText())) return row;
        }
        throw new AssertionError(product + " 下没有角色 " + code);
    }

    private JsonNode roleById(String id) throws Exception {
        for (String product : new String[]{"warehouse", "metadata"}) {
            for (JsonNode row : roles(product)) {
                if (id.equals(row.path("id").asText())) return row;
            }
        }
        throw new AssertionError("找不到角色 " + id);
    }

    private static int countAdmin(JsonNode list) {
        int n = 0;
        for (JsonNode row : list) {
            if (row.path("isAdmin").asBoolean()) n++;
        }
        return n;
    }

    private static Set<String> values(JsonNode array) {
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode row : array) out.add(row.path("value").asText());
        return out;
    }

    private JsonNode productPerms(String product) throws Exception {
        Resp res = call(get("/api/v1/platform/product-perms").header("Authorization", "Bearer " + adminToken)
                .queryParam("product", product));
        assertEquals(200, res.status(), "读词表失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private JsonNode candidates() throws Exception {
        Resp res = call(get("/api/v1/platform/nav-candidates")
                .header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "读候选失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private static JsonNode row(JsonNode body, String product) {
        for (JsonNode r : body.path("products")) {
            if (product.equals(r.path("product").asText())) return r;
        }
        throw new AssertionError("响应里没有产品 " + product + ": " + body);
    }

    private JsonNode createRole(String json) throws Exception {
        Resp res = createRoleRaw(json);
        assertEquals(200, res.status(), "建角色失败: " + res.body());
        return MAPPER.readTree(res.body());
    }

    private Resp createRoleRaw(String json) throws Exception {
        return call(post("/api/v1/platform/product-roles")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json));
    }

    private void register(String product, String frontendUrl) throws Exception {
        Resp res = call(post("/api/v1/platform/services")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"" + product + "\",\"frontendUrl\":\"" + frontendUrl + "\"}"));
        assertEquals(200, res.status(), "登记服务失败: " + res.body());
    }

    private String login(String username, String password) throws Exception {
        Resp res = call(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"));
        assertEquals(200, res.status(), "登录失败(" + username + "): " + res.body());
        return MAPPER.readTree(res.body()).path("token").asText();
    }

    private record Resp(int status, String body) {
    }

    private Resp call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }
}
