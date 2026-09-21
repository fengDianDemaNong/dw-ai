package controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import com.dwai.lineage.Main;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 租户管理接口 + {@code TenantInterceptor} 的 HTTP 层行为。
 *
 * <p>这是本仓库<b>第一个 controller 层测试</b>。此前 5 个 controller 一个测试都没有，
 * 意味着「请求头怎么变成租户上下文」这段链路完全没被覆盖 —— 而它恰恰是多租户的入口。
 *
 * <p>这里也是唯一能验证「经由 HTTP 头进来的租户也被隔离」的地方：
 * 仓储层那些跨租户负向用例用的是手工构造的 {@code LineageContext}，
 * 证明不了拦截器有没有把头正确地翻译过去。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:tenant_api_test;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password="
})
@AutoConfigureMockMvc
class TenantAdminApiTest {


    /**
     * 建表必须发生在 Spring 容器启动<b>之前</b>。
     *
     * <p>先建表再起 Spring 上下文；Flyway 见到非空库只会 baseline，不会重复建表。
     * 静态初始化块在类加载时执行，早于 Spring 上下文创建，正好赶在校验之前。
     * 放进 {@code @BeforeAll} 就晚了 —— 那时上下文已经在创建。
     */
    static {
        persistence.TestSchema.apply(
                persistence.TestDataSources.h2("tenant_api_test"), "h2");
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mvc;

    // ---------- TenantInterceptor ----------

    @Test
    void noHeaderFallsBackToDefaultTenant() throws Exception {
        mvc.perform(get("/api/context"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(1))
                .andExpect(jsonPath("$.projectId").value(1))
                .andExpect(jsonPath("$.tenantExists").value(true));
    }

    @Test
    void headersAreHonoured() throws Exception {
        long tenantId = createTenant("hdr", "请求头租户");
        long projectId = defaultProjectOf(tenantId);

        mvc.perform(get("/api/context")
                        .header("X-Tenant-Id", tenantId)
                        .header("X-Project-Id", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenantId))
                .andExpect(jsonPath("$.tenantName").value("请求头租户"));
    }

    /** 组织侧 code 尚未落户时按 code 同步，不静默回落到默认租户。 */
    @Test
    void orgTenantCodeIsProvisioned() throws Exception {
        mvc.perform(get("/api/context")
                        .header("X-Tenant-Id", "qihang")
                        .header("X-Project-Id", "default"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantExists").value(true))
                .andExpect(jsonPath("$.projectExists").value(true));
    }

    /**
     * 当前版本不校验租户是否存在（没有登录体系，见 {@code TenantInterceptor} 的说明），
     * 因此这里断言的是「照单全收 + 如实回报不存在」，由前端据此回落。
     * 哪天补上存在性校验，这条用例会失败，正好提醒同步更新前端的兜底逻辑。
     */
    @Test
    void unknownTenantIsAcceptedButReportedAsMissing() throws Exception {
        mvc.perform(get("/api/context").header("X-Tenant-Id", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(999999))
                .andExpect(jsonPath("$.tenantExists").value(false));
    }

    /** Tomcat 复用工作线程，请求结束不清理就会串租户。 */
    @Test
    void contextIsClearedAfterRequest() throws Exception {
        mvc.perform(get("/api/context").header("X-Tenant-Id", "1"))
                .andExpect(status().isOk());
        assertTrue(TenantContextHolder.find().isEmpty(),
                "请求结束后 ThreadLocal 必须已清空，否则下一个请求会读到上一个的租户");
    }

    // ---------- 建租户时的初始化 ----------

    /**
     * 新租户必须开箱可用。
     *
     * <p>V3 迁移只给 (1,1) 硬编码种了内置 CATALOG 源，新租户不补种的话，
     * 本地元数据目录就参与不了它的解析链 —— 页面上看起来一切正常，
     * 只有解析时才发现查不到自己刚导入的元数据。
     */
    @Test
    void newTenantGetsDefaultProjectAndBuiltInCatalogSource() throws Exception {
        long tenantId = createTenant("provision", "初始化验证");

        JsonNode projects = MAPPER.readTree(body(mvc.perform(
                        get("/api/tenants/" + tenantId + "/projects"))
                .andExpect(status().isOk())));
        assertEquals(1, projects.size(), "应当自动生成一个默认项目");
        assertEquals("default", projects.get(0).get("code").asText());

        long projectId = projects.get(0).get("id").asLong();
        JsonNode sources = MAPPER.readTree(body(mvc.perform(get("/api/metadata-sources")
                        .header("X-Tenant-Id", tenantId)
                        .header("X-Project-Id", projectId))
                .andExpect(status().isOk())));
        assertEquals(1, sources.size(), "新项目应当只有一条内置源");
        assertEquals("CATALOG", sources.get(0).get("type").asText());
    }

    @Test
    void newProjectAlsoGetsItsOwnCatalogSource() throws Exception {
        long tenantId = createTenant("second-proj", "多项目租户");

        JsonNode created = MAPPER.readTree(body(mvc.perform(
                        post("/api/tenants/" + tenantId + "/projects")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"code\":\"dw\",\"name\":\"数仓\"}"))
                .andExpect(status().isOk())));
        long projectId = created.get("id").asLong();

        JsonNode sources = MAPPER.readTree(body(mvc.perform(get("/api/metadata-sources")
                .header("X-Tenant-Id", tenantId)
                .header("X-Project-Id", projectId))));
        assertEquals(1, sources.size());
        assertEquals("CATALOG", sources.get(0).get("type").asText());
    }

    // ---------- 跨租户隔离（经由 HTTP 头） ----------

    @Test
    void dataWrittenUnderOneTenantIsInvisibleToAnother() throws Exception {
        long tenantA = createTenant("iso-a", "租户 A");
        long projectA = defaultProjectOf(tenantA);
        long tenantB = createTenant("iso-b", "租户 B");
        long projectB = defaultProjectOf(tenantB);

        mvc.perform(post("/api/meta/ddl")
                        .header("X-Tenant-Id", tenantA)
                        .header("X-Project-Id", projectA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbType\":\"hive\",\"ddl\":\""
                                + "create table iso_db.secret_t (id bigint comment '主键')\"}"))
                .andExpect(status().isOk());

        JsonNode mine = MAPPER.readTree(body(mvc.perform(get("/api/meta/tables")
                .header("X-Tenant-Id", tenantA)
                .header("X-Project-Id", projectA))));
        assertTrue(mine.get("total").asLong() >= 1, "租户 A 应当看得到自己刚导入的表");

        JsonNode theirs = MAPPER.readTree(body(mvc.perform(get("/api/meta/tables")
                .header("X-Tenant-Id", tenantB)
                .header("X-Project-Id", projectB))));
        assertEquals(0, theirs.get("total").asLong(), "租户 B 不该看到租户 A 的表");
    }

    // ---------- 删除保护 ----------

    @Test
    void tenantWithDataCannotBeDeleted() throws Exception {
        long tenantId = createTenant("busy-t", "有数据的租户");
        long projectId = defaultProjectOf(tenantId);

        mvc.perform(post("/api/meta/ddl")
                        .header("X-Tenant-Id", tenantId)
                        .header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbType\":\"hive\",\"ddl\":\"create table d.t (id bigint)\"}"))
                .andExpect(status().isOk());

        String message = body(mvc.perform(delete("/api/tenants/" + tenantId))
                .andExpect(status().isConflict()));
        assertTrue(message.contains("停用"), "409 的消息要给出替代方案，实际为: " + message);
    }

    @Test
    void emptyTenantCanBeDeleted() throws Exception {
        long tenantId = createTenant("empty-t", "空租户");
        mvc.perform(delete("/api/tenants/" + tenantId)).andExpect(status().isOk());

        JsonNode tenants = MAPPER.readTree(body(mvc.perform(get("/api/tenants"))));
        boolean stillThere = false;
        for (JsonNode t : tenants) {
            stillThere |= t.get("id").asLong() == tenantId;
        }
        assertFalse(stillThere, "删除后不该还在列表里");
    }

    @Test
    void defaultTenantCannotBeDeleted() throws Exception {
        mvc.perform(delete("/api/tenants/1")).andExpect(status().isBadRequest());
    }

    // ---------- 校验 ----------

    @Test
    void duplicateTenantCodeIsRejected() throws Exception {
        createTenant("dup-code", "第一个");
        mvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"dup-code\",\"name\":\"第二个\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidTenantCodeIsRejected() throws Exception {
        mvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"Bad Code!\",\"name\":\"非法编码\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void tenantCanBeDisabledAndReEnabled() throws Exception {
        long tenantId = createTenant("toggle", "开关租户");

        mvc.perform(put("/api/tenants/" + tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"toggle\",\"name\":\"开关租户\",\"status\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mvc.perform(put("/api/tenants/" + tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"toggle\",\"name\":\"开关租户\",\"status\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    /** 项目接口挂在租户路径下，拿错租户就该查不到。 */
    @Test
    void projectEndpointsAreScopedToTheirTenant() throws Exception {
        long tenantA = createTenant("scope-a", "A");
        long tenantB = createTenant("scope-b", "B");
        long projectA = defaultProjectOf(tenantA);

        mvc.perform(put("/api/tenants/" + tenantB + "/projects/" + projectA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"default\",\"name\":\"越权改名\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---------- 跨域操作的状态码 ----------

    /**
     * 拿别的租户的版本 id 调「设为当前」，必须是 400，<b>不能是 500</b>。
     *
     * <p>隔离本身一直是对的（{@code JdbcLineageRepository.markCurrent} 的 SQL 带租户过滤，
     * 确实查不到），但它抛的 {@code IllegalArgumentException} 会被 Spring 的持久化异常
     * 翻译切面（{@code @Repository} + {@code @Transactional}）包成
     * {@code InvalidDataAccessApiUsageException}，绕开 400 处理器落到兜底 ——
     * 用户看到的是「服务内部错误，请联系管理员」，而真实原因只是「这个版本不存在」。
     *
     * <p>影响不止跨租户：任何人点一个已被删掉的版本都会看到那句话。
     */
    @Test
    void markingAnotherTenantsVersionCurrentReturns400Not500() throws Exception {
        long versionId = saveLineageAndGetVersionId(1, 1);
        long otherTenant = createTenant("ver-iso", "版本隔离验证");
        long otherProject = defaultProjectOf(otherTenant);

        String message = body(mvc.perform(put("/api/lineage/versions/" + versionId + "/current")
                        .header("X-Tenant-Id", otherTenant)
                        .header("X-Project-Id", otherProject))
                .andExpect(status().isBadRequest()));

        assertTrue(message.contains("版本不存在"),
                "应当如实说明版本不存在，而不是「服务内部错误」，实际为: " + message);
        assertFalse(message.contains("联系管理员"),
                "不该落到兜底处理器，实际为: " + message);
    }

    /** 删除走的是另一条路径，一并钉住 —— 两个接口此前一个 400 一个 500，正是没测试才漂移的。 */
    @Test
    void deletingAnotherTenantsVersionReturns400() throws Exception {
        long versionId = saveLineageAndGetVersionId(1, 1);
        long otherTenant = createTenant("ver-del-iso", "版本删除隔离验证");
        long otherProject = defaultProjectOf(otherTenant);

        mvc.perform(delete("/api/lineage/versions/" + versionId)
                        .header("X-Tenant-Id", otherTenant)
                        .header("X-Project-Id", otherProject))
                .andExpect(status().isBadRequest());
    }

    /** 正常路径不能被一起改坏：自己的版本仍然能设为当前。 */
    @Test
    void markingOwnVersionCurrentStillWorks() throws Exception {
        long versionId = saveLineageAndGetVersionId(1, 1);
        mvc.perform(put("/api/lineage/versions/" + versionId + "/current")
                        .header("X-Tenant-Id", 1).header("X-Project-Id", 1))
                .andExpect(status().isOk());
    }

    // ---------- helpers ----------

    /** 在指定域保存一段血缘，返回其中一个版本 id。 */
    private long saveLineageAndGetVersionId(long tenantId, long projectId) throws Exception {
        // 表名带上租户/项目，避免不同用例互相覆盖同一张目标表的版本
        String suffix = tenantId + "_" + projectId + "_" + System.nanoTime();
        String sql = "insert into ver" + suffix + ".tgt select id from ver" + suffix + ".src";
        JsonNode saved = MAPPER.readTree(body(mvc.perform(post("/api/lineage/save")
                        .header("X-Tenant-Id", tenantId).header("X-Project-Id", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dbType\":\"hive\",\"querySql\":\"" + sql + "\"}"))
                .andExpect(status().isOk())));
        return saved.get("versions").get(0).get("versionId").asLong();
    }

    private long createTenant(String code, String name) throws Exception {
        String json = body(mvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk()));
        return MAPPER.readTree(json).get("id").asLong();
    }

    private long defaultProjectOf(long tenantId) throws Exception {
        JsonNode projects = MAPPER.readTree(body(
                mvc.perform(get("/api/tenants/" + tenantId + "/projects"))));
        return projects.get(0).get("id").asLong();
    }

    /** 必须显式指定 UTF-8：MockMvc 的响应默认按 ISO-8859-1 解码，中文消息会变成乱码。 */
    private static String body(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        MvcResult result = actions.andReturn();
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
