package com.dwai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓建设作为<b>接收方</b>的对外契约：{@code /internal/v1/projects/{project_code}} 的入站校验。
 *
 * <p>技术方案 §3.5 规定组织创建/删除项目时，用同一个 {@code project_code} 往模块里落镜像。
 * 这条链路此前没有任何测试 —— 它坏掉的表现是「组织里建了项目，仓建设里看不见」，
 * 而且只在 multi 组合下才出现，单跑仓建设永远正常。
 *
 * <p>两个入口都用本地 admin 的 {@code /api/platform/tenants} 无关，纯走模块令牌，
 * 因此这里断言的重点是：<b>没有模块令牌进不来</b>、以及<b>没有 tenantCode 不许落项目</b>。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dwmodel_internal_contract;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.run-mode=multi",
        "dwai.security.mode=dev",
        "dwai.security.module-token=contract-module-token"
})
@AutoConfigureMockMvc
class InternalContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String MODULE_TOKEN = "contract-module-token";

    @Autowired
    private MockMvc mvc;

    /** 没有模块令牌：组织之外的任何人都不该能往本模块塞项目。 */
    @Test
    void projectMirrorRequiresModuleToken() throws Exception {
        Resp res = put("mirror_denied", "{\"tenantCode\":\"acme\",\"name\":\"越权项目\"}", null);
        assertEquals(401, res.status(), "无令牌竟然能落项目: " + res.body());
    }

    /** 带了令牌但没说属于哪个租户：必须拒绝，不能落到某个默认租户下。 */
    @Test
    void projectMirrorRequiresTenantCode() throws Exception {
        Resp res = put("mirror_no_tenant", "{\"name\":\"无主项目\"}", MODULE_TOKEN);
        assertEquals(400, res.status(), "没给 tenantCode 也落进项目了: " + res.body());
    }

    /**
     * 正常落镜像：组织的 {@code project_code} 原样落到本模块，且带上租户归属。
     *
     * <p>断言的字段刻意与组织侧对齐：跨服务契约是那个 <b>code</b>，
     * 不是任何一边的主键 —— 所以这里只认 code 与租户 code，不碰 id 语义。
     */
    @Test
    void projectMirrorLandsWithCodeAndTenant() throws Exception {
        Resp res = put("trade_dw", "{\"tenantCode\":\"xinghe\",\"tenantName\":\"星河电商\",\"name\":\"交易数仓\"}",
                MODULE_TOKEN);
        assertEquals(200, res.status(), "落镜像失败: " + res.body());

        JsonNode p = MAPPER.readTree(res.body());
        assertEquals("trade_dw", p.path("code").asText(), "项目 code 必须原样保留: " + res.body());
        assertNotEquals("trade_dw", p.path("id").asText(),
                "本模块的 id 与跨服务的 code 应当是两个东西");
        assertTrue(p.path("name").asText().contains("交易数仓"), "名称应被采纳: " + res.body());
    }

    /** 同一个 code 重复落镜像必须幂等 —— 心跳每 120 秒会重放一次。 */
    @Test
    void projectMirrorIsIdempotent() throws Exception {
        String body = "{\"tenantCode\":\"xinghe\",\"tenantName\":\"星河电商\",\"name\":\"交易数仓\"}";
        String first = put("trade_dw", body, MODULE_TOKEN).body();
        Resp again = put("trade_dw", body, MODULE_TOKEN);
        assertEquals(200, again.status(), "重复落镜像应幂等: " + again.body());
        assertEquals(MAPPER.readTree(first).path("id").asText(),
                MAPPER.readTree(again.body()).path("id").asText(),
                "重复落镜像不该换一个 id");
    }

    // ------------------------------------------------------------------

    private record Resp(int status, String body) {
    }

    private Resp put(String projectCode, String json, String moduleToken) throws Exception {
        var rb = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/internal/v1/projects/" + projectCode)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json);
        if (moduleToken != null) rb = rb.header("X-Module-Token", moduleToken);
        var r = mvc.perform(rb).andReturn().getResponse();
        return new Resp(r.getStatus(), r.getContentAsString(StandardCharsets.UTF_8));
    }
}
