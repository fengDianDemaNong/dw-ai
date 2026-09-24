package com.dwai.lineage.auth;

import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.conf.LineageProperties.Security;
import com.dwai.lineage.persistence.LocalUserRepository;
import com.dwai.lineage.persistence.LocalUserRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * standard 模式本地认证端点：{@code /api/auth/*}。
 *
 * <p>路径与 dw-model 的 {@code AuthController} 对齐（{@code /api/auth} + {@code /api/v1/auth}），
 * 前端同一套调用能打通两个服务。区别只在「没有租户相关端点」：
 * {@code /tenants}、{@code /select-tenant}、{@code /enter-tenant} 都不存在，
 * 因为数据地图 standard 下只有默认租户。
 *
 * <h2>这里只管「我自己的会话」</h2>
 *
 * <p>{@code login} / {@code refresh} / {@code logout} / {@code me} / {@code profile} /
 * {@code password} 全部作用在<b>当前登录者自己</b>身上。管理别人的账号在
 * {@link LocalUserController}（{@code /api/users}）—— 两者的授权模型不同，
 * 这里只要登录，那里必须管理员。
 *
 * <h2>哪些端点公开</h2>
 *
 * <p>{@code config} / {@code login} / {@code refresh} / {@code logout} 必须匿名可达 ——
 * 登录本身不能要求先登录（见 {@code SecurityConfig.PUBLIC_PATHS}）。
 *
 * <p>{@code me} / {@code profile} / {@code password} 反过来必须<b>已登录</b>：
 * 它们要读当前身份，而在 {@code SecurityConfig} 里 standard 模式的
 * {@code /api/**} 是 {@code authenticated()}，这三个路径没进白名单，天然被挡住。
 * 这是刻意的 —— 不留「未登录也能改密码」这种口子。
 *
 * <h2>为什么 standalone 下这些端点是 403 而不是 404</h2>
 *
 * <p>standalone 无账号体系，{@code login} 会明确回 403 并说明原因。回 404 会让人以为
 * 「是不是这个版本没带这个功能」，而 403 + 文案能直接指向「你跑的这模式本来就没有登录」。
 * 两者的前端行为也不同：404 触发「接口不存在」告警，403 可以安静地跳过登录页。
 */
@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class LocalAuthController {

    private final LocalAuthService auth;
    private final LocalUserRepository users;
    private final CurrentLocalUser current;
    private final LocalTokenEpoch epoch;
    private final LineageProperties props;
    private final PasswordEncoder passwords;

    public LocalAuthController(
            LocalAuthService auth,
            LocalUserRepository users,
            CurrentLocalUser current,
            LocalTokenEpoch epoch,
            LineageProperties props,
            PasswordEncoder passwords) {
        this.auth = auth;
        this.users = users;
        this.current = current;
        this.epoch = epoch;
        this.props = props;
        this.passwords = passwords;
    }

    /**
     * 前端启动时先拉这个：决定要不要渲染登录页、令牌过期阈值取多少。
     *
     * <p>内容与 dw-model 的 {@code /api/auth/config} 同形但做了裁剪 —— 数据地图没有
     * Casdoor、没有平台账号、没有租户选择，把这些字段留着只会让前端多写无用分支。
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Security sec = props.getSecurity();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runMode", props.runMode());
        out.put("deployMode", props.runMode());
        out.put("product", "metadata");
        out.put("mode", sec.isOidc() ? "oidc" : "dev");
        // 只有 standard 有本地登录；standalone 与 multi 都明确「不该走这条路径」
        out.put("allowLogin", props.isStandard());
        out.put("accessTtlSeconds", sec.getJwtTtlSeconds());
        out.put("refreshTtlSeconds", sec.getRefreshTtlSeconds());
        out.put("sessionEpoch", epochId());
        return out;
    }

    @PostMapping("/login")
    public LocalAuthModels.LoginRes login(@RequestBody(required = false) LocalAuthModels.LoginReq req) {
        requireStandardForLogin();
        LocalAuthService.LoginResult r = auth.login(
                req == null ? null : req.username(),
                req == null ? null : req.password());
        return toRes(r);
    }

    @PostMapping("/refresh")
    public LocalAuthModels.LoginRes refresh(@RequestBody(required = false) LocalAuthModels.RefreshReq req) {
        requireStandardForLogin();
        return toRes(auth.refresh(req == null ? null : req.refreshToken()));
    }

    /**
     * 登出：删掉库里的刷新令牌。
     *
     * <p>无状态 JWT 做不到「登出即失效」，所以这里撤的是 refresh —— 前端同时丢掉
     * access，它最多再活一个 access TTL。返回 {@code void}（200 + 空体），
     * 让前端不必判断「登出算不算成功」。
     *
     * <p>幂等：传一个不存在的令牌也回 200。登出失败还要用户处理是荒谬的。
     */
    @PostMapping("/logout")
    public void logout(@RequestBody(required = false) LocalAuthModels.RefreshReq req) {
        requireStandardForLogin();
        auth.logout(req == null ? null : req.refreshToken());
    }

    @GetMapping("/me")
    public LocalAuthModels.Me me() {
        return toMe(current.require());
    }

    /** 改显示名。 */
    @PutMapping("/profile")
    public LocalAuthModels.Me updateProfile(@RequestBody(required = false) LocalAuthModels.ProfileReq req) {
        LocalUserRow u = current.require();
        if (req == null || req.displayName() == null || req.displayName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写显示名");
        }
        LocalUserRow updated = users.rename(u.id(), req.displayName().trim());
        return toMe(updated);
    }

    /**
     * 改密码。
     *
     * <p>必须先验当前密码 —— 否则一个被盗的 access token 就能把账号锁成攻击者的。
     * 改完<b>踢掉全部刷新令牌</b>：密码变了，之前发出去的 refresh 都该作废，
     * 这是「怀疑密码泄露」时唯一的自救动作。
     *
     * <p>注意这里改的是<b>自己的</b>密码，所以要验旧密码。管理员改别人的密码是另一条
     * 路径（{@code PUT /api/users/{id}/password}），那条没有旧密码可验，
     * 靠的是调用方必须是管理员。
     */
    @PutMapping("/password")
    public void changePassword(@RequestBody(required = false) LocalAuthModels.PasswordReq req) {
        LocalUserRow u = current.require();
        if (req == null || req.currentPassword() == null
                || req.newPassword() == null || req.newPassword().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写当前密码和新密码");
        }
        if (u.passwordHash() == null || !passwords.matches(req.currentPassword(), u.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前密码不正确");
        }
        // 与 dw-model 的 MeController 一致的下限；不设强度规则是有意的 ——
        // 本地部署的账号由管理员手工发放，强制复杂度只会催生写在便签上的密码。
        if (req.newPassword().length() < 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少 4 位");
        }
        users.changePassword(u.id(), passwords.encode(req.newPassword()));
        users.deleteRefreshTokensByUser(u.id());
    }

    // ------------------------------------------------------------------

    /** standalone 无账号体系、multi 由组织签发，两条路都不该从前端拿到本地登录。 */
    private void requireStandardForLogin() {
        if (props.isStandard()) return;
        if (props.isMulti()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "多租户模式下登录由组织平台统一提供");
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "独立模式没有登录");
    }

    private String epochId() {
        // 世代 id 用于前端识别「服务重启过、手上的 access 已整体失效」。
        // 只对 standard 自己签发的令牌有意义；其它模式下回空串，前端按「没这个信息」处理。
        return props.isStandard() ? epoch.id() : "";
    }

    /**
     * {@code /me} 的响应。
     *
     * <p>带 {@code admin} 是给前端门禁用的：管理页与「账号管理」导航项的显示
     * 必须和后端的授权判断同源，否则普通用户会看到一个点进去就 403 的入口。
     */
    private static LocalAuthModels.Me toMe(LocalUserRow u) {
        return new LocalAuthModels.Me(
                u.id(), u.username(), u.displayName(), u.isAdmin(), "standard");
    }

    private static LocalAuthModels.LoginRes toRes(LocalAuthService.LoginResult r) {
        return new LocalAuthModels.LoginRes(
                r.token(), r.refreshToken(), r.expiresIn(),
                r.userId(), r.username(), r.displayName());
    }
}
