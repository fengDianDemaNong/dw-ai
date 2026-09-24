package controller;

import com.dwai.lineage.Main;
import com.dwai.lineage.persistence.LocalUserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * standard 模式的本地认证：登录 / 刷新 / 登出 / 改密 的端到端契约。
 *
 * <p>为什么值得单独立一个测试类（而不是塞进 {@code RunModeSmokeTest}）：
 * 这里要钉的不是「服务能起来」，而是几条<b>安全语义</b> —— 每一条的失效表现
 * 都是一个看起来很正常的 200，只有断言能抓住：
 *
 * <ul>
 *   <li>刷新令牌<b>明文不落库</b>（库被拖走也拼不回可用令牌）</li>
 *   <li>登录<b>踢掉旧刷新令牌</b>（一处登录、别处下线的自救通道）</li>
 *   <li>登出后刷新令牌<b>真的失效</b>（无状态 JWT 做不到，靠落库撤销）</li>
 *   <li>改密必须验旧密码、且改完<b>踢掉全部刷新令牌</b></li>
 *   <li>改密用的是<b>已登录</b>身份，匿名改不了</li>
 * </ul>
 *
 * <h2>测试隔离：每个用例一个账号</h2>
 *
 * <p>这个类里的用例都是<b>破坏性</b>的 —— 改密码、踢刷新令牌、删账号状态。
 * 如果全类共用一个账号（比如种子里那个 {@code admin}），先跑的用例会把后跑的
 * 弄挂，而失败信息会指向错误的位置：<b>明明改密功能是好的，报错的却是「登录失败」</b>。
 * 这个坑本类踩过一次（{@code changePassword*} 按字母序排在前面，把 admin 的密码
 * 永久改掉，导致后面 7 个用例全部 401）。
 *
 * <p>所以：{@link #freshUser(int)} 给每个用例建一个独立账号，账号名带自增序号，
 * 用完即弃。种子账号只被 {@link #seedAdminExistsAndCanLogIn} 读一次，不做任何修改。
 *
 * <p>H2 内存库在整个测试类里共享（{@code DB_CLOSE_DELAY=-1}），所以账号是累积的 ——
 * 这正是我们要的：不清理就不会互相影响，隔离靠「各用各的」而不是靠「用完擦掉」，
 * 后者一旦某个用例中途失败就会留下脏状态，让后续失败更难定位。
 *
 * <p>全程走真实 HTTP，不直接调 service —— 这样过滤器链、解码器、拦截器的顺序问题
 * 才会暴露出来。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:lineage_local_auth;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        "lineage.run-mode=standard"
})
@AutoConfigureMockMvc
class LocalAuthApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 LocalAdminSeedRunner 一致。 */
    private static final String SEED_USER = "admin";
    private static final String SEED_PASSWORD = "123456";

    private static final String PASSWORD = "pw-secret";

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LocalUserRepository users;

    @Autowired
    private PasswordEncoder passwords;

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /**
     * 建一个本用例专属的普通账号（非管理员），返回 (username, password)。
     *
     * <p>刻意建成非管理员：{@code /api/auth/*} 这组端点的授权只要求「已登录」，
     * 用普通账号跑才能证明它们<b>没有</b>被误加上管理员要求。
     */
    private String[] freshUser(int tenantHint) {
        String username = "u" + SEQ.incrementAndGet() + "-" + tenantHint;
        users.insertUser(username, "测试用户" + username, passwords.encode(PASSWORD), false);
        return new String[]{username, PASSWORD};
    }

    private JsonNode login(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private JsonNode refresh(String refreshToken) throws Exception {
        return MAPPER.readTree(mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn().getResponse().getContentAsString());
    }

    private int refreshStatus(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int businessStatus(String accessToken) throws Exception {
        return mvc.perform(get("/api/stats/project")
                        .header("Authorization", "Bearer " + accessToken))
                .andReturn().getResponse().getStatus();
    }

    /** 登录一个专属账号，返回它的 access token。 */
    private String tokenFor(String[] user) throws Exception {
        return login(user[0], user[1]).path("token").asText();
    }

    // ------------------------------------------------------------------
    // 配置与前提
    // ------------------------------------------------------------------

    /** 前端靠它决定要不要渲染登录页；standard 下必须为 true。 */
    @Test
    void configAdvertisesLoginAndTtls() throws Exception {
        JsonNode cfg = MAPPER.readTree(mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertEquals("standard", cfg.path("runMode").asText());
        assertTrue(cfg.path("allowLogin").asBoolean(),
                "standard 下 allowLogin=false，前端不会渲染登录页 —— 那这个模式就没法进去了");
        assertTrue(cfg.path("accessTtlSeconds").asLong() > 0, "access TTL 没回报，前端无法定续期阈值");
        assertTrue(cfg.path("refreshTtlSeconds").asLong() > 0);
    }

    /**
     * 空库时种下的初始管理员必须真的存在且能登录。
     *
     * <p>只<b>读</b>它、不修改 —— 改行为由每个用例自己的账号承担，见类注释里的隔离说明。
     * 「能登录」用 {@code loginStatus} 验一次即可，不再签发令牌，避免污染它的刷新令牌集合。
     */
    @Test
    void seedAdminExistsAndCanLogIn() throws Exception {
        assertTrue(users.findByUsername(SEED_USER).isPresent(),
                "standard 空库没有种下 admin —— 新部署将无法首次登录");
        assertEquals(200, loginStatus(SEED_USER, SEED_PASSWORD),
                "种下的 admin 登不进去：种子、BCrypt 或登录链路有一环断了");
    }

    // ------------------------------------------------------------------
    // 门禁
    // ------------------------------------------------------------------

    @Test
    void anonymousBusinessRequestIsRejected() throws Exception {
        assertEquals(401, mvc.perform(get("/api/stats/project")).andReturn().getResponse().getStatus(),
                "standard 下匿名就能读业务接口 —— 这个模式等于没有认证");
    }

    /** 模式探测与登录本身必须匿名可达，否则是循环依赖。 */
    @Test
    void runtimeLoginAndConfigAreReachableAnonymously() throws Exception {
        assertEquals(200, mvc.perform(get("/api/runtime")).andReturn().getResponse().getStatus());
        assertEquals(200, mvc.perform(get("/api/auth/config")).andReturn().getResponse().getStatus());

        String[] u = freshUser(1);
        assertEquals(200, loginStatus(u[0], u[1]));
    }

    @Test
    void businessRequestWithTokenIsServed() throws Exception {
        String[] u = freshUser(2);
        String token = login(u[0], u[1]).path("token").asText();
        assertFalse(token.isBlank(), "登录没有返回 access token");
        assertEquals(200, businessStatus(token));
    }

    /** 密码错、账号不存在、用户名空 —— 三种都必须是同一个 401，不泄露账号是否存在。 */
    @Test
    void badCredentialsAllYieldTheSame401() throws Exception {
        String[] u = freshUser(3);
        assertEquals(401, loginStatus(u[0], "wrong-password"));
        assertEquals(401, loginStatus("no_such_user_" + SEQ.incrementAndGet(), PASSWORD));
        assertEquals(401, loginStatus("", PASSWORD));
    }

    // ------------------------------------------------------------------
    // 刷新令牌的语义
    // ------------------------------------------------------------------

    /** 库里存的必须是 SHA-256 十六进制，不是明文 —— 这是「库被拖走也不丢账号」的底线。 */
    @Test
    void refreshTokenIsStoredHashedNotInPlaintext() throws Exception {
        String[] u = freshUser(4);
        String raw = login(u[0], u[1]).path("refreshToken").asText();

        assertEquals(64, sha256(raw).length(),
                "探针自检：SHA-256 十六进制应为 64 字符");

        assertTrue(users.findRefreshTokenByHash(sha256(raw)).isPresent(),
                "库中找不到该刷新令牌的哈希 —— 存的很可能是明文或另一种摘要");
        assertTrue(users.findRefreshTokenByHash(raw).isEmpty(),
                "明文本身能在库里查到 —— 刷新令牌没有做哈希");
    }

    @Test
    void refreshReturnsNewAccessTokenAndKeepsWorking() throws Exception {
        String[] u = freshUser(5);
        JsonNode first = login(u[0], u[1]);
        String refreshToken = first.path("refreshToken").asText();

        JsonNode second = refresh(refreshToken);
        assertFalse(second.path("token").asText().isBlank(), "刷新没有返回新的 access token");
        assertEquals(u[0], second.path("username").asText());

        // 滑动续期：同一个 refresh 还在，能继续用
        assertEquals(200, refreshStatus(refreshToken));
        assertEquals(200, businessStatus(second.path("token").asText()));
    }

    @Test
    void unknownOrEmptyRefreshTokenIsRejected() throws Exception {
        assertEquals(401, refreshStatus("not-a-real-token"));
        assertEquals(401, refreshStatus(""));
    }

    /**
     * 登录<b>踢掉旧刷新令牌</b>：同一账号第二次登录后，第一次拿到的 refresh 必须失效。
     *
     * <p>这是「账号被盗、我在别处登录一次就能把他踢下线」的实现基础。
     */
    @Test
    void loginInvalidatesPreviousRefreshToken() throws Exception {
        String[] u = freshUser(6);
        String oldRefresh = login(u[0], u[1]).path("refreshToken").asText();
        assertEquals(200, refreshStatus(oldRefresh), "前提：刚签发的 refresh 应当可用");

        String newRefresh = login(u[0], u[1]).path("refreshToken").asText();
        assertNotEquals(oldRefresh, newRefresh, "两次登录拿到了同一个 refresh token");

        assertEquals(401, refreshStatus(oldRefresh),
                "旧刷新令牌在重新登录后仍然可用 —— 踢不下线，被盗号时没有自救手段");
        assertEquals(200, refreshStatus(newRefresh));
    }

    @Test
    void logoutRevokesRefreshToken() throws Exception {
        String[] u = freshUser(7);
        String refreshToken = login(u[0], u[1]).path("refreshToken").asText();

        mvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().is2xxSuccessful());

        assertEquals(401, refreshStatus(refreshToken),
                "登出后刷新令牌还能换出 access —— 登出是假的");
    }

    /** 登出幂等：令牌不存在也要成功。登出失败还要求用户处理是荒谬的。 */
    @Test
    void logoutIsIdempotent() throws Exception {
        assertEquals(200, mvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"never-existed\"}"))
                .andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------------
    // /me 与改密
    // ------------------------------------------------------------------

    @Test
    void meRequiresTokenAndReportsIdentity() throws Exception {
        assertEquals(401, mvc.perform(get("/api/auth/me")).andReturn().getResponse().getStatus(),
                "匿名就能读 /me —— 它应该要求已登录");

        String[] u = freshUser(8);
        String token = tokenFor(u);
        JsonNode me = MAPPER.readTree(mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        assertEquals(u[0], me.path("username").asText());
        assertEquals("standard", me.path("runMode").asText());
    }

    /**
     * 改密：必须先验旧密码，且改完<b>踢掉全部刷新令牌</b>。
     *
     * <p>「怀疑密码泄露」时唯一有效的自救动作就是改密 —— 如果改完旧 refresh 还能用，
     * 那就等于没改。这条用例把这一点钉住。
     *
     * <p>用专属账号，不改种子 admin：改了它会污染同库的其它用例（见类注释）。
     */
    @Test
    void changePasswordRequiresCurrentPasswordAndRevokesEverything() throws Exception {
        String[] u = freshUser(9);
        String refreshToken = login(u[0], u[1]).path("refreshToken").asText();
        String token = tokenFor(u);

        // 旧密码不对 → 400
        assertEquals(400, mvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"wrong\",\"newPassword\":\"newpass\"}"))
                .andReturn().getResponse().getStatus());

        // 匿名不可改（少写一条白名单就是这个后果）
        assertEquals(401, mvc.perform(put("/api/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"newpass\"}"))
                .andReturn().getResponse().getStatus(),
                "匿名就能改密码");

        // 正常改密
        mvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"newpass\"}"))
                .andExpect(status().is2xxSuccessful());

        assertEquals(401, refreshStatus(refreshToken),
                "改密后旧刷新令牌仍然可用 —— 密码泄露时无法自救");
        assertEquals(200, loginStatus(u[0], "newpass"), "新密码登不进去");
        assertEquals(401, loginStatus(u[0], PASSWORD), "旧密码仍然能登录");
    }

    @Test
    void shortNewPasswordIsRejected() throws Exception {
        String[] u = freshUser(10);
        String token = tokenFor(u);
        assertEquals(400, mvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"12\"}"))
                .andReturn().getResponse().getStatus());
    }

    /** 改显示名要已登录，且改完 {@code /me} 能看到新名字。 */
    @Test
    void updateProfileRenamesDisplayName() throws Exception {
        String[] u = freshUser(11);
        String token = tokenFor(u);

        String body = mvc.perform(put("/api/auth/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"新名字\"}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode me = MAPPER.readTree(body);
        assertEquals("新名字", me.path("displayName").asText());
        assertEquals(u[0], me.path("username").asText());

        assertEquals(401, mvc.perform(put("/api/auth/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"匿名改的\"}".getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse().getStatus());
    }

    // ------------------------------------------------------------------

    /** 与 LocalAuthService.hash 同一实现（测试独立算一遍，避免共用代码掩盖不一致）。 */
    private static String sha256(String raw) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
