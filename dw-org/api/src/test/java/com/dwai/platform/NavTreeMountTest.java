package com.dwai.platform;

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
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

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
 * 「挂载产品节点」：把一个产品清单里的节点挂到 org 的菜单树上，此后这一支的内容由产品在
 * <b>渲染时</b>提供 —— 产品新增子菜单，org 的壳上就跟着多一条，不需要任何管理动作。
 *
 * <p><b>相对 V21「挂载分组」的核心变化：匹配键从名字变成了 id</b>（{@code nav_nodes.ref}）。
 * 旧版挂的是「这个壳上叫这个名字的组」，所以「已挂载的分组不能改名」—— 组名就是匹配键，
 * 改一下就与产品清单再也对不上，表现为「改名成功、侧栏里这一组整个消失」。新版挂的是
 * {@code (product, ref)}，标题只是显示名，改名因此重新变成一件安全的事
 * （{@link #renamingAMountedNodeIsAllowed()}）。
 *
 * <p>挂的粒度也变了：以前只能挂「整个分组」，现在可以挂产品树上的<b>任意节点</b> ——
 * 挂目录得到一棵子树（{@link #mountingADirectoryBringsItsWholeSubtree()}），
 * 挂叶子得到一条可点的项（{@link #mountingALeafBringsOneClickableItem()}）。
 *
 * <h2>为什么要一整层测试</h2>
 *
 * 这个功能把一条<b>跨进程</b>依赖放进了侧栏的渲染路径（{@code NavNodeService.treeFor}
 * 每次都要问产品「你现在报了什么」）。跨进程的东西会以三种方式坏，而三种都<b>不报错</b>：
 *
 * <ol>
 *   <li><b>退化成快照</b>：挂载时拉一次就存下来 —— 接口全 200，侧栏看起来完全正常，
 *       只是产品后来加的菜单永远不出现。{@link #productAddingAChildShowsUpWithoutAnyAdminAction()}
 *       是唯一能发现它的断言，所以它带上「不做任何管理动作」这个前置。</li>
 *   <li><b>接管过头</b>：把「产品报过的所有路径」都当成接管对象 —— 只挂了 A 支，
 *       却把 B 支的手工行一起吞掉，那一项从侧栏<b>直接消失</b>、没有任何痕迹。
 *       {@link #manualRowOutsideTheMountedSubtreeIsNotTakenOver()} 钉住接管范围。</li>
 *   <li><b>某个产品拉不到、整支跟着空</b>：拉不到时若也展开（展开成空），那一支连同老的
 *       手工行一起消失。侧栏是关键路径，宁可显示旧配置 ——
 *       {@link #unreachableProductFallsBackToCopiedRows()} 钉住「两个产品里坏一个」。</li>
 * </ol>
 *
 * <h2>三条测试写法上的注意</h2>
 *
 * <ul>
 *   <li><b>缓存 TTL 调成 0</b>（{@code dwai.nav.candidate-ttl-seconds=0}）。默认 300 秒的
 *       缓存在生产上是对的（挡的是每次渲染都去打产品），但「产品加菜单后是否自动出现」
 *       这个断言在它下面永远跑不过。</li>
 *   <li><b>「产品拉不到」那条用 quality 而不是 metadata</b>：拉取失败会留下 60 秒的失败记忆
 *       （{@code MenuCandidateService.permMiss}），落在 metadata 上会把同一套库里的
 *       其它用例一起带红 —— 那种红看起来像功能坏了，其实是测试互相污染。</li>
 *   <li><b>第二个桩产品是逐用例登记、用完摘掉的</b>（见 {@link #PRODUCT_B}）：注册表在整类里
 *       共享，而别的用例的断言几乎都是「这一支里恰好有 metadata 那几条」。常驻一个也报着
 *       同名节点的产品会把它们全部带偏。</li>
 * </ul>
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_nav_tree;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=nav-tree-module-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456",
        "dwai.nav.candidate-ttl-seconds=0"
})
@AutoConfigureMockMvc
class NavTreeMountTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TENANT_CODE = "navt_t1";

    /** 种子的 id —— 清库时要留着（它们是两个壳的 org 自有菜单）。 */
    private static final Set<String> SEED_IDS = Set.of(
            "nav-sys", "nav-sys-users", "nav-sys-roles", "nav-sys-projects",
            "nav-sys-knowledge", "nav-sys-settings",
            // V27 加的两个工作台入口，理由同 NavNodeTest.SEED_IDS：漏了会被当本用例自建的行删掉。
            "nav-sys-modules", "nav-sys-compute",
            "nav-proj", "nav-proj-members");

    private static final String PRODUCT = "metadata";
    /**
     * 第二个桩产品 —— 它自己报了一棵「数仓流水」的树，与 metadata 那棵毫无关系。
     *
     * <p>只在 {@link #twoMountedNodesUnderOneDirectory()} 里登记，用完就摘：注册表在整类里
     * 共享，而别的用例的断言都是「这一支里恰好有 metadata 那几条」。
     */
    private static final String PRODUCT_B = "warehouse";

    // ------------------------------------------------------------------
    // 桩服务器返回的清单。
    //
    // 子节点刻意**不写 scope** —— 它该从父节点继承（见 MenuCandidateService.normalize 的说明），
    // 所以这几条候选同时也在验证那条继承规则。
    // ------------------------------------------------------------------

    /** 只读成员有的那种权限词。 */
    private static final String TABLES =
            "{\"id\":\"metadata:project:/lineage/tables\",\"path\":\"/lineage/tables\",\"label\":\"数据表\","
                    + "\"icon\":\"DatabaseOutlined\",\"perm\":\"catalog:read\",\"sort\":10}";
    private static final String CATALOGS =
            "{\"id\":\"metadata:project:/lineage/catalogs\",\"path\":\"/lineage/catalogs\",\"label\":\"数据目录\","
                    + "\"icon\":\"FolderOutlined\",\"perm\":\"\",\"sort\":20}";
    /** 权限词只有管理员有 —— 用来验展开项照样判权。 */
    private static final String ADMIN_ONLY =
            "{\"id\":\"metadata:project:/lineage/rules\",\"path\":\"/lineage/rules\",\"label\":\"临时表规则\","
                    + "\"icon\":\"SafetyOutlined\",\"perm\":\"catalog:admin\",\"sort\":15}";
    /** 产品发版后新增的那条 —— 自动跟随的用例靠它。 */
    private static final String SEARCH =
            "{\"id\":\"metadata:project:/lineage/search\",\"path\":\"/lineage/search\",\"label\":\"全文检索\","
                    + "\"icon\":\"SearchOutlined\",\"perm\":\"\",\"sort\":5}";

    /** 目录节点「数据地图」：挂它应当把下面几条一起带进来。 */
    private static final String MAP_DIR =
            "{\"id\":\"metadata:project:map\",\"scope\":\"project\",\"path\":\"\",\"label\":\"数据地图\","
                    + "\"icon\":\"DatabaseOutlined\",\"perm\":\"\",\"sort\":10,\"children\":["
                    + TABLES + "," + ADMIN_ONLY + "," + CATALOGS + "]}";
    /** 另一支（没被挂载的那一支）—— 证明只展开被挂的那一枝。 */
    private static final String BIO_DIR =
            "{\"id\":\"metadata:project:bio\",\"scope\":\"project\",\"path\":\"\",\"label\":\"血缘分析\","
                    + "\"icon\":\"ShareAltOutlined\",\"perm\":\"\",\"sort\":30,\"children\":["
                    + "{\"id\":\"metadata:project:/lineage/tasks\",\"path\":\"/lineage/tasks\",\"label\":\"解析任务\","
                    + "\"icon\":\"ShareAltOutlined\",\"perm\":\"\",\"sort\":30}]}";
    /** 工作台壳上的叶子 —— 证明壳跟着节点自己报的 scope 走。 */
    private static final String SETTINGS =
            "{\"id\":\"metadata:workbench:settings\",\"scope\":\"workbench\",\"path\":\"/lineage/settings\","
                    + "\"label\":\"设置\",\"icon\":\"SettingOutlined\",\"perm\":\"\",\"sort\":40}";

    /** 挂目录用的 ref。 */
    private static final String REF_MAP = "metadata:project:map";
    /** 挂叶子用的 ref。 */
    private static final String REF_TASKS = "metadata:project:/lineage/tasks";

    // ---- 第二个桩产品（warehouse）----

    private static final String WH_DIR =
            "{\"id\":\"warehouse:project:pipeline\",\"scope\":\"project\",\"path\":\"\",\"label\":\"数仓流水\","
                    + "\"icon\":\"BlockOutlined\",\"perm\":\"\",\"sort\":10,\"children\":["
                    + "{\"id\":\"warehouse:project:/model/models\",\"path\":\"/model/models\",\"label\":\"模型管理\","
                    + "\"icon\":\"BlockOutlined\",\"perm\":\"\",\"sort\":10},"
                    + "{\"id\":\"warehouse:project:/model/marts\",\"path\":\"/model/marts\",\"label\":\"数据集市\","
                    + "\"icon\":\"AppstoreOutlined\",\"perm\":\"\",\"sort\":20}]}";

    /** 桩服务器当前返回的清单。用例里改它就等于产品发了一次版。 */
    private static volatile String body = menusJson(MAP_DIR, BIO_DIR, SETTINGS);
    private static volatile String bodyB = menusJsonFor(PRODUCT_B, WH_DIR);

    private static HttpServer server;
    private static String base;
    /** {@link #PRODUCT_B} 登记的「页面地址」—— 同一个桩服务器的另一个路径前缀。 */
    private static String base2;
    /** 一个确定空闲（没人监听）的端口 —— 用来构造「产品拉不到」。 */
    private static String deadBase;

    @Autowired
    private MockMvc mvc;

    private static String adminToken;
    private static String memberToken;
    private static String tenantId;

    @BeforeAll
    static void startStub() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // 用 Supplier 而不是固定字符串：同一个地址上换内容 = 产品发版
        server.createContext("/menu.json", respond(() -> body));
        // 第二个产品的清单挂在同一个服务器的另一个路径前缀下。org 取清单的地址是
        // 「页面地址 + /menu.json」，所以两个产品只要登记的页面地址不同就够了。
        server.createContext("/wh/menu.json", respond(() -> bodyB));
        server.setExecutor(null);
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        base2 = base + "/wh";

        try (ServerSocket probe = new ServerSocket(0)) {
            deadBase = "http://127.0.0.1:" + probe.getLocalPort();
        }
    }

    @AfterAll
    static void stopStub() {
        if (server != null) server.stop(0);
    }

    @BeforeEach
    void setUp() throws Exception {
        body = menusJson(MAP_DIR, BIO_DIR, SETTINGS);
        bodyB = menusJsonFor(PRODUCT_B, WH_DIR);

        if (adminToken == null) {
            adminToken = login("admin", "123456");
            Resp created = call(post("/api/platform/tenants")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"code\":\"" + TENANT_CODE + "\",\"name\":\"挂载树验证租户\",\"adminUserId\":\"admin\"}"));
            assertEquals(200, created.status(), "创建租户失败: " + created.body());
            tenantId = MAPPER.readTree(created.body()).path("id").asText();

            Resp user = call(post("/api/tenants/" + tenantId + "/users")
                    .header("Authorization", "Bearer " + adminToken)
                    .header("X-Tenant-Code", TENANT_CODE)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"navt_member\",\"displayName\":\"挂载成员\","
                            + "\"password\":\"123456\",\"tenantRole\":\"member\"}"));
            assertEquals(200, user.status(), "建成员失败: " + user.body());
            memberToken = login("navt_member", "123456");
        }

        // 同一套库跑所有用例，而树是可变的：每个用例从「只剩种子」重来，
        // 否则用例之间会互相看见对方留下的行（同层同名会直接撞唯一约束）。
        for (String id : new ArrayList<>(ownedNodeIds())) {
            call(delete("/api/v1/platform/nav-nodes/" + id).header("Authorization", "Bearer " + adminToken));
        }
        // quality 在这里登记成一个「没人监听」的地址：验证降级要用它。
        // 端口每次都是新的，所以要每个用例重新登记（registry.put 是覆盖语义）。
        register(PRODUCT, base);
        register("quality", deadBase);
        setModules("[\"warehouse\",\"metadata\",\"quality\"]");
    }

    // ------------------------------------------------------------------
    // 展开
    // ------------------------------------------------------------------

    /**
     * 挂一个<b>目录节点</b>，整棵子树跟着进来 —— 而且一条手工节点都没建。
     *
     * <p>这正是与「从服务拉取菜单」的区别：那个动作把每一行都复制进 org 的库，复制完就与
     * 产品脱钩了。挂载不复制，所以产品后面加的菜单才跟得上。
     */
    @Test
    void mountingADirectoryBringsItsWholeSubtree() throws Exception {
        mount("project", "数据地图", PRODUCT, REF_MAP);

        JsonNode tree = navTree(memberToken);
        JsonNode dir = findByLabel(tree, "数据地图");
        assertNotNull(dir, "挂上的目录应当出现: " + tree);
        assertEquals("", dir.path("path").asText(),
                "产品报的是目录（没有路径），org 侧这一行也该是目录: " + dir);
        assertTrue(dir.path("mounted").asBoolean(), "要带出挂载态: " + dir);

        // 只读成员看得到 catalog:read 与不带权限词的两条，看不到 catalog:admin 那条
        assertEquals(2, dir.path("children").size(),
                "只读成员不该看到 catalog:admin 那条 —— 菜单看得见、点进去 403 是最坏的一种: " + dir);
        JsonNode tables = findByPath(tree, "/lineage/tables");
        assertNotNull(tables, tree.toString());
        assertEquals("数据表", tables.path("label").asText(), tables.toString());
        assertEquals("catalog:read", tables.path("perm").asText(), tables.toString());
        assertEquals(PRODUCT, tables.path("product").asText(),
                "每一项都要标明自己来自哪个产品 —— 判权是按产品算的: " + tables);
        assertEquals(base, tables.path("frontendUrl").asText(),
                "展开项也要带上产品前端地址，否则侧栏里这一项点不动: " + tables);

        assertEquals(1, ownNodes().size(),
                "挂载是引用，库里只该有那一条挂载行，不该复制任何子节点: " + ownNodes());
    }

    /** 挂一个<b>叶子节点</b>：得到一条可点的项，不会多出一层空目录。 */
    @Test
    void mountingALeafBringsOneClickableItem() throws Exception {
        mount("project", "解析任务", PRODUCT, REF_TASKS);

        JsonNode tree = navTree(memberToken);
        JsonNode node = findByLabel(tree, "解析任务");
        assertNotNull(node, "挂上的叶子应当出现: " + tree);
        assertEquals("/lineage/tasks", node.path("path").asText(),
                "路径要取自产品那一项，否则点了没反应: " + node);
        assertEquals(0, node.path("children").size(),
                "挂叶子不该多出一层同名的空目录: " + node);
    }

    /** 壳跟着节点自己报的 scope 走：工作台壳的叶子挂在项目壳里时…… 它就是工作台壳的一员。 */
    @Test
    void mountedNodeKeepsItsOwnShell() throws Exception {
        mount("workbench", "设置", PRODUCT, "metadata:workbench:settings");

        JsonNode node = findByLabel(navTree(memberToken), "设置");
        assertNotNull(node, "挂载行应当出现: " + navTree(memberToken));
        assertEquals("workbench", node.path("scope").asText(),
                "壳由产品那一项自己报，org 侧的行只是跟随: " + node);
    }

    /**
     * <b>产品新增一条子菜单，不做任何管理动作，侧栏就带上它。</b>
     *
     * <p>这是整个挂载功能的全部目的，也是三种「不报错的坏法」里最隐蔽的一种（退化成快照）
     * 唯一能现形的断言：中间不点「从服务拉取菜单」、不重新挂载、不改任何配置，
     * 只让产品那边多报一条。
     */
    @Test
    void productAddingAChildShowsUpWithoutAnyAdminAction() throws Exception {
        mount("project", "数据地图", PRODUCT, REF_MAP);
        assertEquals(2, findByLabel(navTree(memberToken), "数据地图").path("children").size(),
                "前置条件：挂载后应当是两条");

        // 产品发了一次版：「数据地图」下多了一条「全文检索」
        body = menusJson(
                "{\"id\":\"metadata:project:map\",\"scope\":\"project\",\"path\":\"\",\"label\":\"数据地图\","
                        + "\"icon\":\"DatabaseOutlined\",\"perm\":\"\",\"sort\":10,\"children\":["
                        + TABLES + "," + CATALOGS + "," + SEARCH + "]}",
                BIO_DIR, SETTINGS);

        JsonNode after = findByLabel(navTree(memberToken), "数据地图");
        assertEquals(3, after.path("children").size(),
                "产品加了子菜单而侧栏没跟上 —— 挂载退化成了快照: " + after);
        // 注意传的是 children：findByPath 的入参是一层**数组**，直接给节点对象会去遍历它的
        // 字段（Jackson 对 ObjectNode 的迭代是字段值），得到一个永远是 null 的查找
        assertEquals("全文检索", findByPath(after.path("children"), "/lineage/search").path("label").asText(),
                after.toString());
        assertEquals(1, ownNodes().size(), "这中间不该产生任何新行: " + ownNodes());

        // 反方向：产品把子菜单删掉
        body = menusJson(
                "{\"id\":\"metadata:project:map\",\"scope\":\"project\",\"path\":\"\",\"label\":\"数据地图\","
                        + "\"icon\":\"DatabaseOutlined\",\"perm\":\"\",\"sort\":10,\"children\":["
                        + TABLES + "]}",
                BIO_DIR, SETTINGS);
        JsonNode shrunk = findByLabel(navTree(memberToken), "数据地图");
        assertEquals(1, shrunk.path("children").size(), "产品删掉的菜单不该还留在侧栏: " + shrunk);
        assertEquals("/lineage/tables", shrunk.path("children").get(0).path("path").asText(), shrunk.toString());
    }

    /** 取消挂载：产品提供的那几条立刻消失（先丢缓存再读，见 {@code NavNodeService.dropCandidateCache}）。 */
    @Test
    void unmountingRemovesTheExpandedItems() throws Exception {
        String id = mount("project", "数据地图", PRODUCT, REF_MAP).path("id").asText();
        assertEquals(2, findByLabel(navTree(memberToken), "数据地图").path("children").size(),
                "前置条件：挂载后应当是两条");

        Resp res = patchNode(id, "{\"mounted\":false}");
        assertEquals(200, res.status(), "取消挂载失败: " + res.body());
        assertFalse(MAPPER.readTree(res.body()).path("mounted").asBoolean(),
                "返回值要如实带出挂载态，否则页面上那个开关点完看不出变化: " + res.body());

        // 取消挂载之后它变回「手工节点」：没有子节点、也没有路径 → 空目录 → 默认策略下不出现
        assertNull(findByLabel(navTree(memberToken), "数据地图"),
                "取消挂载后产品提供的那几条应当消失（这里没有复制行，所以整支为空）: "
                        + navTree(memberToken));
    }

    /** 挂载节点<b>可以改名</b> —— 匹配键是 ref 而不是标题，改名只影响显示。 */
    @Test
    void renamingAMountedNodeIsAllowed() throws Exception {
        String id = mount("project", "数据地图", PRODUCT, REF_MAP).path("id").asText();

        Resp res = patchNode(id, "{\"title\":\"我的数据分析\"}");
        assertEquals(200, res.status(),
                "匹配键是 ref，改名不该被拒 —— 这是相对 V21「已挂载的分组不能改名」的改进: " + res.body());

        JsonNode tree = navTree(memberToken);
        assertNull(findByLabel(tree, "数据地图"), "旧名字应当消失: " + tree);
        JsonNode renamed = findByLabel(tree, "我的数据分析");
        assertNotNull(renamed, "新名字应当出现: " + tree);
        assertEquals(2, renamed.path("children").size(),
                "改的只是显示名，产品提供的内容一条都不该少: " + renamed);
    }

    // ------------------------------------------------------------------
    // 接管：按路径去重
    // ------------------------------------------------------------------

    /**
     * 早先复制下来的手工行被挂载接管，不会出现两遍。
     *
     * <p>「先复制过、后来又挂了同一支」是最常见的状态，不接管的话同一批菜单会在侧栏里
     * 出现两遍 —— 而且两遍长得几乎一样，管理员无从判断该删哪一条。
     */
    @Test
    void manualRowWithSamePathIsTakenOver() throws Exception {
        createNode("{\"scope\":\"project\",\"title\":\"手抄的数据表\",\"product\":\"" + PRODUCT + "\","
                + "\"path\":\"/lineage/tables\"}");
        assertEquals("手抄的数据表", findByPath(navTree(memberToken), "/lineage/tables").path("label").asText(),
                "前置条件：没挂载时看的是手工行");

        mount("project", "数据地图", PRODUCT, REF_MAP);

        JsonNode tree = navTree(memberToken);
        JsonNode tables = findByPath(tree, "/lineage/tables");
        assertEquals("数据表", tables.path("label").asText(),
                "接管后这一条以产品报的为准: " + tables);
        assertEquals(1, pathsOf(tree).stream().filter("/lineage/tables"::equals).count(),
                "同路径的手工行应当被接管，而不是与展开项并存: " + pathsOf(tree));

        assertEquals(2, ownNodes().size(),
                "接管是渲染层的事，不该悄悄删掉管理员配置的那一行: " + ownNodes());
    }

    /**
     * <b>接管范围只限「展开真的会吐出来的那些路径」。</b>
     *
     * <p>手工行与产品在<b>另一个没被挂载的分支</b>里报的路径相同时，那条手工行必须照常渲染。
     * 否则「挂了 A 支」会顺手把 B 支的一条菜单弄消失，而侧栏上没有任何痕迹说明为什么 ——
     * 管理员只会看到「我挂了一组，结果少了一条」。
     */
    @Test
    void manualRowOutsideTheMountedSubtreeIsNotTakenOver() throws Exception {
        createNode("{\"scope\":\"project\",\"title\":\"手抄的解析任务\",\"product\":\"" + PRODUCT + "\","
                + "\"path\":\"/lineage/tasks\"}");

        mount("project", "数据地图", PRODUCT, REF_MAP);

        JsonNode tree = navTree(memberToken);
        JsonNode tasks = findByPath(tree, "/lineage/tasks");
        assertNotNull(tasks, "只挂了「数据地图」，另一支的同路径手工行不该被吞掉: " + pathsOf(tree));
        assertEquals("手抄的解析任务", tasks.path("label").asText(),
                "这一条没被展开覆盖，应当照常按手工行渲染: " + tasks);
    }

    /**
     * <b>两个产品里坏一个：坏的那个降级，好的那个照常展开。</b>
     *
     * <p>这是最容易做错的地方 —— 一旦把「这个产品拉不到」与「这一支没人报」混成一个判断，
     * 某个产品的页面地址没配好就会让整支从侧栏消失（连带管理员早先复制下来的手工行）。
     * 侧栏是关键路径，宁可显示旧配置。
     *
     * <p>降级与接管是同一个开关的两面：拉不到时<b>既不展开也不接管</b>，
     * 所以那一份复制行既不会重复、也不会丢。
     */
    @Test
    void unreachableProductFallsBackToCopiedRows() throws Exception {
        createNode("{\"scope\":\"project\",\"title\":\"手抄的质量规则\",\"product\":\"quality\","
                + "\"path\":\"/quality/rules\"}");
        // 另一个产品是好的，它必须照常展开 —— 「坏了一个不连累另一个」要有另一个可连累
        mount("project", "数据地图", PRODUCT, REF_MAP);

        // quality 拉不到，但求证在「拉不到」时放水（不能拿一处配置缺失把功能锁死），所以挂得上
        mount("project", "质量规则", "quality", "quality:project:/quality/rules");

        JsonNode tree = navTree(memberToken);
        JsonNode fallback = findByPath(tree, "/quality/rules");
        assertNotNull(fallback,
                "产品拉不到时复制行是这一支唯一还能显示的东西，不能被吞掉: " + pathsOf(tree));
        assertEquals("手抄的质量规则", fallback.path("label").asText(), fallback.toString());
        assertEquals(deadBase, fallback.path("frontendUrl").asText(),
                "降级渲染的复制行也要带前端地址 —— 「拉不到」说的是产品清单，不是这个产品的站点: "
                        + fallback);

        assertNotNull(findByPath(tree, "/lineage/tables"),
                "坏的是一个产品，不该连累另一个产品的展开: " + pathsOf(tree));
    }

    /** 产品拉不到、又没有手工行时，挂载行展开成一个空目录 —— 按 {@code empty_policy} 处理。 */
    @Test
    void unreachableProductWithNoFallbackFollowsEmptyPolicy() throws Exception {
        mount("project", "空挂载-隐藏", "quality", "quality:project:/x");
        mountAlways("project", "空挂载-保留", "quality", "quality:project:/y");

        JsonNode tree = navTree(memberToken);
        assertNull(findByLabel(tree, "空挂载-隐藏"),
                "默认策略下展开为空的挂载行不该出现（它就是升级前的「空分组不渲染」）: " + pathsOf(tree));
        JsonNode kept = findByLabel(tree, "空挂载-保留");
        assertNotNull(kept, "always 策略下要保留并说明原因: " + pathsOf(tree));
        assertTrue(kept.path("children").get(0).path("disabled").asBoolean(), kept.toString());
    }

    /**
     * <b>一个目录下混装两个产品的菜单</b> —— 「壳的菜单树，产品只提供其中几支」的落地形态。
     *
     * <p>org 侧建一个目录「数据分析」，下面挂两个挂载行：一支指向 metadata 的树、
     * 一支指向 warehouse 的树。侧栏里它们就在同一个目录下。
     *
     * <p>钉住三件事：① 挂载的粒度是节点（可以是目录，也可以是叶子）；
     * ② 同一个目录下可以挂不同产品的节点；③ 依然一条子节点都不复制进库。
     */
    @Test
    void twoMountedNodesUnderOneDirectory() throws Exception {
        register(PRODUCT_B, base2);
        try {
            String dir = createNode("{\"scope\":\"project\",\"title\":\"数据分析\"}").path("id").asText();
            mountUnder(dir, "血缘", PRODUCT, REF_MAP);
            mountUnder(dir, "数仓", PRODUCT_B, "warehouse:project:pipeline");

            JsonNode node = findById(navTree(memberToken), dir);
            assertNotNull(node, "混装目录应当出现: " + pathsOf(navTree(memberToken)));
            assertEquals(2, node.path("children").size(), "两个产品的挂载行都该在这个目录下: " + node);

            JsonNode models = findByPath(node.path("children"), "/model/models");
            assertNotNull(models, "warehouse 那支的菜单要在: " + node);
            assertEquals(base2, models.path("frontendUrl").asText(),
                    "每一项都带**自己产品**的页面地址，否则点了会跳到另一个产品里去: " + models);
            assertNotNull(findByPath(node.path("children"), "/lineage/tables"),
                    "metadata 那支的菜单也要在: " + node);

            assertEquals(3, ownNodes().size(),
                    "混装靠的是展开：库里只该有目录 + 两条挂载行: " + ownNodes());
        } finally {
            // 注册表在本类各用例间共享：不摘掉的话，后面每个用例的侧栏都会多出
            // warehouse 那一支（见 PRODUCT_B 的说明）
            call(delete("/api/v1/platform/services/" + PRODUCT_B)
                    .header("Authorization", "Bearer " + adminToken));
        }
    }

    // ------------------------------------------------------------------
    // 写入时的校验
    // ------------------------------------------------------------------

    /**
     * {@code ref} 在产品清单里找不到 → 400。挂上去只会得到一个永远空着的目录。
     *
     * <p>报错要摊开「这个产品现在报了什么」：只回一句「没有这个节点」时，管理员看到产品
     * 明明有这个页面，却没有任何线索知道该去核对哪个 id。
     */
    @Test
    void mountingAnUnknownRefIsRejected() throws Exception {
        Resp res = postNode("{\"scope\":\"project\",\"title\":\"挂错\",\"product\":\"" + PRODUCT + "\","
                + "\"ref\":\"nope\",\"mounted\":true}");
        assertEquals(400, res.status(), "清单里没有的 id 应当被拒: " + res.body());
        assertTrue(res.body().contains("数据地图") && res.body().contains("血缘分析"),
                "报错要把产品现在报的顶层节点摊开，管理员才知道该挂哪个: " + res.body());
        assertEquals(0, ownNodes().size(), "被拒的挂载不该留下任何行");
    }

    /** 挂载是平台配置动作，普通成员不该能挂。 */
    @Test
    void mountingRequiresPlatformAdmin() throws Exception {
        Resp res = call(post("/api/v1/platform/nav-nodes")
                .header("Authorization", "Bearer " + memberToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scope\":\"project\",\"title\":\"X\",\"product\":\"" + PRODUCT + "\","
                        + "\"ref\":\"" + REF_MAP + "\",\"mounted\":true}"));
        assertEquals(403, res.status(), "普通成员不该能挂载: " + res.body());
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

    /** 建一条顶层挂载行（默认空目录策略 = 看不到就整支不出现）。 */
    private JsonNode mount(String scope, String title, String product, String ref) throws Exception {
        return mount(scope, title, product, ref, "hide");
    }

    private JsonNode mountAlways(String scope, String title, String product, String ref) throws Exception {
        return mount(scope, title, product, ref, "always");
    }

    private JsonNode mount(String scope, String title, String product, String ref, String emptyPolicy)
            throws Exception {
        Resp res = postNode("{\"scope\":\"" + scope + "\",\"title\":\"" + title + "\",\"product\":\"" + product
                + "\",\"ref\":\"" + ref + "\",\"mounted\":true,\"emptyPolicy\":\"" + emptyPolicy + "\"}");
        assertEquals(200, res.status(), "挂载失败: " + res.body());
        JsonNode row = MAPPER.readTree(res.body());
        assertTrue(row.path("mounted").asBoolean(), "返回值要如实带出挂载态: " + row);
        return row;
    }

    private void mountUnder(String parentId, String title, String product, String ref) throws Exception {
        Resp res = postNode("{\"scope\":\"project\",\"parentId\":\"" + parentId + "\",\"title\":\"" + title
                + "\",\"product\":\"" + product + "\",\"ref\":\"" + ref + "\",\"mounted\":true}");
        assertEquals(200, res.status(), "挂载失败: " + res.body());
    }

    private JsonNode createNode(String json) throws Exception {
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

    /** 管理面里「非种子」的节点（= 测试自己建的行），清库与断言都用它。 */
    private List<String> ownNodes() throws Exception {
        Resp res = call(get("/api/v1/platform/nav-nodes").header("Authorization", "Bearer " + adminToken));
        assertEquals(200, res.status(), "读管理面菜单树失败: " + res.body());
        Set<String> ids = new LinkedHashSet<>();
        collectOwned(MAPPER.readTree(res.body()), ids);
        return new ArrayList<>(ids);
    }

    private Set<String> ownedNodeIds() throws Exception {
        return new LinkedHashSet<>(ownNodes());
    }

    private static void collectOwned(JsonNode nodes, Set<String> out) {
        for (JsonNode row : nodes) {
            if (!SEED_IDS.contains(row.path("id").asText())) out.add(row.path("id").asText());
            collectOwned(row.path("children"), out);
        }
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

    private void register(String product, String frontendUrl) throws Exception {
        Resp res = call(post("/api/v1/platform/services")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"" + product + "\",\"frontendUrl\":\"" + frontendUrl + "\"}"));
        assertEquals(200, res.status(), "登记服务失败: " + res.body());
    }

    private static String menusJson(String... items) {
        return menusJsonFor(PRODUCT, items);
    }

    /**
     * 信封里的产品码必须与登记的产品逐字一致 —— 不一致时 org 会按「页面地址填串了」
     * 把整份清单拒掉（见 {@code MenuCandidateService.fetch} 里那段自报产品码的校验），
     * 于是第二个桩产品会静默变成「拉不到」，新用例红的理由看起来跟挂载一点关系都没有。
     */
    private static String menusJsonFor(String product, String... items) {
        return "{\"product\":\"" + product + "\",\"version\":\"0.1.3\",\"menus\":["
                + String.join(",", items) + "]}";
    }

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

    private static List<String> pathsOf(JsonNode nodes) {
        List<String> out = new ArrayList<>();
        for (JsonNode row : nodes) {
            String path = row.path("path").asText();
            if (!path.isEmpty()) out.add(path);
            out.addAll(pathsOf(row.path("children")));
        }
        return out;
    }

    private record Resp(int status, String body) {
    }

    private Resp call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 每次都现取 {@code body}，所以「产品发版」在测试里就是给那个字段重新赋值。 */
    private static com.sun.net.httpserver.HttpHandler respond(Supplier<String> body) {
        return exchange -> {
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException ignored) {
                // 客户端提前断开（超时用例），无所谓
            }
        };
    }
}
