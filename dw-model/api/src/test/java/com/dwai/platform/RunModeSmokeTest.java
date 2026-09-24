package com.dwai.platform;

import com.dwai.platform.auth.JwtIssuer;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三模式冒烟：dw-model 在 <b>standalone / standard / multi</b> 三种启动模式下都必须能起来，
 * 且关键行为符合设计（见 {@code docs/tech/07-0.2.0.md} §3.3、§4）。
 *
 * <p>这是「服务可以拿出来单独使用」这条产品承诺<b>唯一可自动验证的方式</b>：
 * 三种模式各起一次上下文，确认没有哪条装配路径只在某一模式下成立。
 * 三种模式的差别是<b>刻意的</b>，不是可以互相兼容的：
 *
 * <table border="1">
 *   <tr><th>模式</th><th>登录口</th><th>{@code /api/**}</th><th>账号来源</th></tr>
 *   <tr><td>standalone</td><td>不提供（403）</td><td>开放</td><td>本机固定主体（种子里那行）</td></tr>
 *   <tr><td>standard</td><td>本地登录</td><td>需鉴权</td><td>本库账号</td></tr>
 *   <tr><td>multi</td><td>不提供（403）</td><td>需鉴权</td><td>组织平台签发</td></tr>
 * </table>
 *
 * <p>因此本类断言的是「差异」，不是「一致」——把它们抹平才是破坏设计。
 */
class RunModeSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 探针路径：确定不存在对应控制器，用来区分「路径放行」与「路径需鉴权」。 */
    private static final String PROBE = "/api/__runmode_probe__";

    private static JsonNode json(String body) throws Exception {
        return MAPPER.readTree(body);
    }

    // ------------------------------------------------------------------
    // standalone：独立部署、没有身份来源，接口全开放、不提供登录
    // ------------------------------------------------------------------

    @Nested
    @SpringBootTest(classes = DwaiApplication.class, properties = {
            "spring.datasource.url=jdbc:h2:mem:dwmodel_mode_standalone;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "dwai.run-mode=standalone",
            "dwai.security.mode=dev"
    })
    @AutoConfigureMockMvc
    class Standalone {

        @Autowired
        private MockMvc mvc;

        @Test
        void startsAndReportsStandalone() throws Exception {
            JsonNode rt = json(mvc.perform(get("/api/runtime"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("warehouse", rt.path("product").asText());
            assertEquals("standalone", rt.path("runMode").asText());
        }

        @Test
        void loginIsRefused() throws Exception {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                    .andExpect(status().isForbidden());
        }

        /** 独立部署没有人来签发 token，所以业务接口必须开放。 */
        @Test
        void apiIsOpenWithoutCredentials() throws Exception {
            mvc.perform(get(PROBE)).andExpect(status().isNotFound());
        }

        @Test
        void configTellsFrontendNotToShowLogin() throws Exception {
            JsonNode cfg = json(mvc.perform(get("/api/auth/config"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("standalone", cfg.path("runMode").asText());
            assertFalse(cfg.path("allowLogin").asBoolean(), "独立模式不该出现登录入口");
        }

        /**
         * 续期在独立模式下明拒（403）。
         *
         * <p>{@code /api/auth/refresh} 在 {@code SecurityConfig} 里是放行的，所以这个请求
         * 会真的走进 controller，由 {@code AuthController.refresh} 里的 standalone 判断挡下。
         * 之所以要显式挡：独立模式没有登录，也就没有 refresh token 可言 ——
         * 不挡的话它落到 {@code RefreshTokenService.rotate(null)}，表现是 401 还是 500
         * 全看那边的实现细节，而这两种回答都在暗示「这里本来可以续期」。
         */
        @Test
        void refreshIsRefused() throws Exception {
            mvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"whatever\"}"))
                    .andExpect(status().isForbidden());
        }

        /**
         * 登出在独立模式下<b>照收</b>（200），与 login / refresh 的 403 不一致 —— 这是刻意的。
         *
         * <p>{@code AuthController.logout} 没有 standalone 判断，直接走
         * {@code AuthService.logout} 去撤销一个不存在的 refresh token，等于空操作。
         * 保留它是为了幂等：前端在退出登录时可以无脑调一次，不必先判断运行模式；
         * 而 login / refresh 反过来——它们会<b>产生新的身份凭据</b>，所以在独立模式下必须拒绝。
         * 「不签发」要挡，「不签发地撤销」不必挡。
         *
         * <p>这条是<b>把现状钉住</b>，不是宣称它正确：若哪天有人给 logout 加上 403，
         * 这条会红，正好逼一次讨论（要不要为了一致性牺牲幂等），而不是悄悄漂移。
         */
        @Test
        void logoutIsAcceptedAsANoOp() throws Exception {
            mvc.perform(post("/api/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"whatever\"}"))
                    .andExpect(status().isOk());
        }

        /**
         * {@code /api/auth/me} 在独立模式下返回一个合成的 {@code remote} 用户，并指明落地页。
         *
         * <p>这与数据地图（dw-lineage）同一接口的选择相反：那边 {@code /api/auth/me} 给 401，
         * 因为它的前端在独立模式下不渲染用户；这里必须给 200 —— 仓库端的页面依赖
         * {@code me} 拿到 {@code landing} 才知道把人送到哪个路由（见 {@code AuthService.landing}）。
         * 两种选择都成立，但都别改成对方的样子。
         *
         * <p>运行模式在 {@code me} 里叫 {@code deployMode}（{@code ApiModels.Me} 没有
         * {@code runMode} 字段，见 {@code AuthService.toMe} 传的是 {@code props.runMode()}）。
         * 别去 JSON 里找 {@code runMode} —— 它只在 {@code /api/runtime} 与 {@code /api/auth/config} 上。
         *
         * <p>而 {@code tenantRole} 必须出现在 JSON 里：前端按它决定「新增项目」「编辑」
         * 这些入口渲不渲染。它为 {@code null} 时会被 {@code default-property-inclusion: non_null}
         * 整个省略 —— 那正是「独立模式只能看、不能动」的样子：接口不报错，按钮不出现。
         */
        @Test
        void meReportsTheLocalUserAsTenantAdmin() throws Exception {
            JsonNode me = json(mvc.perform(get("/api/auth/me"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("standalone", me.path("deployMode").asText());
            assertEquals("/model", me.path("landing").asText(),
                    "独立模式没有项目可选，落地页就是仓库首页");
            assertFalse(me.path("needSelectTenant").asBoolean(), "独立模式没有组织可切");
            assertEquals("admin", me.path("tenantRole").asText(),
                    "独立模式的本地主体必须是租户管理员 —— 前端按这个字段决定「新增项目」等入口渲不渲染");
            assertNotEquals("访客", me.path("displayName").asText(),
                    "独立模式不是访客：它没有登录这一层，但拿到的是完整的管理员能力");
        }

        /**
         * 独立模式能**真的建出项目**来 —— 钉住外键 {@code fk_pm_user}。
         *
         * <p>这条覆盖一个真实发生过的 500：{@code TenantFilter} 把上下文里的 userId 定成
         * {@code standalone}，而 {@code users} 表里没有这一行，于是 {@code createProject}
         * 落 {@code project_members} 时外键违约。权限检查**全过了**（那一层用的是内存假用户），
         * 所以表现是「点新建就报错」，而不是「没有权限」—— 光看鉴权代码永远找不到它。
         *
         * <p>断言状态码 200 而不是「项目建出来了」：外键违约会以 500 冒出来，
         * 200 就说明那行本地用户确实在库里。
         */
        @Test
        void projectCanBeCreated() throws Exception {
            mvc.perform(post("/api/projects")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"probe_p1\",\"name\":\"探针项目\"}"))
                    .andExpect(status().isOk());
        }

        /**
         * multi 专属入口在独立模式下 403，而不是 404 或 500。
         *
         * <p>{@code POST /api/auth/enter-tenant} 的第一个动作就是 {@code AccessService.requireMulti()}，
         * 所以三种非 multi 的部署都会明确回答「当前运行模式没有此功能」。
         * 误删那道判断的后果不是「少个功能」，而是单机部署上多出一个<b>点不进也报不出原因</b>的入口。
         */
        @Test
        void multiOnlyEndpointIsForbidden() throws Exception {
            mvc.perform(post("/api/auth/enter-tenant")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tenantId\":\"t-xinghe\",\"code\":\"abc\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    // ------------------------------------------------------------------
    // standard：独立部署但自管账号，本地登录
    // ------------------------------------------------------------------

    @Nested
    @SpringBootTest(classes = DwaiApplication.class, properties = {
            "spring.datasource.url=jdbc:h2:mem:dwmodel_mode_standard;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "dwai.run-mode=standard",
            "dwai.security.mode=dev",
            "dwai.security.allow-dev-login=true"
    })
    @AutoConfigureMockMvc
    class Standard {

        @Autowired
        private MockMvc mvc;

        @Test
        void startsAndReportsStandard() throws Exception {
            JsonNode rt = json(mvc.perform(get("/api/runtime"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("standard", rt.path("runMode").asText());
            assertEquals("warehouse", rt.path("product").asText());
        }

        /**
         * 「独立拿出来用」的核心：空库必须自带一份可登录的本地账号。
         *
         * <p>账号由 {@code BootstrapAdminRunner} 在 standard 模式下创建：用户名 {@code admin}，
         * 密码取 {@code dwai.bootstrap.admin-password}（默认 {@code 123456}）。
         * 这条一旦断了，独立部署就是「进不去的系统」。
         *
         * <p>早前这条测的是 {@code 张三/123456}，注释还写着「账号由 WarehouseLocalSeedRunner
         * 在 standard 模式下灌入」—— 那段代码写在一个 standalone 判断之后，两个条件互斥，
         * 从来没执行过，所以这条一直是红的。现在 standard 走 {@code admin}：平台账号在
         * 普通模式也能登录，见 {@code AuthService.login}。
         */
        @Test
        void localAdminCanLogIn() throws Exception {
            String token = loginAsAdmin();

            // 拿到的令牌必须真能用，否则「独立可用」只是看起来成立
            mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        @Test
        void wrongPasswordIsRejected() throws Exception {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                    .andExpect(status().is4xxClientError());
        }

        @Test
        void apiRequiresAuthentication() throws Exception {
            mvc.perform(get(PROBE)).andExpect(status().isUnauthorized());
        }

        @Test
        void configAllowsLogin() throws Exception {
            JsonNode cfg = json(mvc.perform(get("/api/auth/config"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertTrue(cfg.path("allowLogin").asBoolean(), "standard 模式必须提供本地登录");
        }

        /**
         * 续期在 standard 下走<b>本地</b>刷新令牌，认不出的令牌给 401 而不是 403 / 500。
         *
         * <p>与 standalone 的 403 是一对：那边是「这个模式没有续期这回事」，
         * 这里是「有这回事，但这个令牌无效」——排查方向完全不同。
         */
        @Test
        void refreshRejectsUnknownTokenLocally() throws Exception {
            mvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"not-a-token\"}"))
                    .andExpect(status().isUnauthorized());
        }

        /** 本地登出照收（无状态 JWT 撤的是 refresh），前端可无脑调。 */
        @Test
        void logoutIsAccepted() throws Exception {
            mvc.perform(post("/api/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"not-a-token\"}"))
                    .andExpect(status().isOk());
        }

        /**
         * multi 专属入口在 standard 下也是 403 —— 而且要<b>带着合法令牌</b>去验。
         *
         * <p>不带令牌时先被 {@code SecurityConfig} 挡成 401（{@code /api/auth/enter-tenant}
         * 不在放行名单里），那就测不到 {@code requireMulti()} 那一层了。
         * 这里用管理员令牌，是为了排掉「权限不够」这个解释：403 的原因只能是运行模式。
         */
        @Test
        void multiOnlyEndpointIsForbiddenEvenForAdmin() throws Exception {
            mvc.perform(post("/api/auth/enter-tenant")
                            .header("Authorization", "Bearer " + loginAsAdmin())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"tenantId\":\"t-xinghe\",\"code\":\"abc\"}"))
                    .andExpect(status().isForbidden());
        }

        /**
         * 本地账号的落地页在 {@code /model} 下，且 {@code deployMode} 如实回报 standard。
         *
         * <p>{@code landing} 是前后端之间最硬的一条契约：前端只认这几个字符串去路由，
         * 改了这里前端就白屏。空库（无项目）时落在 {@code /model/no-project}，
         * 由前端引导建项目 —— 这条同时钉住了「standard 不会把用户送去组织平台那套页面」。
         */
        @Test
        void mePointsAtModelLanding() throws Exception {
            JsonNode me = json(mvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + loginAsAdmin()))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("standard", me.path("deployMode").asText());
            assertTrue(me.path("landing").asText().startsWith("/model"),
                    "standard 的落地页必须在仓库端，实际: " + me.path("landing").asText());
        }

        /** 登录一次，返回可用的 access 令牌。 */
        private String loginAsAdmin() throws Exception {
            JsonNode res = json(mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            String token = res.path("token").asText();
            assertTrue(token != null && !token.isBlank(), "登录成功必须返回令牌，实际: " + res);
            return token;
        }
    }

    // ------------------------------------------------------------------
    // multi：与组织平台整合，身份由 dw-org 统一负责，本进程不提供登录
    // ------------------------------------------------------------------

    @Nested
    @SpringBootTest(classes = DwaiApplication.class, properties = {
            "spring.datasource.url=jdbc:h2:mem:dwmodel_mode_multi;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "dwai.run-mode=multi",
            "dwai.security.mode=dev"
    })
    @AutoConfigureMockMvc
    class Multi {

        @Autowired
        private MockMvc mvc;

        @Autowired
        private JwtIssuer issuer;

        @Test
        void startsAndReportsMulti() throws Exception {
            JsonNode rt = json(mvc.perform(get("/api/runtime"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("multi", rt.path("runMode").asText());
            assertEquals("warehouse", rt.path("product").asText());
        }

        /**
         * 设计如此：warehouse 进程 + multi 模式下登录必须被拒绝。
         *
         * <p>身份由组织平台签发，本进程若也开登录口，就出现了第二条身份来源 ——
         * 那正是「不共库、不直打兄弟 /api」要防的事。
         */
        @Test
        void loginIsRefused() throws Exception {
            mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void apiRequiresAuthentication() throws Exception {
            mvc.perform(get(PROBE)).andExpect(status().isUnauthorized());
        }

        @Test
        void configTellsFrontendNotToShowLogin() throws Exception {
            JsonNode cfg = json(mvc.perform(get("/api/auth/config"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertFalse(cfg.path("allowLogin").asBoolean(), "multi 模式的登录由组织平台负责");
        }

        /** 未登录不能顺着 multi 模式拿到任何业务数据。 */
        @Test
        void protectedEndpointRejectsAnonymous() throws Exception {
            mvc.perform(get("/api/auth/me")).andExpect(status().is4xxClientError());
        }

        /**
         * 续期在 multi 下<b>转发给组织平台</b>；组织不在时明确 503，不是 200 也不是 500。
         *
         * <p>本类没有配 {@code dwai.org-base-url}，所以 {@code OrgClient.refresh} 走到
         * 「基址未配置」分支。这里要守的是它<b>说出真相</b>：503（依赖不可用）让运维去查组织平台，
         * 而 200 会骗所有人「续期成功了」——那才是真正的故障形态（前端拿着空令牌继续跑）。
         */
        @Test
        void refreshIsForwardedToOrgAndFailsLoudlyWhenOrgIsAbsent() throws Exception {
            mvc.perform(post("/api/auth/refresh")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"whatever\"}"))
                    .andExpect(status().isServiceUnavailable());
        }

        /**
         * 登出在组织不可达时仍然 200 —— 与上面的续期相反，这个「成功」是刻意的。
         *
         * <p>{@code OrgClient.revokeRefresh} 在组织基址为空时静默返回：撤销一个刷新令牌
         * 属于尽力而为，组织不在并不代表「用户没登出」。若这里改成 503，
         * 前端会把「退出登录」按钮变成报错，而用户的意图（清掉本地令牌离开）其实已经达成了。
         */
        @Test
        void logoutStaysOkWhenOrgIsAbsent() throws Exception {
            mvc.perform(post("/api/auth/logout")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"whatever\"}"))
                    .andExpect(status().isOk());
        }

        /**
         * 拿着组织签发的令牌但还没选组织时，落地页是 {@code /org/select-tenant}。
         *
         * <p>这是 multi 下最容易白屏的一条路径：令牌有效、但没有租户上下文，
         * 前端必须被明确告知「去选组织」，而不是被丢到某个需要租户的页面上去空转。
         * 本类库里没有租户镜像，所以 {@code listTenantsFor} 返回空列表 → 需要选择。
         *
         * <p>令牌用 {@code JwtIssuer} 直接签，与组织平台签发的形态一致
         * （免掉一个 org 桩；本类关心的是本进程怎么解读令牌，不是组织怎么签）。
         */
        @Test
        void meWithoutTenantAsksForTenantSelection() throws Exception {
            String token = issuer.issue("u-nobody", "u-nobody", "无租户用户", false);
            JsonNode me = json(mvc.perform(get("/api/auth/me")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("multi", me.path("deployMode").asText());
            assertEquals("/org/select-tenant", me.path("landing").asText(),
                    "没有组织的用户被丢到了别处，前端会白屏");
            assertTrue(me.path("needSelectTenant").asBoolean(),
                    "落地页要求选组织，却没说需要选择");
        }
    }

    // ------------------------------------------------------------------
    // 只设 DW_AI_MODE（deploy-mode）时，模式必须跟着它走
    // ------------------------------------------------------------------

    /**
     * 旧开关 {@code deploy-mode}（环境变量 {@code DW_AI_MODE}）必须真的能切模式。
     *
     * <p><b>这条是为一个真实故障补的</b>：{@code application.yml} 里 {@code run-mode} 的占位符
     * 默认值曾被写成非空的 {@code standard}，而 {@code DwaiProperties.runMode()} 的回落是
     * 「runMode 非空则用它，否则用 deployMode」—— 于是 deployMode 永远读不到，
     * {@code DW_AI_MODE=standalone} 启动出来的进程实际跑在 standard：
     * {@code /api/auth/config} 报 {@code runMode=standard} + {@code allowLogin=true}，
     * 业务接口一律 401。配置看着没错、日志没有任何异常，只是「独立模式要登录」。
     *
     * <p>而 README、产品手册、安装包 {@code conf/env.sh}、docker-compose 用的<b>全都是</b>
     * {@code DW_AI_MODE} —— 这个开关一失效，四种部署方式一起中招。dw-org 的同一行默认值是空，
     * 所以只有 dw-model 出这个问题，两边的差异就在那一个字符上。
     *
     * <p>本类<b>刻意不设</b> {@code dwai.run-mode}：一旦设了就直接给值，测不到
     * 「yml 的默认值有没有架空 deploy-mode」这件事。所以它依赖运行测试的 JVM 里没有
     * {@code DW_AI_RUN_MODE} 环境变量（开发机与 CI 都不该设它；那是部署期变量）。
     */
    @Nested
    @SpringBootTest(classes = DwaiApplication.class, properties = {
            "spring.datasource.url=jdbc:h2:mem:dwmodel_mode_deployonly;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "dwai.deploy-mode=standalone",
            "dwai.security.mode=dev"
    })
    @AutoConfigureMockMvc
    class DeployModeOnly {

        @Autowired
        private MockMvc mvc;

        @Test
        void deployModeAloneSwitchesTheMode() throws Exception {
            JsonNode rt = json(mvc.perform(get("/api/runtime"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("standalone", rt.path("runMode").asText(),
                    "只设 DW_AI_MODE 没生效 —— application.yml 的 run-mode 占位符默认值又变成非空了："
                            + "它一旦非空就架空 deploy-mode，DW_AI_MODE 会被静默忽略");
        }

        /** 模式真的切过去了，业务接口就该跟 standalone 一样开放（探针给 404 而不是 401）。 */
        @Test
        void deployModeAloneOpensTheApi() throws Exception {
            mvc.perform(get(PROBE)).andExpect(status().isNotFound());
        }

        @Test
        void deployModeAloneHidesTheLogin() throws Exception {
            JsonNode cfg = json(mvc.perform(get("/api/auth/config"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("standalone", cfg.path("runMode").asText());
            assertFalse(cfg.path("allowLogin").asBoolean(),
                    "独立模式还挂着登录入口 —— 前端会把人拦在登录页上");
        }
    }
}
