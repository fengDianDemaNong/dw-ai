package controller;

import com.dwai.lineage.Main;
import com.dwai.lineage.persistence.LocalUserRepository;
import com.dwai.lineage.persistence.LocalUserRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 本地账号管理面（{@code /api/users}）的端到端契约。
 *
 * <p>这个类是「一旦漏判就是提权漏洞」那条边界的守卫。检查的都是<b>看起来会 200</b>
 * 的失败形态：
 *
 * <ul>
 *   <li>匿名 / 普通用户能列出或改动别人的账号</li>
 *   <li>响应里带出 {@code passwordHash}</li>
 *   <li>停用某人之后，他还能靠刷新令牌继续用</li>
 *   <li>重置某人密码之后，攻击者手上的 refresh 还能换出新 access</li>
 *   <li>撤销管理员之后，他的旧令牌仍然能管账号</li>
 * </ul>
 *
 * <h2>测试隔离</h2>
 *
 * <p>和 {@link LocalAuthApiTest} 同一套路数：需要被改动的账号一律由本用例现建，
 * 种子 {@code admin} 只作为<b>执行操作的管理员</b>出现、从不被改动
 * （所有针对它自己的操作都应当被 409 拒绝，所以它天然是稳定的）。
 * 这样用例之间不会互相污染，失败也不会指向错误的功能。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:lineage_user_admin;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        "lineage.run-mode=standard"
})
@AutoConfigureMockMvc
class LocalUserApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ADMIN = "admin";
    private static final String ADMIN_PW = "123456";
    private static final String PW = "pw-secret";

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

    /** 现建一个普通账号，返回它的行。 */
    private LocalUserRow newMember() {
        String username = "m" + SEQ.incrementAndGet();
        long id = users.insertUser(username, "成员" + username, passwords.encode(PW), false);
        return users.findById(id).orElseThrow();
    }

    private ObjectNode createBody(String username, String displayName, String password, Boolean admin) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("username", username);
        body.put("displayName", displayName);
        if (password != null) body.put("password", password);
        if (admin != null) body.put("admin", admin);
        return body;
    }

    private JsonNode login(String username, String password) throws Exception {
        String raw = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                                .getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return MAPPER.readTree(raw);
    }

    private int loginStatus(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
                                .getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse().getStatus();
    }

    /** 以管理员身份登录，返回 access token。 */
    private String adminToken() throws Exception {
        return login(ADMIN, ADMIN_PW).path("token").asText();
    }

    private int status(String method, String path, String token, Object body) throws Exception {
        var rb = switch (method) {
            case "get" -> get(path);
            case "post" -> post(path);
            case "put" -> put(path);
            case "delete" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (token != null) rb = rb.header("Authorization", "Bearer " + token);
        if (body != null) {
            rb = rb.contentType(MediaType.APPLICATION_JSON)
                    .content(MAPPER.writeValueAsBytes(body));
        }
        return mvc.perform(rb).andReturn().getResponse().getStatus();
    }

    private void adminPut(String path, Object body) throws Exception {
        assertEquals(200, status("put", path, adminToken(), body), path + " 应当被管理员放行");
    }

    private ObjectNode statusBody(int status) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("status", status);
        return body;
    }

    // ------------------------------------------------------------------
    // 门禁：谁都不能碰别人的账号，只有管理员可以
    // ------------------------------------------------------------------

    @Test
    void anonymousCannotListAccounts() throws Exception {
        assertEquals(401, status("get", "/api/users", null, null),
                "匿名就能列出所有账号 —— 这是管理面");
    }

    @Test
    void plainMemberCannotManageAccounts() throws Exception {
        LocalUserRow member = newMember();
        String token = login(member.username(), PW).path("token").asText();

        assertEquals(403, status("get", "/api/users", token, null),
                "普通用户能列出全部账号 —— 暴露了部署上有谁、谁是管理员");
        assertEquals(403, status("get", "/api/users/" + member.id(), token, null), token);
        assertEquals(403, status("post", "/api/users", token,
                createBody("sneak" + SEQ.incrementAndGet(), "偷偷建的", PW, true)), "普通用户能建号 —— 可以给自己开管理员");
        assertEquals(403, status("put", "/api/users/" + member.id() + "/status", token, statusBody(0)),
                "普通用户能停用别人");
        assertEquals(403, status("delete", "/api/users/" + member.id(), token, null),
                "普通用户能删账号");
        assertEquals(403, status("put", "/api/users/" + member.id() + "/password", token,
                MAPPER.createObjectNode().put("password", "hacked")), "普通用户能改别人的密码 —— 可以直接接管账号");
    }

    @Test
    void adminCanListAndResponseNeverCarriesPasswordHash() throws Exception {
        LocalUserRow member = newMember();
        String raw = mvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + adminToken()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode list = MAPPER.readTree(raw);
        assertTrue(list.isArray() && list.size() >= 2, "列表至少应含种子 admin 与本用例新建的成员");

        JsonNode seed = null;
        JsonNode mine = null;
        for (JsonNode n : list) {
            if (ADMIN.equals(n.path("username").asText())) seed = n;
            if (member.username().equals(n.path("username").asText())) mine = n;
        }
        assertNotNull(seed, "列表里没有种子 admin");
        assertNotNull(mine, "列表里没有刚建出来的成员");
        assertTrue(seed.path("admin").asBoolean(), "种子 admin 的 admin 字段应当为 true");
        assertFalse(mine.path("admin").asBoolean(), "普通成员不该是管理员");
        assertTrue(mine.path("enabled").asBoolean(), "新建账号应当默认启用");

        // 这条是整个类的核心断言：哈希一旦漏出去，即使只是 BCrypt，
        // 也等于把离线爆破的素材散发了一遍（列表会进日志、会被贴进群里）
        assertFalse(raw.contains("passwordHash"), "列表响应里出现了 passwordHash");
        assertFalse(raw.contains("$2a$"), "列表响应里出现了 BCrypt 哈希本体");
    }

    // ------------------------------------------------------------------
    // 建号 / 改号
    // ------------------------------------------------------------------

    @Test
    void createdAccountCanLogIn() throws Exception {
        String username = "newbie" + SEQ.incrementAndGet();
        String raw = mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MAPPER.writeValueAsBytes(
                                createBody(username, "新来的", "pw-newbie", null))))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode created = MAPPER.readTree(raw);
        assertEquals(username, created.path("username").asText());
        assertEquals(200, loginStatus(username, "pw-newbie"), "新建的账号登不进去");
    }

    @Test
    void duplicateUsernameIsConflict() throws Exception {
        LocalUserRow member = newMember();
        assertEquals(409, status("post", "/api/users", adminToken(),
                        createBody(member.username(), "重名", PW, null)),
                "重名建号没有拒绝 —— 唯一约束的报错会变成 500 而不是可读的 409");
    }

    @Test
    void shortPasswordOnCreateIsRejected() throws Exception {
        assertEquals(400, status("post", "/api/users", adminToken(),
                        createBody("shortpw" + SEQ.incrementAndGet(), "短密码", "12", null)),
                "建号时允许了过短的密码");
    }

    @Test
    void renameIsApplied() throws Exception {
        LocalUserRow member = newMember();
        adminPut("/api/users/" + member.id(),
                createBody(member.username(), "改过的名字", null, null));
        assertEquals("改过的名字", users.findById(member.id()).orElseThrow().displayName());
    }

    /**
     * 提权<b>立刻生效，不需要重新登录</b>。
     *
     * <p>这条同时钉住一个实现选择：{@code CurrentLocalUser} 每次都回查数据库取
     * {@code isAdmin}，而不是信 JWT 里的快照。如果哪天有人为了省一次查询改成信令牌，
     * 这条会红 —— 而那正是「撤销了管理员，他却还能继续管账号 15 分钟」的漏洞形态。
     */
    @Test
    void promotionTakesEffectOnTheExistingToken() throws Exception {
        LocalUserRow member = newMember();
        String token = login(member.username(), PW).path("token").asText();
        assertEquals(403, status("get", "/api/users", token, null), "前提：他本来不是管理员");

        adminPut("/api/users/" + member.id(), createBody(member.username(), member.displayName(), null, true));

        assertEquals(200, status("get", "/api/users", token, null),
                "提权后旧令牌仍然 403 —— 说明判权读的是令牌快照而不是库");
    }

    /** 反向：撤销管理员也立刻生效（同一个机制的另一面，这才是安全相关的方向）。 */
    @Test
    void demotionTakesEffectOnTheExistingToken() throws Exception {
        String username = "tempadmin" + SEQ.incrementAndGet();
        long id = users.insertUser(username, "临时管理员", passwords.encode(PW), true);
        String token = login(username, PW).path("token").asText();
        assertEquals(200, status("get", "/api/users", token, null), "前提：他本来是管理员");

        adminPut("/api/users/" + id, createBody(username, "临时管理员", null, false));

        assertEquals(403, status("get", "/api/users", token, null),
                "撤销管理员后旧令牌仍能管账号 —— 权限收回要等令牌过期才生效");
    }

    // ------------------------------------------------------------------
    // 停用 / 删除：必须连带杀掉刷新令牌
    // ------------------------------------------------------------------

    /**
     * 停用要<b>立刻</b>生效，包括刷新通道。
     *
     * <p>只把 {@code status} 改成 0 是不够的：access 只有 15 分钟，但 refresh 是长命的，
     * 被停用的人可以一直换新的 access 出来。所以停用必须删刷新令牌 ——
     * 这条用例专门检查那个「看起来已经停用了」的假象。
     */
    @Test
    void disableBlocksLoginAndRevokesRefreshToken() throws Exception {
        LocalUserRow member = newMember();
        JsonNode session = login(member.username(), PW);
        String refresh = session.path("refreshToken").asText();

        assertEquals(200, refreshStatus(refresh), "前提：刷新令牌本来可用");

        adminPut("/api/users/" + member.id() + "/status", statusBody(0));

        assertEquals(401, loginStatus(member.username(), PW),
                "被停用的账号仍然能登录");
        assertEquals(401, refreshStatus(refresh),
                "停用后刷新令牌仍然有效 —— 被停用的人能一直换出新 access，停用形同虚设");
    }

    @Test
    void reEnableRestoresAccess() throws Exception {
        LocalUserRow member = newMember();
        adminPut("/api/users/" + member.id() + "/status", statusBody(0));
        assertEquals(401, loginStatus(member.username(), PW));

        adminPut("/api/users/" + member.id() + "/status", statusBody(1));
        assertEquals(200, loginStatus(member.username(), PW), "重新启用后仍然登不进去");
    }

    @Test
    void deleteRemovesAccountAndRevokesTokens() throws Exception {
        LocalUserRow member = newMember();
        String refresh = login(member.username(), PW).path("refreshToken").asText();

        assertEquals(200, status("delete", "/api/users/" + member.id(), adminToken(), null));
        assertTrue(users.findById(member.id()).isEmpty(), "账号没有被真正删除");
        assertEquals(401, loginStatus(member.username(), PW));
        assertEquals(401, refreshStatus(refresh), "账号删了但刷新令牌还在");
    }

    // ------------------------------------------------------------------
    // 管理员重置密码
    // ------------------------------------------------------------------

    /**
     * 管理员重置密码：不需要旧密码，但重置后<b>必须</b>踢掉该账号的全部刷新令牌。
     *
     * <p>「怀疑密码泄露」时重置密码是止血动作 —— 如果攻击者手上的 refresh 还能换出
     * 新 access，血就没止住。这条用例检查的就是那个止血点。
     */
    @Test
    void adminResetPasswordChangesLoginAndRevokesRefresh() throws Exception {
        LocalUserRow member = newMember();
        String refresh = login(member.username(), PW).path("refreshToken").asText();

        ObjectNode body = MAPPER.createObjectNode().put("password", "reset-by-admin");
        assertEquals(200, status("put", "/api/users/" + member.id() + "/password", adminToken(), body));

        assertEquals(200, loginStatus(member.username(), "reset-by-admin"), "重置后新密码登不进去");
        assertEquals(401, loginStatus(member.username(), PW), "重置后旧密码仍然能登录");
        assertEquals(401, refreshStatus(refresh),
                "重置密码后旧刷新令牌仍然有效 —— 密码泄露时无法靠重置止血");
    }

    // ------------------------------------------------------------------
    // 防锁死：不能对自己动手
    // ------------------------------------------------------------------

    /**
     * 管理员不能停用 / 降级 / 删除<b>自己</b>。
     *
     * <p>这三条是防锁死的实际主力（推理见 {@code LocalUserService} 的类注释：
     * 在「只有管理员能管账号」的前提下，能让可用管理员归零的人只可能是管理员自己）。
     * 断言 409 而不只是「非 200」，是为了确认它<B>被明确拒绝</B>，而不是撞上了
     * 别的错误路径（比如 500）。
     */
    @Test
    void adminCannotLockHimselfOut() throws Exception {
        LocalUserRow seed = users.findByUsername(ADMIN).orElseThrow();
        String token = adminToken();

        assertEquals(409, status("put", "/api/users/" + seed.id() + "/status", token, statusBody(0)),
                "管理员把自己停用了 —— 部署将再也没人能登录");
        assertEquals(409, status("delete", "/api/users/" + seed.id(), token, null),
                "管理员把自己删了 —— 部署将再也没人能登录");
        assertEquals(409, status("put", "/api/users/" + seed.id(), token,
                        createBody(ADMIN, "管理员", null, false)),
                "管理员把自己降级了 —— 部署将再也没人能管账号");

        // 前提校验：上面三次拒绝之后，种子 admin 必须完好无损
        LocalUserRow after = users.findByUsername(ADMIN).orElseThrow();
        assertTrue(after.enabled(), "种子 admin 被误停用了");
        assertTrue(after.isAdmin(), "种子 admin 被误降级了");
        assertEquals(seed.id(), after.id());
    }

    // ------------------------------------------------------------------

    private int refreshStatus(String refreshToken) throws Exception {
        return mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"refreshToken\":\"" + refreshToken + "\"}")
                                .getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse().getStatus();
    }
}
