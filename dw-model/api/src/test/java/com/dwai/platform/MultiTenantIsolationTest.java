package com.dwai.platform;

import com.dwai.platform.auth.JwtIssuer;
import com.dwai.platform.meta.entity.DomainEntity;
import com.dwai.platform.meta.mapper.DomainMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * multi 模式下的租户隔离：<b>项目 id 替换不得越租户</b>。
 *
 * <p>这条测试守的是一个真实存在过的口子：组织只按<b>请求头</b>里的 tenantCode/projectCode 判权，
 * 而数据操作按<b>路径</b>里的 projectId 落库。攻击者带自己项目的头（判权通过）、
 * 把路径换成别的租户的项目 id，就能读写别人的数据 —— 因为本项目没有 MyBatis 租户拦截器，
 * 业务表（如 {@code domains}）也不带 {@code tenant_id} 列，隔离完全依赖
 * {@code AccessService.requireProject} 这一步。
 *
 * <h2>为什么要起一个桩组织平台</h2>
 *
 * <p>桩的 {@code /internal/v1/authz/check} <b>永远返回 allow</b>。这样任何被拒绝的请求都
 * 只能是模块自己的校验拦下的 —— 把「授权判定」和「租户归属校验」这两件事彻底分开：
 * 组织说「你可以做这件事」，模块仍然必须回答「这件事发生在不属于你的项目上」。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dwmodel_multi_isolation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.run-mode=multi",
        "dwai.security.mode=dev",
        "dwai.security.module-token=isolation-module-token",
        // 心跳目标指向不可达端口：本用例不验证跨服务推送
        "dwai.service-base-url=http://127.0.0.1:1",
        "dwai.public-base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class MultiTenantIsolationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODULE_TOKEN = "isolation-module-token";

    /** 桩组织平台。静态初始化：必须早于 Spring 上下文（org-base-url 要读它的端口）。 */
    private static final HttpServer ORG_STUB = startStubOrg();

    private static HttpServer startStubOrg() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal/v1/authz/check", ex ->
                    respond(ex, 200, "{\"allow\":true,\"role\":\"admin\"}"));
            server.createContext("/internal/v1/registry/heartbeat", ex -> respond(ex, 200, "{}"));
            server.setExecutor(null);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动桩组织平台", e);
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    @DynamicPropertySource
    static void orgBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("dwai.org-base-url",
                () -> "http://127.0.0.1:" + ORG_STUB.getAddress().getPort());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtIssuer issuer;

    @Autowired
    private DomainMapper domains;

    /** 两个租户各一个项目；项目 id 由落镜像时生成，测试从响应里取。 */
    private static String projectA;
    private static String projectB;
    private static String domainB;

    private String tokenFor(String userId) {
        return issuer.issue(userId, userId, userId, false);
    }

    /** 通过模块接口落两个租户的项目（这是组织 fan-out 的正规入口）。 */
    private void seedProjects() throws Exception {
        if (projectA != null) return;
        projectA = mirrorProject("p_alpha", "alpha", "租户A的项目");
        projectB = mirrorProject("p_beta", "beta", "租户B的项目");

        // 直接写一行属于 B 的 domain，作为「别人的资源」样本（不走接口，避免依赖桩的宽松授权）
        DomainEntity d = new DomainEntity();
        d.setId("d-belongs-to-beta");
        d.setProjectId(projectB);
        d.setCode("beta_dom");
        d.setName("B 的主题域");
        d.setRelated("[]");
        d.setCoreEntities("[]");
        domains.insert(d);
        domainB = d.getId();
    }

    private String mirrorProject(String code, String tenantCode, String name) throws Exception {
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/internal/v1/projects/" + code)
                        .header("X-Module-Token", MODULE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"" + tenantCode + "\",\"name\":\"" + name + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode p = MAPPER.readTree(body);
        String id = p.path("id").asText();
        assertEquals(code, p.path("code").asText(), "落镜像失败: " + body);
        return id;
    }

    private record Resp(int status, String body) {
    }

    /** 模拟前端：把自己租户/项目的标识放请求头。 */
    private Resp asTenantA(String method, String path, String projectIdHeader) throws Exception {
        return asTenantA(method, path, projectIdHeader, null);
    }

    private Resp asTenantA(String method, String path, String projectIdHeader, String jsonBody) throws Exception {
        var rb = switch (method) {
            case "GET" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);
            case "PUT" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(jsonBody == null ? "{}" : jsonBody);
            default -> throw new IllegalArgumentException(method);
        };
        var r = mvc.perform(rb
                        .header("Authorization", "Bearer " + tokenFor("u-alpha"))
                        .header("X-Tenant-Id", "t-alpha")
                        .header("X-Project-Id", projectIdHeader))
                .andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------

    /** 读：拿自己项目的头，去读别人租户项目的主题域，必须拒绝。 */
    @Test
    void readingAnotherTenantsProjectsDomainsIsRejected() throws Exception {
        seedProjects();
        Resp res = asTenantA("GET", "/api/projects/" + projectB + "/domains", projectA);
        assertEquals(403, res.status(),
                "跨租户读被放行了（桩 org 已返回 allow，说明是本模块的归属校验漏了）: " + res.body());
    }

    /** 写：同理。授权（桩说 allow）通过了，但归属校验必须拦住。 */
    @Test
    void modifyingAnotherTenantsDomainIsRejected() throws Exception {
        seedProjects();
        Resp res = asTenantA("PUT", "/api/projects/" + projectB + "/domains/" + domainB, projectA,
                "{\"code\":\"hijacked\",\"name\":\"越权改名\"}");
        assertEquals(403, res.status(), "跨租户写被放行了: " + res.body());

        // 对方那一行必须原封不动
        assertEquals("beta_dom", domains.selectById(domainB).getCode(), "B 的数据被改动了");
    }

    /** 反例防线：换成别人的 id 当「请求体里的资源 id」，也不能改到别人的行。 */
    @Test
    void updatingForeignDomainIdUnderOwnProjectIsRejected() throws Exception {
        seedProjects();
        var rb = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/projects/" + projectA + "/domains/" + domainB)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"hijacked\",\"name\":\"越权改名\"}");
        var r = mvc.perform(rb
                        .header("Authorization", "Bearer " + tokenFor("u-alpha"))
                        .header("X-Tenant-Id", "t-alpha")
                        .header("X-Project-Id", projectA))
                .andReturn().getResponse();
        int status = r.getStatus();
        String body = r.getContentAsString(StandardCharsets.UTF_8);
        assertEquals(404, status, "用别人的资源 id 改到了别人的行: " + body);

        // 而对方那一行必须原封不动
        DomainEntity untouched = domains.selectById(domainB);
        assertEquals("beta_dom", untouched.getCode(), "B 的数据被改动了");
    }

    /** 未知租户编码：不得静默变成「没有租户」的请求。 */
    @Test
    void unknownTenantCodeIsRejectedNotSilentlyDropped() throws Exception {
        seedProjects();
        var r = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/projects/" + projectA + "/domains")
                        .header("Authorization", "Bearer " + tokenFor("u-alpha"))
                        .header("X-Tenant-Code", "not_synced_at_all")
                        .header("X-Project-Id", projectA))
                .andReturn().getResponse();
        int status = r.getStatus();
        assertEquals(403, status,
                "未知租户编码被静默忽略（HTTP " + status + "），租户归属校验会因此失去依据: "
                        + r.getContentAsString(StandardCharsets.UTF_8));
    }

    /** 正常路径不能被误伤：自己的项目、自己的租户照常可读。 */
    @Test
    void ownProjectStillWorks() throws Exception {
        seedProjects();
        Resp res = asTenantA("GET", "/api/projects/" + projectA + "/domains", projectA);
        assertEquals(200, res.status(), "自己的项目被误伤了: " + res.body());
    }
}
