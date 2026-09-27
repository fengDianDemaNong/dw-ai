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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 外观按<b>壳</b>分开存 —— {@code /api/v1/tenants/{id}/appearance?shell=workbench|project} 的契约。
 *
 * <h2>为什么单独一个契约测试</h2>
 *
 * <p>拆壳之前，工作台壳与项目壳共用 {@code scope='tenant'} 那一行：在任一边改主题/菜单风格，
 * 另一边跟着变（用户报的正是这个）。这次把一行拆成两行，最该钉住的不是「两边都能存」，
 * 而是下面这几条：
 *
 * <ol>
 *   <li>{@link #putProjectDoesNotAffectWorkbench()} —— <b>本类最重要的一条</b>。
 *       改了项目那套，工作台那套一个字都不能动。</li>
 *   <li>{@link #noShellFallsBackToLegacyTenant()} —— <b>不带 {@code shell} 时落在老口径</b>。
 *       这个端点还有第二个消费者：dw-model 前端（{@code dw-model/ui/src/api/client.ts}）
 *       读它时不带 {@code shell}。新默认值哪怕只偏一格，都会静默改掉那个产品读的是哪一行。</li>
 *   <li>{@link #workbenchRejectsDrawer()} —— {@code drawer} 在写入口被归一成 {@code left}。
 *       库里存下「工作台 + drawer」这种组合，工作台设置页的菜单风格 picker 会<b>一项都不高亮</b>。</li>
 *   <li>{@link #unknownShellIsRejected()} —— 非法 shell 值要 400，不能静默当默认值处理。</li>
 * </ol>
 *
 * <p>每个用例建自己的租户：本类共用一个 H2 内存库，几个写用例若共用租户会互相污染
 * （JUnit 的方法顺序不定）。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_appearance_shell;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.security.module-token=appearance-shell-token",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class AppearanceShellContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    private static String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        if (adminToken != null) return;
        adminToken = login("admin", "123456");
    }

    // ------------------------------------------------------------------
    // 1. 拆壳本身
    // ------------------------------------------------------------------

    /**
     * <b>本类最重要的一条。</b>改项目那套外观，工作台那套必须原封不动 ——
     * 这就是用户报的「项目和工作台设置的菜单/主题没有隔离」。
     */
    @Test
    void putProjectDoesNotAffectWorkbench() throws Exception {
        String code = "ap_split";
        String id = newTenant(code);

        // 先读两边的出厂值。它们本来就不同（left / drawer），所以下面「没变」的断言不会是假绿。
        assertEquals("left", getAppearance(id, code, "workbench").path("menuPos").asText(),
                "工作台的出厂菜单风格应当是 left（它没有顶栏，抽屉没有锚点）");
        assertEquals("drawer", getAppearance(id, code, "project").path("menuPos").asText(),
                "项目的出厂菜单风格应当是 drawer");

        Resp put = putAppearance(id, code, "project",
                "{\"theme\":\"dark\",\"menuPos\":\"top\",\"menuColor\":\"blue\"}");
        assertEquals(200, put.status(), "写项目外观失败: " + put.body());

        JsonNode wb = getAppearance(id, code, "workbench");
        assertEquals("cyan", wb.path("theme").asText(), "改项目主题动到了工作台 —— 拆壳没生效: " + wb);
        assertEquals("left", wb.path("menuPos").asText(), "改项目菜单风格动到了工作台: " + wb);
        assertEquals("ink", wb.path("menuColor").asText(), "改项目菜单栏颜色动到了工作台: " + wb);

        JsonNode pj = getAppearance(id, code, "project");
        assertEquals("dark", pj.path("theme").asText(), "项目主题没落库: " + pj);
        assertEquals("top", pj.path("menuPos").asText(), "项目菜单风格没落库: " + pj);
        assertEquals("blue", pj.path("menuColor").asText(), "项目菜单栏颜色没落库: " + pj);
    }

    /** 两套是各自独立的两行，不是「后写的覆盖前面的」—— 交叉再写一遍确认。 */
    @Test
    void themeIsStoredPerShell() throws Exception {
        String code = "ap_theme";
        String id = newTenant(code);

        assertEquals(200, putAppearance(id, code, "workbench",
                "{\"theme\":\"green\",\"menuPos\":\"top\",\"menuColor\":\"ink\"}").status());
        assertEquals(200, putAppearance(id, code, "project",
                "{\"theme\":\"orange\",\"menuPos\":\"drawer\",\"menuColor\":\"cyan\"}").status());

        assertEquals("green", getAppearance(id, code, "workbench").path("theme").asText(),
                "先写的工作台那套被后写的项目那套顶掉了");
        assertEquals("orange", getAppearance(id, code, "project").path("theme").asText());

        // 反向再改一次工作台，项目那份仍然不动
        assertEquals(200, putAppearance(id, code, "workbench", "{\"theme\":\"cyan\"}").status());
        assertEquals("orange", getAppearance(id, code, "project").path("theme").asText(),
                "第二次改工作台顶掉了项目那套");
    }

    // ------------------------------------------------------------------
    // 2. 老口径（dw-model 的兼容面）
    // ------------------------------------------------------------------

    /**
     * 不带 {@code shell} 的读写落在<b>老口径</b>（{@code scope='tenant'}）上，
     * 且它**不等于**工作台那份。
     *
     * <p>把「dw-model 读的还是原来那一行」钉成测试，而不是留一句注释 ——
     * 那个前端在另一个仓库路径下，改坏了这里看不出来。
     */
    @Test
    void noShellFallsBackToLegacyTenant() throws Exception {
        String code = "ap_legacy";
        String id = newTenant(code);

        Resp put = putAppearance(id, code, null,
                "{\"theme\":\"blue\",\"menuPos\":\"drawer\",\"menuColor\":\"ink\"}");
        assertEquals(200, put.status(), "老口径写入失败: " + put.body());
        assertEquals("blue", getAppearance(id, code, null).path("theme").asText(),
                "不带 shell 的读取没落在老口径上");

        assertEquals("cyan", getAppearance(id, code, "workbench").path("theme").asText(),
                "不带 shell 的写入串到了工作台那份 —— dw-model 与 org 会互相覆盖");
        assertEquals("cyan", getAppearance(id, code, "project").path("theme").asText(),
                "不带 shell 的写入串到了项目那份");
    }

    // ------------------------------------------------------------------
    // 3. 非法组合
    // ------------------------------------------------------------------

    /**
     * 工作台壳写 {@code drawer} 要在入口就被归一成 {@code left}：它没有项目壳那条顶栏，
     * 抽屉是 {@code absolute} + {@code top:100%} 挂在顶栏下面的，没有锚点就没有意义。
     *
     * <p>不归一的话库里的值与页面上的有效值会不一致 —— 设置页的 picker 按
     * {@code appearance.menuPos === 选项.id} 判选中，而工作台又不列 drawer 这个选项，
     * 结果是<b>一项都不高亮</b>。
     */
    @Test
    void workbenchRejectsDrawer() throws Exception {
        String code = "ap_drawer";
        String id = newTenant(code);

        assertEquals(200, putAppearance(id, code, "workbench", "{\"menuPos\":\"drawer\"}").status());
        assertEquals("left", getAppearance(id, code, "workbench").path("menuPos").asText(),
                "工作台存下了 drawer —— 菜单风格 picker 会一项都不高亮");

        // 项目壳反过来：drawer 正是它的默认，必须原样保留
        assertEquals(200, putAppearance(id, code, "project", "{\"menuPos\":\"left\"}").status());
        assertEquals(200, putAppearance(id, code, "project", "{\"menuPos\":\"drawer\"}").status());
        assertEquals("drawer", getAppearance(id, code, "project").path("menuPos").asText(),
                "项目壳的 drawer 被吃掉了");
    }

    /** 认不出的 shell 值要 400：静默当成默认值会让调用方以为写成功了。 */
    @Test
    void unknownShellIsRejected() throws Exception {
        String code = "ap_bad";
        String id = newTenant(code);

        Resp put = putAppearance(id, code, "no_such_shell", "{\"theme\":\"dark\"}");
        assertEquals(400, put.status(), "非法 shell 的写入应当被拒: " + put.body());

        Resp get = call(get("/api/v1/tenants/" + id + "/appearance")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", code)
                .param("shell", "no_such_shell"));
        assertEquals(400, get.status(), "非法 shell 的读取也应当被拒: " + get.body());

        // 被拒的那次没有落库
        assertEquals("cyan", getAppearance(id, code, "workbench").path("theme").asText(),
                "400 的那次仍然写进去了");
    }

    // ------------------------------------------------------------------
    // 助手
    // ------------------------------------------------------------------

    /**
     * 建一个租户并返回它的 id。建租户时服务端会种下 tenant / workbench / project 三行出厂值
     * （{@code PlatformService.createTenant}），所以新租户读出来就是各自的默认值。
     */
    private String newTenant(String code) throws Exception {
        Resp res = call(post("/api/platform/tenants")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"name\":\"" + code + "\",\"adminUserId\":\"admin\"}"));
        assertEquals(200, res.status(), "建租户失败(" + code + "): " + res.body());
        return MAPPER.readTree(res.body()).path("id").asText();
    }

    private JsonNode getAppearance(String tenantId, String tenantCode, String shell) throws Exception {
        MockHttpServletRequestBuilder rb = get("/api/v1/tenants/" + tenantId + "/appearance")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", tenantCode);
        if (shell != null) rb = rb.param("shell", shell);
        Resp res = call(rb);
        assertEquals(200, res.status(), "读外观失败(shell=" + shell + "): " + res.body());
        return MAPPER.readTree(res.body());
    }

    private Resp putAppearance(String tenantId, String tenantCode, String shell, String body) throws Exception {
        MockHttpServletRequestBuilder rb = put("/api/v1/tenants/" + tenantId + "/appearance")
                .header("Authorization", "Bearer " + adminToken)
                .header("X-Tenant-Code", tenantCode)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (shell != null) rb = rb.param("shell", shell);
        return call(rb);
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
