package com.dwai.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dw-org 的最小回归网之二：鉴权链路（登录 → 拿 token → 访问受保护接口）。
 *
 * <p>这是「租户管理」最容易出事、也最难靠人工点出来的链路 ——
 * token 签发、鉴权过滤器、租户上下文解析，任一环断了整站都用不了。
 *
 * <p>用 dev 模式（{@code dwai.security.mode=dev}）与内置管理员登录，
 * 不依赖任何外部 IdP。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=admin123"
})
@AutoConfigureMockMvc
class AuthSmokeTest {

    @Autowired
    private MockMvc mvc;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void adminCanLoginAndReadOwnProfile() throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode res = json.readTree(body);
        String token = res.path("token").asText();
        assertTrue(token != null && !token.isBlank(),
                "登录应返回 token，实际响应: " + body);

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void wrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"definitely-wrong\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().is4xxClientError());
    }
}
