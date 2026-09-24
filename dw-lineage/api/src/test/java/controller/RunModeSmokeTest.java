package controller;

import com.dwai.lineage.Main;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import support.DevJwt;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 三模式冒烟：数据地图（dw-lineage）在 <b>standalone / standard / multi</b> 下都必须能起来。
 *
 * <p>数据地图是「可以被单独拿出来用」这条承诺的标杆 —— 它默认就是
 * {@code standalone}（{@code lineage.run-mode} 缺省值），历史上也确实一直是独立跑的。
 * 这里把三种模式各起一次，确认后来加的组织整合能力没有把独立部署弄坏。
 *
 * <p>multi 模式额外断言一条韧性：组织平台不可达时（{@code org-base-url} 为空）
 * 本进程仍须正常启动 —— 心跳失败只记日志，不能让数据地图跟着躺下。
 *
 * <h2>三种模式的身份门禁（本轮补 standard 认证后的形态）</h2>
 *
 * <pre>
 *   standalone → 无认证，业务接口直接 200
 *   standard   → 本地账号签发 JWT，业务接口要令牌
 *   multi      → 组织签发 JWT，业务接口要令牌（且还要租户头）
 * </pre>
 */
class RunModeSmokeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode runtime(MockMvc mvc) throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/runtime"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /**
     * 只取状态码的 GET / POST —— 这些用例关心的是「让不让过」，不是响应体。
     *
     * <p>名字里的 {@code …Of} 不是装饰：直接叫 {@code status} 会<b>遮蔽</b>静态导入的
     * {@code MockMvcResultMatchers.status()}（类内声明的方法优先于单静态导入），
     * 于是本文件里所有 {@code status().isOk()} 一起编译不过。
     */
    private static int statusOf(MockMvc mvc, String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    private static int postStatusOf(MockMvc mvc, String path, String body) throws Exception {
        return mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn().getResponse().getStatus();
    }

    /** {@code /api/auth/config} 的响应，前端靠它决定要不要渲染登录页。 */
    private static JsonNode authConfig(MockMvc mvc) throws Exception {
        return MAPPER.readTree(mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    @Nested
    @SpringBootTest(classes = Main.class, properties = {
            "database.type=h2",
            "database.url=jdbc:h2:mem:lineage_mode_standalone;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "database.username=sa",
            "database.password=",
            "lineage.run-mode=standalone"
    })
    @AutoConfigureMockMvc
    class Standalone {

        @Autowired
        private MockMvc mvc;

        @Test
        void startsAndReportsStandalone() throws Exception {
            JsonNode rt = runtime(mvc);
            assertEquals("metadata", rt.path("product").asText());
            assertEquals("standalone", rt.path("runMode").asText());
            assertTrue(rt.path("standalone").asBoolean());
            assertFalse(rt.path("multi").asBoolean());
        }

        /** 独立部署没有身份来源，业务接口不应因为「没带租户头」就拒绝。 */
        @Test
        void businessEndpointServesDefaultTenant() throws Exception {
            mvc.perform(get("/api/stats/project")).andExpect(status().isOk());
        }

        /**
         * 账号管理面在 standalone 下明确 403，而不是返回空列表。
         *
         * <p>空列表会让人以为「功能坏了」；403 + 文案能直接说明「这个模式下账号
         * 根本不归这里管」。同一条规则在 multi 下也一样（账号由组织管）。
         */
        @Test
        void accountManagementIsNotAvailable() throws Exception {
            mvc.perform(get("/api/users")).andExpect(status().isForbidden());
        }

        /**
         * 登录、续期、登出三条路都必须明确 403。
         *
         * <p>守的是 {@code LocalAuthController.requireStandardForLogin()}（三个端点各调一次）。
         * 任何一个漏掉，独立部署上就会出现一条<b>没有身份来源却签发令牌</b>的路 ——
         * 而独立模式恰恰是全放行的，令牌一旦签出来就是无门槛的通行证。
         */
        @Test
        void loginRefreshAndLogoutAreAllRefused() throws Exception {
            assertEquals(403, postStatusOf(mvc, "/api/auth/login",
                    "{\"username\":\"admin\",\"password\":\"123456\"}"),
                    "独立模式签发了令牌");
            assertEquals(403, postStatusOf(mvc, "/api/auth/refresh", "{\"refreshToken\":\"x\"}"),
                    "独立模式能续期 —— 没有登录却有续期");
            assertEquals(403, postStatusOf(mvc, "/api/auth/logout", "{\"refreshToken\":\"x\"}"),
                    "独立模式的登出没有被拒绝");
        }

        /**
         * {@code /api/auth/me} 必须 401，<b>不能凭空造一个身份</b>。
         *
         * <p>standalone 的 {@code /api/**} 是全放行的，所以这个请求会真的走到 controller，
         * 由 {@code CurrentLocalUser.require()} 在读不到 JWT 时给出 401。
         * 看起来「反正没人用」，但 contrived 的 200 会让前端渲染出一个不存在的登录用户，
         * 而那正是排查问题时最误导人的东西。
         */
        @Test
        void meIsNotFabricated() throws Exception {
            assertEquals(401, statusOf(mvc, "/api/auth/me"),
                    "独立模式凭空返回了一个身份 —— 那是无中生有的账号");
        }

        /** 前端据此不渲染登录页；sessionEpoch 对独立部署没有意义，回空串。 */
        @Test
        void configAdvertisesNoLogin() throws Exception {
            JsonNode cfg = authConfig(mvc);
            assertEquals("standalone", cfg.path("runMode").asText());
            assertFalse(cfg.path("allowLogin").asBoolean(), "独立模式不该出现登录入口");
            assertEquals("", cfg.path("sessionEpoch").asText(),
                    "独立模式没有自签令牌，不该回报世代 id");
        }

        /**
         * {@code /api/v1/**} 是 {@code /api/**} 的别名，两边都要挂上。
         *
         * <p>控制器上是 {@code @RequestMapping({"/api", "/api/v1"})} 成对写的，
         * 漏一处就是前端在 v1 前缀下 404。
         */
        @Test
        void v1AliasIsServed() throws Exception {
            JsonNode v1 = MAPPER.readTree(mvc.perform(get("/api/v1/runtime"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("standalone", v1.path("runMode").asText());
            assertEquals(runtime(mvc).path("product").asText(), v1.path("product").asText());
        }

        /**
         * 独立模式是<b>唯一</b>能管租户的模式。
         *
         * <p>与另外两个嵌套类里的同名反面用例配对：租户写操作在 standard 与 multi 下
         * 一律 403（{@code TenantAdminController.assertTenantWritable}），只有 standalone 放行。
         * 这条同时是「独立部署的租户管理页没被弄坏」的守卫。
         */
        @Test
        void tenantWritesAreAllowedHere() throws Exception {
            assertEquals(200, postStatusOf(mvc, "/api/tenants",
                    "{\"code\":\"probe-standalone\",\"name\":\"独立模式建的租户\"}"),
                    "唯一能管租户的模式反而不让建 —— 独立部署的租户管理页会废掉");
        }
    }

    @Nested
    @SpringBootTest(classes = Main.class, properties = {
            "database.type=h2",
            "database.url=jdbc:h2:mem:lineage_mode_standard;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "database.username=sa",
            "database.password=",
            "lineage.run-mode=standard"
    })
    @AutoConfigureMockMvc
    class Standard {

        @Autowired
        private MockMvc mvc;

        @Test
        void startsAndReportsStandard() throws Exception {
            JsonNode rt = runtime(mvc);
            assertEquals("standard", rt.path("runMode").asText());
            assertTrue(rt.path("standard").asBoolean());
            assertFalse(rt.path("multi").asBoolean());
        }

        /**
         * standard 是<b>有账号体系</b>的普通模式：业务接口要先登录、再带令牌。
         *
         * <p>这条用例此前断言「无认证也 200」，理由是「standard 自管账号、不调组织」。
         * 那个理由只对了一半 —— 不调组织是对的，但正因为「不调组织」，它才更需要
         * 自己的认证；否则一个标着 standard 的部署实际上是裸奔的。
         * 现在 standard 的 {@code /api/**} 与 multi 一样要求令牌。
         *
         * <p>登录用的是 {@code LocalAdminSeedRunner} 在空库时种的 admin/123456。
         * 这里刻意走<b>真实 HTTP 登录</b>而不是直接签令牌：种子账号、BCrypt 校验、
         * 签发、解码器校验世代这条完整链路，任何一环断了都该让测试红。
         */
        @Test
        void businessEndpointRequiresLocalToken() throws Exception {
            assertEquals(401, mvc.perform(get("/api/stats/project")).andReturn().getResponse().getStatus(),
                    "standard 模式下匿名就能读写 —— 等于没有认证");

            String body = mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            String token = MAPPER.readTree(body).path("token").asText();
            assertFalse(token.isBlank(), "登录没有返回令牌");

            mvc.perform(get("/api/stats/project").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }

        /**
         * standard 固定单租户：缺租户头回落默认租户，<b>带了头也照样忽略</b>。
         *
         * <p>后半句是本轮收紧的重点。此前 {@code TenantInterceptor} 只在 multi 下强制租户头，
         * standard 下 {@code X-Tenant-Id: 2} 会被 {@code Long.parseLong} 直接采纳 ——
         * 于是登录用户（以及任何能 curl 到端口的人）带个数字就能读写别的租户，写出孤儿数据
         * （见 {@code KNOWN_ISSUES.md}）。现在租户头整个不看：界面不出现租户、
         * 接口不认租户头，是同一条规则的两种表现。
         *
         * <p>顺带覆盖了 {@code X-Tenant-Code} 传一个解析不到的编码：以前这会 400，
         * 现在租户头根本不解析，所以应当照常 200。
         */
        @Test
        void tenantHeaderIsIgnoredSoContextStaysOnDefaultTenant() throws Exception {
            String token = loginAs("admin", "123456");

            JsonNode noHeader = MAPPER.readTree(mvc.perform(get("/api/context")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals(1, noHeader.path("tenantId").asLong(), "缺租户头没有回落到默认租户");

            JsonNode withHeader = MAPPER.readTree(mvc.perform(get("/api/context")
                            .header("Authorization", "Bearer " + token)
                            .header("X-Tenant-Id", "2")
                            .header("X-Tenant-Code", "not-exist")
                            .header("X-Project-Id", "2"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals(1, withHeader.path("tenantId").asLong(),
                    "standard 下租户头没有被忽略 —— 登进来的人能读写别的租户");
        }

        /**
         * standard 下租户的<b>写操作对谁都是 403</b>，<b>读</b>仍开放但只剩默认租户一条。
         *
         * <p>这条此前钉的是「普通用户不能建租户、管理员可以」。本轮把租户维度整个从
         * standard 移除后管理员也不行了 —— 留着「管理员仍能建第二个租户」这条口子，
         * 等于没真正收紧。所以断言从「403 / 200」变成「两个都 403」。
         *
         * <p>读为什么仍然开放：它是切换器与页面正常渲染的依赖，普通用户也要用。
         * 但列表被 {@code TenantAdminServiceImpl.visibleTenants} 收成默认租户一条 ——
         * 收紧在后端成立，不依赖前端把菜单藏起来。
         */
        @Test
        void tenantWritesAreForbiddenForEveryoneButListStaysReadable() throws Exception {
            String admin = loginAs("admin", "123456");
            String plain = plainUserToken(admin);

            JsonNode listed = MAPPER.readTree(mvc.perform(get("/api/tenants")
                            .header("Authorization", "Bearer " + plain))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals(1, listed.size(), "standard 下租户列表不止默认租户一条");

            assertEquals(403, mvc.perform(post("/api/tenants")
                            .header("Authorization", "Bearer " + plain)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"nope\",\"name\":\"不该被建出来\"}"))
                    .andReturn().getResponse().getStatus(),
                    "普通用户可以建租户 —— 登录了，但没判权");

            assertEquals(403, mvc.perform(post("/api/tenants")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"nope-admin\",\"name\":\"管理员也不该建出来\"}"))
                    .andReturn().getResponse().getStatus(),
                    "管理员仍能建租户 —— 租户维度没有真正从 standard 移除");
        }

        /**
         * 项目是 standard 下<b>保留</b>的维度：管理员建得出来，普通用户不行。
         *
         * <p>租户与项目原本共用 {@code assertLocalAdmin()}，本轮拆成了两条路
         * （{@code assertTenantWritable} 管租户、{@code assertLocalAdmin} 管项目）。
         * 收紧租户时最容易顺手把项目一起关掉，这条用例就是钉住那件事没发生。
         */
        @Test
        void projectWritesStillRequireAdmin() throws Exception {
            String admin = loginAs("admin", "123456");
            String plain = plainUserToken(admin);

            assertEquals(403, mvc.perform(post("/api/tenants/1/projects")
                            .header("Authorization", "Bearer " + plain)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"p-nope\",\"name\":\"普通用户建的\"}"))
                    .andReturn().getResponse().getStatus(),
                    "普通用户可以建项目 —— 项目没判权");

            mvc.perform(post("/api/tenants/1/projects")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"p-ok\",\"name\":\"管理员建的项目\"}"))
                    .andExpect(status().isOk());
        }

        /**
         * 续期与登出走<b>本地</b>这条路，并且不存在的令牌必须是 401。
         *
         * <p>守的是 {@code LocalAuthController.refresh/logout} 里那条「非 standard 直接 403」
         * 的守卫方向：这里是 standard，所以<b>不能</b> 403，而要真的去查库。
         * 抛 403 说明守卫写反了（把 standard 也挡了，普通模式将无法续期）；
         * 抛 500 说明异常没被翻译成状态码。
         */
        @Test
        void refreshAndLogoutAreHandledLocally() throws Exception {
            assertEquals(401, postStatusOf(mvc, "/api/auth/refresh", "{\"refreshToken\":\"no-such-token\"}"),
                    "不存在的刷新令牌没有回 401");
            assertEquals(200, postStatusOf(mvc, "/api/auth/logout", "{\"refreshToken\":\"no-such-token\"}"),
                    "登出必须幂等 —— 令牌不存在也要 200");
        }

        /** 前端据此渲染登录页；sessionEpoch 只在 standard 有值。 */
        @Test
        void configAdvertisesLogin() throws Exception {
            JsonNode cfg = authConfig(mvc);
            assertEquals("standard", cfg.path("runMode").asText());
            assertTrue(cfg.path("allowLogin").asBoolean(),
                    "standard 不提供登录 —— 那这个模式就进不去了");
            assertTrue(!cfg.path("sessionEpoch").asText().isBlank(),
                    "standard 自签令牌，必须回报世代 id，否则重启后前端无法识别令牌整体失效");
        }

        /**
         * 与 {@code Standalone.tenantWritesAreAllowedHere} 配对的<b>反面</b>：
         * 普通模式下租户写对谁都是 403。
         *
         * <p>租户维度已从 standard 整体移除，「固定使用默认租户」是设计，不是缺陷。
         * 误把它放开，普通模式就会重新长出第二个租户。
         */
        @Test
        void tenantWritesAreForbidden() throws Exception {
            String admin = loginAs("admin", "123456");
            assertEquals(403, mvc.perform(post("/api/tenants")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"probe-standard\",\"name\":\"普通模式不该建出来\"}"))
                    .andReturn().getResponse().getStatus(),
                    "管理员能在普通模式建租户 —— 租户维度没有真正移除");
        }

        /**
         * 建一个非管理员账号并登录，用来走「登录了但没判权」这条路径。
         *
         * <p>刻意<b>不</b>断言创建成功：本类的多条用例共用一个 H2 库
         * （{@code DB_CLOSE_DELAY=-1}），第二次调用时账号已经存在、创建会失败。
         * 那不影响这里要验的东西 —— 能登进去就够了。
         */
        private String plainUserToken(String adminToken) throws Exception {
            mvc.perform(post("/api/users")
                    .header("Authorization", "Bearer " + adminToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"username\":\"plain-user\",\"displayName\":\"普通用户\","
                            + "\"password\":\"pw-123456\",\"admin\":false}"));
            return loginAs("plain-user", "pw-123456");
        }

        /** 走真实 HTTP 登录并取回 access token，避免测试自己签令牌而绕过种子/密码/世代校验。 */
        private String loginAs(String username, String password) throws Exception {
            String body = mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return MAPPER.readTree(body).path("token").asText();
        }
    }

    @Nested
    @SpringBootTest(classes = Main.class, properties = {
            "database.type=h2",
            "database.url=jdbc:h2:mem:lineage_mode_multi;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "database.username=sa",
            "database.password=",
            "lineage.run-mode=multi",
            // 组织平台地址留空：模拟「org 还没起来 / 网络不通」
            "lineage.org-base-url="
    })
    @AutoConfigureMockMvc
    class Multi {

        @Autowired
        private MockMvc mvc;

        @Test
        void startsAndReportsMulti() throws Exception {
            JsonNode rt = runtime(mvc);
            assertEquals("multi", rt.path("runMode").asText());
            assertTrue(rt.path("multi").asBoolean());
            assertFalse(rt.path("standalone").asBoolean());
        }

        /**
         * 组织平台不在，数据地图也要活着 —— 但要带<b>令牌与租户头</b>才服务业务接口。
         *
         * <p>心跳失败在 {@code OrgClient.heartbeat} 里被吞成 WARN 是刻意的：
         * 数据地图的核心能力（SQL 解析、血缘、元数据）不依赖组织平台，
         * 因为它连不上就拒绝启动，等于把一个可独立部署的服务绑死在 org 上。
         *
         * <p>注意这里必须同时带两样东西：multi 下的两道门禁是<b>串联</b>的 ——
         * 没有组织 JWT 先得 401（SecurityConfig），有 JWT 但缺租户头得 400
         * （{@code TenantInterceptor}）。此前这条用例只带租户头就期望 200，
         * 因为那时还没有鉴权；那个断言在这轮补鉴权后失效了。
         */
        @Test
        void servesWithTokenAndTenantHeaderWhenOrgIsUnreachable() throws Exception {
            mvc.perform(get("/api/stats/project")
                            .header("Authorization", DevJwt.bearer())
                            .header("X-Tenant-Id", "1")
                            .header("X-Project-Id", "1"))
                    .andExpect(status().isOk());
        }

        /**
         * multi 下账号管理面不可用 —— 账号由组织平台统一管理，本模块的 users 表是空的。
         *
         * <p>注意这里带了<b>组织 JWT</b>：不带的话先被 SecurityConfig 挡成 401，
         * 那就测不到「本模块明确拒绝账号管理」这一层了。
         */
        @Test
        void accountManagementIsNotAvailableInMulti() throws Exception {
            mvc.perform(get("/api/users")
                            .header("Authorization", DevJwt.bearer())
                            .header("X-Tenant-Id", "1")
                            .header("X-Project-Id", "1"))
                    .andExpect(status().isForbidden());
        }

        /**
         * multi 下登录同样 403，但<b>文案与独立模式不同</b>。
         *
         * <p>两条都是 403，但原因必须能区分：独立模式是「本模式没有登录」，
         * multi 是「登录由组织平台统一提供」。合并成一条会让人查错方向 ——
         * multi 下要修的是组织平台，不是数据地图。
         *
         * <p><b>这里的租户头不是凑数</b>：multi 下 {@code TenantInterceptor} 在
         * controller <b>之前</b>就要租户上下文，不带头的请求连 {@code requireStandardForLogin()}
         * 都到不了（先得 400，见 {@link #loginWithoutTenantHeaderIsRejectedEarlier()}）。
         * 所以要验的是「那道 403 的门还在不在」，就得把请求送到门前面去。
         */
        @Test
        void loginIsRefusedAndSaysWhy() throws Exception {
            var res = mvc.perform(post("/api/auth/login")
                            .header("X-Tenant-Id", "1")
                            .header("X-Project-Id", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                    .andReturn().getResponse();
            assertEquals(403, res.getStatus(), "multi 下登录口被打开了 —— 出现了第二条身份来源");
            assertTrue(res.getContentAsString(StandardCharsets.UTF_8).contains("组织"),
                    "403 的文案没指向组织平台，排查的人会去查错地方："
                            + res.getContentAsString(StandardCharsets.UTF_8));
        }

        /**
         * 不带租户头时，先拒的是拦截器（400），而不是那道 403。
         *
         * <p>钉这一条是因为它决定了上面那条用例必须带头，也因为它是个容易被误读的状态码：
         * 调用方看到「必须带 X-Tenant-Code」会以为补上头就能登录，而实际上补上也是 403——
         * 真正的原因始终是「multi 的登录在组织平台」。400 只是排队在前面。
         */
        @Test
        void loginWithoutTenantHeaderIsRejectedEarlier() throws Exception {
            var res = mvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                    .andReturn().getResponse();
            assertEquals(400, res.getStatus(),
                    "multi 下缺租户头应当由拦截器拒掉；若这里变成 403，说明拦截器不再要求租户头");
            assertTrue(res.getContentAsString(StandardCharsets.UTF_8).contains("租户"),
                    "400 的文案没提到租户头，调用方不知道该补什么："
                            + res.getContentAsString(StandardCharsets.UTF_8));
        }

        /**
         * 前端据此不渲染登录页（登录由组织平台负责）；世代 id 对组织令牌没有意义。
         *
         * <p>{@code /api/auth/config} 不在 {@code TenantInterceptor} 的免解析名单里
         * （名单只有 {@code /api/runtime} 与 {@code /api/tenants}），所以 multi 下也必须带头 ——
         * 这与 {@code /api/runtime} 那种「不知道模式就不知道该带什么头」的自举接口不同，
         * 这里假定调用方已经知道自己是谁。当前前端并未调用此接口（它是对外的形态探测契约），
         * 所以这是记录现状，不是修复后的形态。
         */
        @Test
        void configAdvertisesNoLogin() throws Exception {
            JsonNode cfg = MAPPER.readTree(mvc.perform(get("/api/auth/config")
                            .header("X-Tenant-Id", "1")
                            .header("X-Project-Id", "1"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString());
            assertEquals("multi", cfg.path("runMode").asText());
            assertFalse(cfg.path("allowLogin").asBoolean(), "multi 不该出现本地登录入口");
            assertEquals("", cfg.path("sessionEpoch").asText(),
                    "multi 的令牌由组织签发，本模块不该回报自己的世代 id");
        }

        /**
         * 与 {@code Standalone.tenantWritesAreAllowedHere} 配对的另一面：
         * multi 下租户由组织平台管，本模块一律 403。
         *
         * <p>这是「不共库」的具体体现 —— 数据地图要是也能建租户，两边的租户表就开始打架。
         */
        @Test
        void tenantWritesAreForbidden() throws Exception {
            assertEquals(403, mvc.perform(post("/api/tenants")
                            .header("Authorization", DevJwt.bearer())
                            .header("X-Tenant-Id", "1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"probe-multi\",\"name\":\"multi 不该建出来\"}"))
                    .andReturn().getResponse().getStatus(),
                    "数据地图在 multi 下建出了租户 —— 两边的租户表要打架了");
        }
    }

    // ------------------------------------------------------------------
    // 配错了怎么办：归一化的兜底必须朝「要认证」倒
    // ------------------------------------------------------------------

    /**
     * {@code lineage.run-mode} 设成<b>空串</b>时的行为。
     *
     * <p>这不是虚构的场景：{@code ${LINEAGE_RUN_MODE:standard}} 只在变量<b>未设</b>时
     * 取默认值，写成 {@code LINEAGE_RUN_MODE=} 拿到的是空串 —— 恰好绕过 yml 里那个安全的默认值。
     * 而空串最终落到 {@code LineageProperties.runMode()} 的兜底分支上。
     *
     * <p>此前那个兜底是 {@code standalone}，于是这种写法会让数据地图<b>静默变成无认证</b>：
     * 服务照常起来、日志没有任何异常、页面照常用，只是谁都能读写全部数据。
     * 现在兜底改为 {@code standard}，本类把这一点钉住。
     *
     * <p>归一化函数本身的取值表由 {@code conf.LineagePropertiesRunModeTest} 穷举，
     * 这里只验「真的接到了门禁上」—— 功能函数对、装配错，照样是裸奔。
     */
    @Nested
    @SpringBootTest(classes = Main.class, properties = {
            "database.type=h2",
            "database.url=jdbc:h2:mem:lineage_mode_blank;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "database.username=sa",
            "database.password=",
            "lineage.run-mode="
    })
    @AutoConfigureMockMvc
    class BlankRunMode {

        @Autowired
        private MockMvc mvc;

        @Test
        void blankRunModeDoesNotOpenTheApi() throws Exception {
            assertEquals("standard", runtime(mvc).path("runMode").asText(),
                    "空值没有兜底到 standard —— 配置写漏一个值就换了个模式跑");
            assertEquals(401, statusOf(mvc, "/api/stats/project"),
                    "空值把数据地图变成了无认证 —— 这个失效在日志里看不出来");
            assertTrue(authConfig(mvc).path("allowLogin").asBoolean(),
                    "兜底落在 standard，就该有本地登录口，否则这个部署既进不去也跟不上");
        }
    }

    // ------------------------------------------------------------------
    // 只设 DW_AI_MODE（deploy-mode）时，模式必须跟着它走
    // ------------------------------------------------------------------

    /**
     * 三模块共用的部署开关 {@code DW_AI_MODE} 必须也能切数据地图的模式。
     *
     * <p><b>这条是为一个真实故障补的</b>：数据地图原先只认 {@code LINEAGE_RUN_MODE}，
     * 运维按 README 给 org / model / lineage 统一设 {@code DW_AI_MODE=standalone} 时，
     * 只有它静默跑成 standard —— 接口 401、前端渲染登录页，而 {@code /api/runtime}
     * 老老实实报 standard，日志里找不到任何异常。dw-org 与 dw-model 都吃 {@code DW_AI_MODE}，
     * 只有这里不吃，这个不一致本身就是坑。
     *
     * <p>本类<b>刻意不设</b> {@code lineage.run-mode}：设了就直接给值，测不到
     * 「yml 的默认值有没有架空 deploy-mode」这件事（dw-model 正是这么失效的 ——
     * 它的 {@code run-mode} 占位符默认值写成了非空的 standard）。所以这条依赖运行测试的
     * JVM 里没有 {@code LINEAGE_RUN_MODE} 环境变量；那是部署期变量，开发机与 CI 都不该设。
     */
    @Nested
    @SpringBootTest(classes = Main.class, properties = {
            "database.type=h2",
            "database.url=jdbc:h2:mem:lineage_mode_deployonly;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "database.username=sa",
            "database.password=",
            "lineage.deploy-mode=standalone"
    })
    @AutoConfigureMockMvc
    class DeployModeOnly {

        @Autowired
        private MockMvc mvc;

        @Test
        void deployModeAloneSwitchesTheMode() throws Exception {
            JsonNode rt = runtime(mvc);
            assertEquals("standalone", rt.path("runMode").asText(),
                    "只设 DW_AI_MODE 没生效 —— 不是这里漏了回落，就是 yml 里 run-mode 的"
                            + "占位符默认值又变成非空了（那会架空 deploy-mode）");
            assertTrue(rt.path("standalone").asBoolean());
            assertFalse(rt.path("multi").asBoolean());
        }

        /** 模式真切过去了，业务接口就该跟 standalone 一样免令牌。 */
        @Test
        void deployModeAloneOpensTheApi() throws Exception {
            assertEquals(200, statusOf(mvc, "/api/stats/project"),
                    "DW_AI_MODE=standalone 切过去了、接口却还要令牌");
        }

        @Test
        void deployModeAloneHidesTheLogin() throws Exception {
            JsonNode cfg = authConfig(mvc);
            assertEquals("standalone", cfg.path("runMode").asText());
            assertFalse(cfg.path("allowLogin").asBoolean(),
                    "独立部署还挂着登录入口 —— 前端会把人拦在登录页上");
        }
    }
}
