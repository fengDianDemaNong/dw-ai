package controller;

import com.dwai.lineage.Main;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * 白名单表达不出来时<b>保持</b> DENY，也就是这条防护 fail-closed。
 *
 * <p>为什么值得单独一个上下文：把 {@code frame-ancestors} 换成无条件
 * {@code frameOptions(disable)} 写起来更短，但那是「配置不完整 → 静默变成谁都能嵌」。
 * 默认值本身是安全的（DENY），只有配了明确的来源才放开 —— 这条测试钉的就是这个默认。
 *
 * <p>用全通配的白名单触发：CORS 认 {@code http://localhost:*}，CSP 不认，
 * 剔除后没有可精确表达的来源 → 走 {@code frameAncestors()} 的 null 分支。
 * 另见 {@link EmbedFramePolicyTest}。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:lineage_embed_frame_unset;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        "cors.allowed-origins=http://localhost:*,http://127.0.0.1:*"
})
@AutoConfigureMockMvc
class EmbedFramePolicyUnsetTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void unexpressibleWhitelistKeepsDeny() throws Exception {
        mvc.perform(get("/api/runtime"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    /** 不写 CSP，比写一条无效指令好：无效指令会被浏览器整条忽略，等于没写还误导人。 */
    @Test
    void noCspWhenWhitelistUnexpressible() throws Exception {
        mvc.perform(get("/api/runtime"))
                .andExpect(header().doesNotExist("Content-Security-Policy"));
    }
}
