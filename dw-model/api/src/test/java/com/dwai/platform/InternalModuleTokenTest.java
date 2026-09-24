package com.dwai.platform;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 未配置模块令牌时，{@code /internal/v1/**} 必须<b>拒绝</b>而不是放行。
 *
 * <p>本模块这两个入口（PUT / DELETE 项目镜像）是组织 fan-out 的落点。
 * 它们的门禁只有 {@code assertModule} —— SecurityConfig 里 {@code /internal/v1/**}
 * 是 {@code permitAll}（它不能要求用户 JWT，心跳发生在启动时还没有用户登录）。
 *
 * <p>此前 {@code assertModule} 在密钥为空时「跳过校验」，而默认配置里
 * {@code dwai.security.module-token} 就是空的 —— 于是默认部署下这两个写接口
 * <b>任何人都能调</b>。这个漏洞不会报错、不会告警，只有断言能抓住它。
 *
 * <p>注意与 {@code InternalContractTest} 的分工：那个类用<b>配好</b>的密钥验证
 * 「对得上才放行」，本类验证「没配就一律不放行」。两者合起来才是完整契约。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dwmodel_module_token_absent;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.run-mode=multi"
        // dwai.security.module-token 刻意不配 —— 这正是默认部署的形态
})
@AutoConfigureMockMvc
class InternalModuleTokenTest {

    private static final String BODY = "{\"tenantCode\":\"acme\",\"name\":\"越权项目\"}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ProjectMapper projects;

    /** 写镜像：没配密钥就谁都不许写。 */
    @Test
    void projectMirrorIsClosedWhenTokenIsNotConfigured() throws Exception {
        mvc.perform(put("/internal/v1/projects/hijack_me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    /** 删镜像：同样不许。 */
    @Test
    void projectMirrorDeleteIsClosedWhenTokenIsNotConfigured() throws Exception {
        mvc.perform(delete("/internal/v1/projects/hijack_me")
                        .header("X-Tenant-Code", "acme"))
                .andExpect(status().isUnauthorized());
    }

    /** 空串头不算「提供了令牌」。 */
    @Test
    void blankTokenHeaderIsRejected() throws Exception {
        mvc.perform(put("/internal/v1/projects/hijack_me")
                        .header("X-Module-Token", "")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());
    }

    /** 401 之前的副作用等于没拦住：被拒的请求不能留下项目。 */
    @Test
    void rejectedCallLeavesNoProject() throws Exception {
        mvc.perform(put("/internal/v1/projects/never_landed")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnauthorized());

        ProjectEntity row = projects.selectOne(
                Wrappers.<ProjectEntity>lambdaQuery().eq(ProjectEntity::getCode, "never_landed"));
        assertNull(row, "被 401 拒绝的请求还是把项目写进去了");
    }

    /** 报错要能照着修：信息里必须出现要设的属性名。 */
    @Test
    void failureMessageTellsYouWhatToConfigure() throws Exception {
        String body = mvc.perform(put("/internal/v1/projects/hijack_me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("module-token"),
                "错误信息没说要配哪个属性，运维只能猜。实际: " + body);
    }
}
