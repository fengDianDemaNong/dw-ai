package com.dwai.lineage.auth;

import com.dwai.lineage.dto.LocalUserRequest;
import com.dwai.lineage.persistence.LocalUserRepository;
import com.dwai.lineage.persistence.LocalUserRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * 本地账号管理（standard 模式）。
 *
 * <p>承载的全部是<b>业务规则</b>，不是 CRUD 转发。这些规则每一条都对应一种
 * 「看起来很正常的操作，结果把自己关在门外」的场景，所以集中在这里并逐条注释，
 * 而不是散落在 controller 里。
 *
 * <h2>规则清单，以及各自实际拦住的是什么</h2>
 *
 * <ol>
 *   <li><b>不允许对自己停用 / 降级 / 删除</b>（{@link #assertNotSelf}）——
 *       这是<b>实际生效</b>的防锁死机制，也是唯一会通过 HTTP 触发的那个。
 *       推理见下。</li>
 *   <li><b>不允许零个可用管理员</b>（{@link #guardLastEnabledAdmin}）——
 *       这是一条<b>不变量兜底</b>，在当前 HTTP 路径下不可达（见下）。
 *       保留它是因为「可达性」依赖于调用方一定先做了管理员校验，
 *       而这个前提一旦被放松（新增非 HTTP 调用方、服务间调用、测试直接调 service），
 *       它就是最后一道闸。便宜的保险，但要清楚它现在不是主力。</li>
 *   <li><b>停用 / 删除立刻撤销刷新令牌</b> —— 否则被停用的人还能用 refresh
 *       一直换出新 access，停用形同虚设（access 只有 15 分钟，refresh 才是长命的那条）。</li>
 *   <li><b>重置密码也撤销刷新令牌</b> —— 「怀疑密码泄露」时管理员重置密码是止血动作，
 *       如果攻击者手上的 refresh 还能用，血就没止住。</li>
 *   <li><b>用户名唯一</b> —— 靠库的唯一约束兜底，这里先查一次给出可读的 409。</li>
 * </ol>
 *
 * <h2>为什么第 1 条才是主力，第 2 条打不到</h2>
 *
 * <p>{@code requireAdmin()} 保证调用方 actor 是<b>启用的管理员</b>。于是：
 *
 * <pre>
 *   若 target 也是「启用的管理员」，且 target != actor
 *     → 库里至少有 actor 和 target 两个启用的管理员 → countEnabledAdmins() &gt;= 2
 *     → guardLastEnabledAdmin 的条件（&lt;= 1）永远不成立
 *   若 target == actor
 *     → 先被 assertNotSelf 拦下，根本走不到 guard
 * </pre>
 *
 * <p>换句话说：**「不能停用自己」这条规则，恰好也就是「不会把可用管理员清零」**。
 * 这不是巧合 —— 在一个「只有管理员能管账号」的系统里，能让管理员归零的人只可能是
 * 管理员自己。认清这一点比堆两条看起来都很重要的检查更有价值：
 * 它告诉我们真正的风险面是「自己操作自己」，而不是「两个人互相删」。
 *
 * <h2>为什么没有角色</h2>
 *
 * <p>权限只有「管理员 / 普通用户」两档，见 {@link LocalUserRow} 的说明。
 * 普通用户可以改自己的显示名和密码（走 {@code /api/auth/*}），但不能碰别人的账号。
 */
@Service
public class LocalUserService {

    private final LocalUserRepository users;
    private final PasswordEncoder passwords;

    public LocalUserService(LocalUserRepository users, PasswordEncoder passwords) {
        this.users = users;
        this.passwords = passwords;
    }

    public List<LocalUserRow> list() {
        return users.listUsers();
    }

    public LocalUserRow get(long id) {
        return users.findById(id).orElseThrow(() -> notFound(id));
    }

    @Transactional
    public LocalUserRow create(LocalUserRequest req) {
        String username = req.username().trim();
        if (users.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名 " + username + " 已存在");
        }
        String hash = passwords.encode(req.password());
        long id = users.insertUser(username, req.displayName().trim(), hash, req.adminOrDefault());
        return get(id);
    }

    /**
     * 改显示名与管理员身份。
     *
     * <p>{@code username} 与 {@code password} 被忽略（见 {@link LocalUserRequest} 的说明）。
     * 降级管理员要过「不能零个可用管理员」这一关。
     */
    @Transactional
    public LocalUserRow update(long id, LocalUserRequest req, LocalUserRow actor) {
        LocalUserRow target = get(id);

        if (req.admin() != null && !req.admin().equals(target.isAdmin())) {
            if (!req.admin()) {
                assertNotSelf(actor, target, "撤销自己的管理员身份");
                guardLastEnabledAdmin(target, "撤销");
            }
            users.updateAdmin(id, req.admin());
        }

        if (req.displayName() != null && !req.displayName().isBlank()) {
            users.rename(id, req.displayName().trim());
        }

        // status 走本接口也接受，但与专用端点共用同一套检查
        if (req.status() != null && req.statusOrDefault() != target.status()) {
            applyStatus(id, target, req.statusOrDefault(), actor);
        }

        return get(id);
    }

    /** 启用 / 停用。停用会立刻撤销该账号的刷新令牌。 */
    @Transactional
    public LocalUserRow setStatus(long id, int status, LocalUserRow actor) {
        LocalUserRow target = get(id);
        applyStatus(id, target, status, actor);
        return get(id);
    }

    /**
     * 管理员给别人重置密码。
     *
     * <p>不需要旧密码（管理员本来也不知道），所以这条路径<b>只靠调用方做了管理员校验</b>
     * —— 见 {@code LocalUserController} 上的 {@code CurrentLocalUser.requireAdmin()}。
     * 少那一步就是一个「任何登录用户都能改任何人密码」的洞。
     */
    @Transactional
    public void resetPassword(long id, String newPassword) {
        LocalUserRow target = get(id);
        users.changePassword(target.id(), passwords.encode(newPassword));
        // 密码变了，之前发出去的 refresh 全部作废 —— 这正是重置密码作为止血手段的意义
        users.deleteRefreshTokensByUser(target.id());
    }

    @Transactional
    public void delete(long id, LocalUserRow actor) {
        LocalUserRow target = get(id);
        assertNotSelf(actor, target, "删除自己");
        guardLastEnabledAdmin(target, "删除");
        users.deleteRefreshTokensByUser(id);
        users.deleteUser(id);
    }

    // ------------------------------------------------------------------

    private void applyStatus(long id, LocalUserRow target, int status, LocalUserRow actor) {
        if (status == target.status()) {
            return;
        }
        if (status == 0) {
            assertNotSelf(actor, target, "停用自己");
            guardLastEnabledAdmin(target, "停用");
        }
        users.updateStatus(id, status);
        if (status == 0) {
            // 立刻撤销，否则被停用的人还能靠 refresh 换新 access，停用要等 15 分钟才生效
            users.deleteRefreshTokensByUser(id);
        }
    }

    /**
     * 拒绝「对自己做危险操作」。这是防锁死的<b>实际主力</b>，见类注释的推理。
     *
     * <p>{@code actor.id() == 0} 表示 standalone 的占位主体（没有真实账号），
     * 不可能与任何真实账号相等，所以这里不必特判模式。
     */
    private static void assertNotSelf(LocalUserRow actor, LocalUserRow target, String action) {
        if (actor != null && actor.id() != 0 && actor.id() == target.id()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "不能" + action + "。请让另一个管理员操作 —— "
                            + "这个操作会立即让你自己的会话失效。");
        }
    }

    /**
     * 不变量兜底：任何改变都不能让「可用管理员」归零。
     *
     * <p><b>当前 HTTP 路径下不可达</b>（证明见类注释）—— 它拦住的是「调用方没有先做
     * 管理员校验」的情况。保留它是因为可达性依赖那个前提；一旦新增非 HTTP 调用方、
     * 或有人直接注入本服务，它就是最后一道闸。
     *
     * <p>只在目标<b>当前</b>是「启用中的管理员」时才需要担心 —— 对一个普通用户
     * 或一个已停用的管理员做任何事都不会减少可用管理员的数量。
     */
    private void guardLastEnabledAdmin(LocalUserRow target, String action) {
        boolean targetIsEnabledAdmin = target.enabled() && target.isAdmin();
        if (targetIsEnabledAdmin && users.countEnabledAdmins() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "不能" + action + "最后一个可用的管理员 —— "
                            + "本部署将再也无法登录（只能直接改数据库）。"
                            + "请先创建或启用另一个管理员。");
        }
    }

    private static ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在：" + id);
    }
}
