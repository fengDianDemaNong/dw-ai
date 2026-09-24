package com.dwai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dw-org 的运行模式护栏：<b>组织平台只有 multi 一种模式</b>。
 *
 * <p>产品规格写得很直接（{@code docs/product/versions/0.2.0/PRD.md}）：
 * 「组织平台不做独立模式」。它是租户中心 —— 独立/普通两种模式的定义就是
 * <b>「不启组织平台」</b>，所以组织进程自身没有另外两态可切。
 *
 * <p>本类把这条钉死：即使把 {@code dwai.run-mode} 配成 {@code standalone} 或
 * {@code standard}，进程也必须仍然按 multi 运行、仍然提供登录。理由很实际 ——
 * 运维手上只有一份通用部署模板，很容易把模块的 {@code DW_AI_RUN_MODE=standalone}
 * 原样套到组织进程上。那种误配一旦生效，组织平台会关掉自己的登录口，
 * 结果是「整套系统没人能登录」。这里让它换不来。
 */
class RunModeSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 探针路径：无对应控制器，用来区分「路径放行」与「路径需鉴权」。 */
    private static final String PROBE = "/api/__runmode_probe__";

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    /** 组织进程的共性断言：无论怎么配，都必须是「product=org、runMode=multi、能登录」。 */
    private static void assertOrgIsAlwaysMulti(MockMvc mvc) throws Exception {
        JsonNode rt = json(mvc.perform(get("/api/runtime"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals("org", rt.path("product").asText(), "组织进程的 product 恒为 org");
        assertEquals("multi", rt.path("runMode").asText(),
                "组织平台不做独立模式：run-mode 配成别的也必须落到 multi");

        JsonNode cfg = json(mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertTrue(cfg.path("allowLogin").asBoolean(), "组织平台是身份唯一来源，登录口不能关");

        String token = json(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("token").asText();
        assertTrue(token != null && !token.isBlank(), "组织平台必须能签发令牌");

        // 签出来的令牌要能读回自己的身份，否则「能登录」是假的
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Nested
    @SpringBootTest(classes = DwaiApplication.class, properties = {
            "spring.datasource.url=jdbc:h2:mem:dworg_mode_default;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "dwai.security.mode=dev",
            "dwai.security.allow-dev-login=true"
    })
    @AutoConfigureMockMvc
    class DefaultConfig {

        @Autowired
        private MockMvc mvc;

        @Test
        void startsAsMultiAndCanLogIn() throws Exception {
            assertOrgIsAlwaysMulti(mvc);
        }

        /** multi 下业务接口必须鉴权：未登录拿不到任何组织数据。 */
        @Test
        void apiRequiresAuthentication() throws Exception {
            mvc.perform(get(PROBE)).andExpect(status().isUnauthorized());
        }
    }

    /**
     * 误配防护：有人把模块模板里的 {@code DW_AI_RUN_MODE=standalone} 抄到组织进程上。
     *
     * <p>此时进程仍须按 multi 运行 —— 否则登录口会被关掉，整套系统失联。
     */
    @Nested
    @SpringBootTest(classes = DwaiApplication.class, properties = {
            "spring.datasource.url=jdbc:h2:mem:dworg_mode_misconfig_standalone;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "dwai.run-mode=standalone",
            "dwai.security.mode=dev",
            "dwai.security.allow-dev-login=true"
    })
    @AutoConfigureMockMvc
    class MisconfiguredAsStandalone {

        @Autowired
        private MockMvc mvc;

        @Test
        void standaloneConfigIsIgnored() throws Exception {
            assertOrgIsAlwaysMulti(mvc);
        }
    }

    /** 同理：{@code standard} 也不是组织进程的合法模式。 */
    @Nested
    @SpringBootTest(classes = DwaiApplication.class, properties = {
            "spring.datasource.url=jdbc:h2:mem:dworg_mode_misconfig_standard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "dwai.run-mode=standard",
            "dwai.security.mode=dev",
            "dwai.security.allow-dev-login=true"
    })
    @AutoConfigureMockMvc
    class MisconfiguredAsStandard {

        @Autowired
        private MockMvc mvc;

        @Test
        void standardConfigIsIgnored() throws Exception {
            assertOrgIsAlwaysMulti(mvc);
        }
    }
}
