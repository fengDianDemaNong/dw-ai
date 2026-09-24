package controller;

import com.dwai.lineage.Main;
import com.dwai.lineage.persistence.TenantAdminRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import support.DevJwt;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * multi 模式下的租户头契约。
 *
 * <p>技术方案 §3.3 规定 multi 模式 {@code X-Tenant-Code} 必填，并且
 * 「解析不到 → 400，<b>不准静默落到租户 1</b>」。本类把这两条钉住。
 *
 * <p>为什么这条值得单独立一个测试：默认租户是本地主键 {@code 1}，与组织侧的租户没有必然关系。
 * 缺头静默回落的表现是<b>完全正常的 200</b>，但读写的可能是另一个租户的数据 ——
 * 只有断言能抓住它，人眼看页面是看不出来的。
 *
 * <p>独立 / 普通模式**不受影响**：那两种模式下缺头回落是刻意的（见 {@code RunModeSmokeTest}），
 * 本类只覆盖 multi。
 *
 * <p><b>multi 模式有两层门禁，顺序不能混</b>：
 *
 * <pre>
 *   身份（组织 JWT）   → 没令牌 401，发生在 Security 层
 *   租户上下文（请求头）→ 有令牌但头不对 400，发生在 TenantInterceptor
 * </pre>
 *
 * <p>所以下面大多数请求都带上了令牌 —— 否则拿到的是 401，测不到租户头这一层。
 * 「不带令牌必须 401」由 {@link #missingTokenIsRejectedInMultiMode} 单独覆盖。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:lineage_tenant_contract;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        "lineage.run-mode=multi",
        "lineage.org-base-url="
})
@AutoConfigureMockMvc
class TenantHeaderContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TenantAdminRepository tenants;

    /**
     * multi 模式下业务接口都要带组织 JWT，否则在 Security 层就被 401 挡掉，
     * 根本测不到租户头这一层。令牌每次现签（{@code DevJwt}），不缓存成静态常量 ——
     * 免得养成「令牌永不过期」的错觉。
     */
    private int status(String path, String... headers) throws Exception {
        var rb = get(path).header("Authorization", DevJwt.bearer());
        for (int i = 0; i < headers.length; i += 2) rb = rb.header(headers[i], headers[i + 1]);
        return mvc.perform(rb).andReturn().getResponse().getStatus();
    }

    /** 第一层门禁：没有组织令牌，连租户头这一层都到不了。 */
    @Test
    void missingTokenIsRejectedInMultiMode() throws Exception {
        assertEquals(401, mvc.perform(get("/api/stats/project")
                        .header("X-Tenant-Id", "1").header("X-Project-Id", "1"))
                .andReturn().getResponse().getStatus(),
                "multi 下不带组织令牌就被放行了 —— 那等于任何人都是合法用户");
    }

    /** multi 模式下不带任何租户头：必须拒绝，不能拿默认租户的上下文继续做事。 */
    @Test
    void missingTenantHeaderIsRejectedInMultiMode() throws Exception {
        assertEquals(400, status("/api/stats/project"),
                "缺租户头被放行了，会静默读成默认租户的数据");
    }

    /**
     * 编码解析不到：400，且<b>不得</b>顺手建一个租户出来。
     *
     * <p>拦截器里的兜底建租户只会把「编码写错」变成「静默多出一个租户」。
     * 正常的落户路径是组织调 {@code /internal/v1/projects/{code}}。
     */
    @Test
    void unknownTenantCodeIsRejectedAndNotCreated() throws Exception {
        String code = "no_such_tenant_code";
        assertEquals(400, status("/api/stats/project",
                        "X-Tenant-Code", code, "X-Project-Code", "no_such_project"),
                "未知租户编码被放行了");

        assertTrue(tenants.findTenantByCode(code).isEmpty(),
                "未知编码竟被自动落户成了一个租户 —— 数据污染");
    }

    /** 数字本地 id 仍然可用：这是单机 / 内部调用依赖的既有行为，本次不收紧它。 */
    @Test
    void numericLocalIdsStillWork() throws Exception {
        assertEquals(200, status("/api/stats/project", "X-Tenant-Id", "1", "X-Project-Id", "1"),
                "数字本地 id 的既有用法被误伤了");
    }

    /** 运行模式探测必须能在不知道租户、也<b>没有令牌</b>的前提下访问。 */
    @Test
    void runtimeIsReachableWithoutTenantOrToken() throws Exception {
        var res = mvc.perform(get("/api/runtime")).andReturn().getResponse();
        assertEquals(200, res.getStatus(),
                "模式探测被要求认证了 —— 前端要先知道模式，才知道该不该去取令牌（循环依赖）");
        assertTrue(res.getContentAsString(StandardCharsets.UTF_8).contains("multi"));
    }

    /**
     * 跨租户管理面的作用对象由路径参数指定，本来就不读租户上下文，不要求租户头。
     *
     * <p>但<b>身份仍然要</b>：它列的是所有租户，属于平台管理功能。
     */
    @Test
    void crossTenantAdminSurfaceNeedsTokenButNotTenantHeader() throws Exception {
        assertEquals(200, status("/api/tenants"),
                "跨租户管理面被要求带租户头了");
        assertEquals(401, mvc.perform(get("/api/tenants")).andReturn().getResponse().getStatus(),
                "没令牌就能列出所有租户 —— 这是平台管理接口");
    }

    /**
     * {@code /api/context} 必须仍然可用且仍然解析头 —— 它的职责就是回报「头解析出了什么」。
     *
     * <p>它属于「解析但不强制带头」那一类：multi 下缺头也要能应答（回报默认租户 + 
     * {@code tenantExists:false}），否则等于把诊断接口本身关掉。
     */
    @Test
    void contextEndpointIsReachableAndStillResolvesHeaders() throws Exception {
        var noHeader = mvc.perform(get("/api/context").header("Authorization", DevJwt.bearer()))
                .andReturn().getResponse();
        assertEquals(200, noHeader.getStatus(), "诊断接口被多租户规则挡掉了");
        assertEquals(1, MAPPER.readTree(noHeader.getContentAsString(StandardCharsets.UTF_8))
                        .path("tenantId").asInt(),
                "无头时应回报默认租户，而不是把解析整个跳过");

        var withId = mvc.perform(get("/api/context")
                        .header("Authorization", DevJwt.bearer())
                        .header("X-Tenant-Id", "1"))
                .andReturn().getResponse();
        assertEquals(1, MAPPER.readTree(withId.getContentAsString(StandardCharsets.UTF_8))
                .path("tenantId").asInt());

        // 但它不是公开接口：它会把「某个租户编码存不存在」回显出来，匿名可读等于开了个枚举口子
        assertEquals(401, mvc.perform(get("/api/context")).andReturn().getResponse().getStatus(),
                "匿名就能查租户编码存不存在");
    }
}
