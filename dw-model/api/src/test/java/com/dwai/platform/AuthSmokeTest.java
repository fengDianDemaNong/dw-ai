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
 * dw-model 的最小回归网之二：鉴权链路（登录 → 拿 token → 访问受保护接口）。
 *
 * <p>dw-model 的组织数据与 dw-org 同源，鉴权链路是同一条最敏感的路径 ——
 * token 签发、鉴权过滤器、租户上下文解析任一环断了整站都用不了。
 * 这里用 dev 模式与内置管理员登录，不依赖外部 IdP。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dwmodel_auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
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
    void protectedEndpointRejectsAnonymous() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().is4xxClientError());
    }
}
