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
 * 本服务的页面能不能被门户 iframe 嵌进来的那条 HTTP 契约。
 *
 * <p>钉的是一次<b>只有打包态才显形</b>的缺陷：Spring Security 默认给每个响应加
 * {@code X-Frame-Options: DENY}，于是门户里的 iframe 一片白。开发态完全看不出来 ——
 * 页面是 Vite 发的，根本不经过后端的响应头。（真机上就是这么发现的：
 * {@code curl -I} 打打包进程才看到 DENY。）
 *
 * <p>换成 CSP 的 {@code frame-ancestors} 而不是简单地 disable：后者等于「任何人都能嵌」，
 * 把一个点击劫持的防护整块丢掉。白名单直接复用 {@code cors.allowed-origins} ——
 * 那是同一份「允许谁」的清单，另开一个属性迟早写歪。
 *
 * <p>另见 {@link EmbedFramePolicyUnsetTest}：白名单表达不出来时<b>保持</b> DENY。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:lineage_embed_frame;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        // 第二条是通配写法：CSP 表达不了，必须被剔除（原样写进去是非法指令，
        // 浏览器会整条忽略 —— 那就成了「配了白名单反而谁都能嵌」）
        "cors.allowed-origins=http://portal.example.com,http://localhost:*"
})
@AutoConfigureMockMvc
class EmbedFramePolicyTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void allowedOriginsBecomeFrameAncestors() throws Exception {
        mvc.perform(get("/api/runtime"))
                .andExpect(header().string(
                        "Content-Security-Policy", "frame-ancestors http://portal.example.com"));
    }

    /** DENY 必须消失，否则不支持 CSP 的浏览器仍按它拒掉 iframe。 */
    @Test
    void defaultDenyHeaderIsGone() throws Exception {
        mvc.perform(get("/api/runtime"))
                .andExpect(header().doesNotExist("X-Frame-Options"));
    }
}
