package controller;

import com.dwai.lineage.Main;
import com.dwai.lineage.persistence.TenantAdminRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 服务间接口（{@code /internal/v1/**}）的门禁：静态共享密钥。
 *
 * <p>数据地图在 multi 组合下是<b>被组织 fan-out 的一端</b>：组织建了项目会 PUT 过来，
 * 删了项目会 DELETE 过来。这两个入口不在 SecurityConfig 的鉴权范围内（它们不是
 * {@code /api/**}），唯一的门禁就是 {@code assertModule}。
 *
 * <p>本类钉住两件事：
 *
 * <ol>
 *   <li><b>配了密钥就必须对得上</b> —— 漏带 / 带错 / 带空串都不行；</li>
 *   <li><b>没配密钥时必须拒绝</b>，而不是「跳过校验」。</li>
 * </ol>
 *
 * <p>第二条是这次的修复重点。此前 {@code assertModule} 在密钥为空时直接跳过校验，
 * 而默认配置里 {@code lineage.module-token} 恰恰就是空的 —— 于是「忘了配」
 * 等于「谁都能往库里塞项目镜像」。这类漏洞不会报错、不会告警，只有断言能抓住它。
 */
class InternalModuleTokenTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "lineage-contract-token";
    private static final String TENANT_CODE = "acme";
    private static final String PROJECT_ID = "p_checkout";
    private static final String BODY =
            "{\"tenantCode\":\"" + TENANT_CODE + "\",\"name\":\"结算域项目\",\"code\":\"p-checkout\"}";

    private static String bodyOf(org.springframework.test.web.servlet.ResultActions actions)
            throws Exception {
        return actions.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 配了密钥：对的进得来，错的进不来。 */
    @Nested
    @SpringBootTest(classes = Main.class, properties = {
            "database.type=h2",
            "database.url=jdbc:h2:mem:lineage_internal_token_on;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "database.username=sa",
            "database.password=",
            "lineage.run-mode=multi",
            "lineage.org-base-url=",
            "lineage.module-token=" + TOKEN
    })
    @AutoConfigureMockMvc
    class Configured {

        @Autowired
        private MockMvc mvc;

        @Autowired
        private TenantAdminRepository repository;

        /** 正常链路：带对令牌的 fan-out 必须落库，不能只回 200。 */
        @Test
        void acceptsFanOutWithCorrectToken() throws Exception {
            JsonNode res = MAPPER.readTree(bodyOf(mvc.perform(put("/internal/v1/projects/" + PROJECT_ID)
                            .header("X-Module-Token", TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isOk())));
            assertEquals("结算域项目", res.path("name").asText());

            // 回 200 不等于落了库 —— 断言真的写进去了
            long tenantId = repository.findTenantByCode(TENANT_CODE).orElseThrow().id();
            Optional<?> project = repository.findProjectByCode(tenantId, PROJECT_ID);
            assertTrue(project.isPresent(),
                    "带对令牌的 fan-out 没落库，tenant=" + TENANT_CODE + " project=" + PROJECT_ID);
        }

        @Test
        void rejectsMissingToken() throws Exception {
            mvc.perform(put("/internal/v1/projects/" + PROJECT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void rejectsWrongToken() throws Exception {
            mvc.perform(put("/internal/v1/projects/" + PROJECT_ID)
                            .header("X-Module-Token", "not-the-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        /** 空串不能算「带对了」。 */
        @Test
        void rejectsBlankToken() throws Exception {
            mvc.perform(put("/internal/v1/projects/" + PROJECT_ID)
                            .header("X-Module-Token", "")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        /**
         * 被拒的请求不能留下任何痕迹。
         *
         * <p>用一个**独立的租户编码**来断言，而不是复用 {@link #TENANT_CODE}：
         * 一来不受用例执行顺序影响，二来断言更强 —— {@code upsertFromOrg} 会在租户
         * 不存在时顺手建一个，所以「项目没落」还不够，得确认**连租户都没建**。
         */
        @Test
        void rejectedCallLeavesNoTrace() throws Exception {
            mvc.perform(put("/internal/v1/projects/never_landed")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tenantCode\":\"ghost\",\"name\":\"不该存在\"}"))
                    .andExpect(status().isUnauthorized());

            assertTrue(repository.findTenantByCode("ghost").isEmpty(),
                    "被 401 拒绝的请求还是把租户建出来了");
        }
    }

    /** 没配密钥：一律拒绝，并且报错要能照着修。 */
    @Nested
    @SpringBootTest(classes = Main.class, properties = {
            "database.type=h2",
            "database.url=jdbc:h2:mem:lineage_internal_token_off;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "database.username=sa",
            "database.password=",
            "lineage.run-mode=multi",
            "lineage.org-base-url="
            // lineage.module-token 刻意不配 —— 这正是默认部署的形态
    })
    @AutoConfigureMockMvc
    class NotConfigured {

        @Autowired
        private MockMvc mvc;

        @Test
        void refusesFanOutWhenTokenIsNotConfigured() throws Exception {
            mvc.perform(put("/internal/v1/projects/" + PROJECT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(BODY))
                    .andExpect(status().isUnauthorized());
        }

        /** 报错要能照着修：信息里必须出现要设的属性名。 */
        @Test
        void failureMessageTellsYouWhatToConfigure() throws Exception {
            String body = bodyOf(mvc.perform(put("/internal/v1/projects/" + PROJECT_ID)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)));
            assertTrue(body.contains("module-token"),
                    "错误信息没说要配哪个属性，运维只能猜。实际: " + body);
        }
    }
}
