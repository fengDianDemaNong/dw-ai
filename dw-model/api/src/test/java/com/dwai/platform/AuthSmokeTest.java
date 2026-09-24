package com.dwai.platform;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * dw-model 的最小回归网之二：鉴权边界。
 *
 * <p>这里<b>不</b>断言「登录成功」——因为 dw-model 在 multi 模式下本就不提供登录口：
 * 组织身份由 dw-org 统一负责，dw-model 通过 {@code OrgClient} 与它交互
 * （见 {@code AuthController.login} 的 {@code isWarehouseOnly() && isMulti()} 判断）。
 *
 * <p>这个约束此前没有任何测试守着。一旦有人误删那个判断，登录口会在生产上悄悄打开 ——
 * 本测试就是为它上的锁。
 */
@SpringBootTest(classes = DwaiApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:dwmodel_auth;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "dwai.security.mode=dev",
        "dwai.security.allow-dev-login=true",
        // 必须显式钉住 multi：application.yml 里 run-mode 的默认值已是 standard，
        // 不写这行，跑的就是 standard —— 类名里的 multi 形同虚设。403 由
        // AuthController「warehouse 进程 + multi 不提供登录」给出，那条分支
        // 只在 multi 下成立。早前这行缺失，测试是靠 AuthService 里一句
        // 「standard 不接受平台账号」侥幸变绿的，那句已随本次口径调整删除。
        "dwai.run-mode=multi",
        "dwai.bootstrap.admin-username=admin",
        "dwai.bootstrap.admin-password=123456"
})
@AutoConfigureMockMvc
class AuthSmokeTest {

    @Autowired
    private MockMvc mvc;

    /**
     * 设计如此：warehouse 进程 + multi 模式下，登录必须被拒绝。
     *
     * <p>如果这条测试失败（变成 200），说明登录口被打开了 —— 那是安全边界的破坏，
     * 不是「功能增强」。
     */
    @Test
    void loginIsRefusedInWarehouseMultiMode() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().is4xxClientError());
    }
}
