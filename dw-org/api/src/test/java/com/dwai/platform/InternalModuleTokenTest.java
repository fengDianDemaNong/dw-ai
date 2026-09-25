package com.dwai.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未配置模块令牌时，{@code /internal/v1/**} 必须<b>拒绝</b>而不是放行。
 *
 * <p>组织平台的 {@code assertInternal} 此前是这样写的：密钥为空时跳过校验。
 * 而 {@code /internal/v1/**} 在 SecurityConfig 里是 {@code permitAll}（它不能要求
 * 用户 JWT —— 心跳发生在启动时，此时还没有任何用户登录）。两件事叠加的结果是：
 * 默认配置（{@code module-token} 为空）下，{@code /internal/v1/**} 就是公开写接口。
 *
 * <p>「默认配置 = 最开放的接口」是最糟的默认值，因为没人会去改一个看起来已经能用的东西。
 * 现在未配置的表现是 401，并且错误信息里写清楚要设哪个属性。
 *
 * <p>组织平台没有独立模式（{@code product=org} 时 {@code runMode()} 恒为 multi），
 * 所以这里的豁免分支走不到 —— 组织永远要求令牌，这是刻意的：它是身份的唯一来源。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dworg_module_token_absent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password="
        // dwai.security.module-token 刻意不配 —— 这正是默认部署的形态
})
@AutoConfigureMockMvc
class InternalModuleTokenTest {

    @Autowired
    private MockMvc mvc;

    /** 读接口：没配密钥就没人能读组织上下文。 */
    @Test
    void readEndpointIsClosedWhenTokenIsNotConfigured() throws Exception {
        mvc.perform(get("/internal/v1/context")).andExpect(status().isUnauthorized());
    }

    /** 写接口比读更要紧：代用户续期会轮换 refresh token。 */
    @Test
    void writeEndpointIsClosedWhenTokenIsNotConfigured() throws Exception {
        mvc.perform(post("/internal/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"whatever\"}"))
                .andExpect(status().isUnauthorized());
    }

    /** 带一个空串头不算「提供了令牌」。 */
    @Test
    void blankTokenHeaderIsRejected() throws Exception {
        mvc.perform(get("/internal/v1/context").header("X-Module-Token", ""))
                .andExpect(status().isUnauthorized());
    }

    /** 报错要能照着修：信息里必须出现要设的属性名。 */
    @Test
    void failureMessageTellsYouWhatToConfigure() throws Exception {
        String body = mvc.perform(get("/internal/v1/context"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("module-token"),
                "错误信息没说要配哪个属性，运维只能猜。实际: " + body);
    }
}
