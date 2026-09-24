package auth;

import com.dwai.lineage.Main;
import com.dwai.lineage.auth.LocalUserService;
import com.dwai.lineage.dto.LocalUserRequest;
import com.dwai.lineage.persistence.LocalUserRepository;
import com.dwai.lineage.persistence.LocalUserRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「可用管理员不能被清零」这条不变量的兜底测试。
 *
 * <h2>为什么它必须绕过 HTTP</h2>
 *
 * <p>在真实的 HTTP 路径下，这条检查<b>打不到</b>。{@code requireAdmin()} 保证调用方
 * actor 是「启用的管理员」，于是：
 *
 * <pre>
 *   target 也是启用的管理员 且 target != actor
 *     → 库里至少有两个启用的管理员 → countEnabledAdmins() &gt;= 2 → guard 条件不成立
 *   target == actor
 *     → 先被 assertNotSelf 拦下，走不到 guard
 * </pre>
 *
 * <p>所以 {@code LocalUserApiTest.adminCannotLockHimselfOut} 覆盖的是
 * {@code assertNotSelf}（真正生效的那条），而本类用<b>一个不是任何真实账号的 actor</b>
 * 来模拟「调用方没有先做管理员校验」，从而覆盖 {@code guardLastEnabledAdmin} 本身。
 *
 * <p>这种测试写法通常要警惕 —— 造一个生产里不存在的调用形态，容易变成
 * 「测了个从未发生的场景」。这里刻意保留，是因为它守的前提（调用方一定先判权）
 * 是<b>约定</b>而不是编译期保证：新增一个非 HTTP 调用方、或有人直接注入
 * {@link LocalUserService}，guard 就是最后一道闸。用一个幽灵 actor 把这个前提
 * 显式地钉成测试，比让它只存在于注释里可靠。
 *
 * <p>本类的库是独立的、且刻意<b>不创建任何额外管理员</b> ——
 * 这样 {@code countEnabledAdmins()} 确定性地等于 1（只有种子 admin），
 * 不依赖测试执行顺序。
 */
@SpringBootTest(classes = Main.class, properties = {
        "database.type=h2",
        "database.url=jdbc:h2:mem:lineage_lockout_guard;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "database.username=sa",
        "database.password=",
        "lineage.run-mode=standard"
})
class LocalUserLockoutGuardTest {

    @Autowired
    private LocalUserService service;

    @Autowired
    private LocalUserRepository users;

    /**
     * 不是任何真实账号的 actor。
     *
     * <p>id 用 9999（远离自增区间）以免哪天恰好撞上一个真实账号。
     * 它自己是「启用管理员」，所以 {@code assertNotSelf} 不会命中它 ——
     * 这正是本类要的：让执行流走到 {@code guardLastEnabledAdmin}。
     */
    private static final LocalUserRow GHOST =
            new LocalUserRow(9999L, "ghost", "幽灵", null, 1, true, null, null);

    @Test
    void lastEnabledAdminCannotBeDisabledDemotedOrDeleted() {
        long enabledAdmins = users.countEnabledAdmins();
        assertEquals(1, enabledAdmins,
                "前提：本库里应恰好只有一个可用管理员（种子 admin）。"
                        + "这个数不是 1 说明有别的用例往这个库写了管理员，本类的前提失效了。");

        LocalUserRow seed = users.findByUsername("admin").orElseThrow();
        assertTrue(seed.enabled() && seed.isAdmin(), "前提：种子 admin 应当是启用的管理员");

        // 停用
        assertEquals(409, codeOf(() -> service.setStatus(seed.id(), 0, GHOST)),
                "把最后一个可用管理员停用了 —— 本部署将再也无法登录");

        // 降级
        assertEquals(409, codeOf(() -> service.update(seed.id(),
                        new LocalUserRequest("admin", "管理员", null, false, null), GHOST)),
                "把最后一个可用管理员降级了");

        // 删除
        assertEquals(409, codeOf(() -> service.delete(seed.id(), GHOST)),
                "把最后一个可用管理员删除了");

        // 三次拒绝之后必须毫发无损
        LocalUserRow after = users.findById(seed.id()).orElseThrow();
        assertTrue(after.enabled(), "种子 admin 被误停用");
        assertTrue(after.isAdmin(), "种子 admin 被误降级");
        assertEquals(1, users.countEnabledAdmins(), "可用管理员数量被改动了");
    }

    /** 反向：目标<b>不是</b>启用中的管理员时，同一个 guard 不该误伤。 */
    @Test
    void disablingAPlainMemberIsAllowedEvenAsTheOnlyAdmin() {
        long id = users.insertUser("plain-member", "普通成员",
                "$2a$10$....................................................", false);

        // GHOST 不是真实账号，但这里的目标是普通用户 —— guard 只看目标，
        // 对普通用户做任何事都不会减少可用管理员的数量，所以必须放行。
        service.setStatus(id, 0, GHOST);
        assertEquals(0, users.findById(id).orElseThrow().status());
    }

    private static int codeOf(org.junit.jupiter.api.function.Executable action) {
        ResponseStatusException e = assertThrows(ResponseStatusException.class, action);
        return e.getStatusCode().value();
    }
}
