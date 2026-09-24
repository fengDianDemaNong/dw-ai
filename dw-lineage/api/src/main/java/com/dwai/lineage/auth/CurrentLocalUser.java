package com.dwai.lineage.auth;

import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.persistence.LocalUserRepository;
import com.dwai.lineage.persistence.LocalUserRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 「当前登录者是谁」的唯一解析入口。
 *
 * <p>从 {@code SecurityContextHolder} 取已经过解码器校验的 JWT，按 {@code sub}
 * 回查账号。抽成一个组件是因为它被两个 controller 用到（{@code LocalAuthController}
 * 的 /me、{@code LocalUserController} 的管理面），而**这类身份解析最怕两份实现** ——
 * 一旦有人在新端点上少判一步（比如漏了「账号已被停用」），就会出现一条
 * 「令牌还有效但人已经被踢了」的旁路。
 *
 * <h2>为什么每次都要回查数据库</h2>
 *
 * <p>JWT 是无状态的，令牌里的 {@code username} / {@code name} 是**签发那一刻**的快照。
 * 如果直接信令牌，那么「停用某个账号」在它的 access TTL（15 分钟）内完全不生效，
 * 改显示名也要等重新登录。回查一次换来的是：
 *
 * <ul>
 *   <li>停用立即生效（不必等令牌过期）；</li>
 *   <li>账号被删后令牌立刻作废（{@code findById} 查不到）；</li>
 *   <li>显示名、管理员身份都取到最新值 —— 尤其 <b>{@code isAdmin} 必须以库为准</b>，
 *       否则被撤销管理员的人还能用旧令牌继续管账号。</li>
 * </ul>
 *
 * <p>代价是每个已认证请求多一次主键查询。这是本地部署，完全可接受；
 * 若将来成为瓶颈，正确的优化是加一层短 TTL 缓存并在写路径失效它，
 * 而不是退回「信令牌」。
 */
@Component
public class CurrentLocalUser {

    private final LocalAuthService auth;
    private final LocalUserRepository users;
    private final LineageProperties props;

    public CurrentLocalUser(LocalAuthService auth, LocalUserRepository users, LineageProperties props) {
        this.auth = auth;
        this.users = users;
        this.props = props;
    }

    /**
     * 取当前账号；未登录、账号已删、账号已停用都抛 401。
     *
     * <p>账号被停用时按<b>未登录</b>处理（401）而不是 403：对调用方来说
     * 「你的凭据不再有效」比「你没有权限」更准确，前端也能统一走「回登录页」那条分支 ——
     * 403 的分支是「提示无权限」，停用的人在那个分支里会一直卡着。
     */
    public LocalUserRow require() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof Jwt jwt)) {
            throw notLoggedIn();
        }
        LocalUserRow user = auth.currentUser(jwt.getSubject());
        if (!user.enabled()) {
            throw notLoggedIn();
        }
        return user;
    }

    /**
     * 取当前令牌的 {@code sub}，<b>不回查本地账号</b>。
     *
     * <p>给跨服务鉴权用。multi 下的令牌由组织平台签发，{@code sub} 就是组织侧的用户标识 ——
     * 那正是组织 {@code authz/check} 要的 userId。此时<b>不能</b>走 {@link #require()}：
     * 它接着拿 sub 去查本模块的 {@code local_user} 表（见 {@link #require()} 的
     * {@code auth.currentUser}），而 multi 下账号在组织那边、本地这张表里没有这个人，
     * 必然查不到而误判成未登录。
     *
     * <p>它只做「令牌解出来了没有」这一件事，不代表任何权限 —— 判权在组织那边。
     */
    public String requireSubject() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof Jwt jwt)) {
            throw notLoggedIn();
        }
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw notLoggedIn();
        }
        return sub;
    }

    /**
     * 取当前账号并要求它是管理员。
     *
     * <p>standalone 直接放行：那个模式没有账号体系，也就没有人可以是管理员 ——
     * 报 403 会让「独立部署也能用租户管理页」这条既有能力消失。
     * 这与 {@code SecurityConfig} 里「standalone 全放行」是同一个取舍，
     * 区别只是这里是方法级判断，管不到路径匹配。
     */
    public LocalUserRow requireAdmin() {
        if (props.isStandalone()) {
            // 没有登录态，也没有「管理员」可言。返回一个占位行供审计字段使用。
            return PLACEHOLDER_STANDALONE;
        }
        LocalUserRow user = require();
        if (!user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
        }
        return user;
    }

    /** multi 下账号由组织管，本模块既没有 users 表内容也不该暴露管理面。 */
    public boolean accountsManageableLocally() {
        return props.isStandard();
    }

    /**
     * standalone 下的占位主体。
     *
     * <p>它的 id 是 0 —— 不可能与真实账号（自增从 1 开始）冲突，所以任何
     * 「不能停用自己」「不能删掉自己」的判断在 standalone 下都不会误命中。
     */
    private static final LocalUserRow PLACEHOLDER_STANDALONE = new LocalUserRow(
            0L, "standalone", "独立模式", null, 1, true, null, null);

    private static ResponseStatusException notLoggedIn() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
    }
}
