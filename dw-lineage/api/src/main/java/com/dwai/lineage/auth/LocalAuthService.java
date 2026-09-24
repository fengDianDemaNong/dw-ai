package com.dwai.lineage.auth;

import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.persistence.LocalUserRepository;
import com.dwai.lineage.persistence.LocalUserRow;
import com.dwai.lineage.persistence.RefreshTokenRow;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * standard 模式的本地认证：登录 / 刷新 / 登出。
 *
 * <p>流程照 dw-model 的 {@code AuthService + RefreshTokenService}，按数据地图的
 * 惯例重写（JdbcTemplate、BIGINT 自增主键）。关键语义逐一保留：
 *
 * <ul>
 *   <li><b>刷新令牌只存哈希</b> —— 库里是 SHA-256，明文只在登录/刷新响应里出现一次；
 *       库被拖走也拼不回可用的令牌。</li>
 *   <li><b>登录踢掉旧刷新令牌</b> —— 一处登录、别处下线，被盗号时是自救通道。</li>
 *   <li><b>刷新是滑动续期</b> —— 每次刷新顺延过期时间；空闲超过 refresh TTL 才真正过期。</li>
 *   <li><b>登出删库</b> —— 无状态 JWT 做不到「登出即失效」，落库就是为了能撤。</li>
 * </ul>
 *
 * <p>与 model 的一点刻意差异：这里<b>没有租户选择</b>。数据地图的 standard 模式只有
 * 默认租户（本地部署一套库一套数据），令牌只认人，租户由 TenantInterceptor 从请求头解析。
 */
@Service
public class LocalAuthService {

    private static final SecureRandom RNG = new SecureRandom();

    private final LocalUserRepository users;
    private final LocalJwtIssuer issuer;
    private final PasswordEncoder passwords;
    private final LineageProperties props;

    public LocalAuthService(
            LocalUserRepository users,
            LocalJwtIssuer issuer,
            PasswordEncoder passwords,
            LineageProperties props) {
        this.users = users;
        this.issuer = issuer;
        this.passwords = passwords;
        this.props = props;
    }

    public LoginResult login(String username, String password) {
        requireStandard();
        if (username == null || username.isBlank() || password == null) {
            throw badLogin();
        }
        LocalUserRow user = users.findByUsername(username.trim()).orElse(null);
        if (user == null || user.passwordHash() == null
                || !passwords.matches(password, user.passwordHash())) {
            throw badLogin();
        }
        if (!user.enabled()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
        }
        // 一处登录、别处下线：清掉这个账号之前的全部刷新令牌
        users.deleteRefreshTokensByUser(user.id());
        return issue(user);
    }

    public LoginResult refresh(String refreshToken) {
        requireStandard();
        if (refreshToken == null || refreshToken.isBlank()) {
            throw expired();
        }
        RefreshTokenRow row = users.findRefreshTokenByHash(hash(refreshToken)).orElse(null);
        if (row == null) {
            throw expired();
        }
        if (row.expiresAt().isBefore(LocalDateTime.now())) {
            users.deleteRefreshTokenByHash(row.tokenHash());
            throw expired();
        }
        users.deleteExpiredRefreshTokens(LocalDateTime.now());
        users.extendRefreshTokenExpiry(row.id(),
                LocalDateTime.now().plusSeconds(props.getSecurity().getRefreshTtlSeconds()));
        LocalUserRow user = users.findById(row.userId()).orElse(null);
        if (user == null || !user.enabled()) {
            users.deleteRefreshTokensByUser(row.userId());
            throw expired();
        }
        return issue(user, refreshToken);
    }

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        users.deleteRefreshTokenByHash(hash(refreshToken));
    }

    /** 按令牌里的 sub 查回当前账号。sub 不存在或账号已删时按未登录处理。 */
    public LocalUserRow currentUser(String userIdClaim) {
        if (userIdClaim == null || userIdClaim.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        long userId;
        try {
            userId = Long.parseLong(userIdClaim.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录"));
    }

    private LoginResult issue(LocalUserRow user) {
        return issue(user, newRefreshToken(user.id()));
    }

    private LoginResult issue(LocalUserRow user, String refreshToken) {
        return new LoginResult(
                issuer.issue(user),
                refreshToken,
                props.getSecurity().getJwtTtlSeconds(),
                user.id(),
                user.username(),
                user.displayName());
    }

    private String newRefreshToken(long userId) {
        byte[] buf = new byte[32];
        RNG.nextBytes(buf);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
        users.insertRefreshToken(
                "rt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                userId,
                hash(raw),
                LocalDateTime.now().plusSeconds(props.getSecurity().getRefreshTtlSeconds()));
        return raw;
    }

    private void requireStandard() {
        if (!props.isStandard()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "本地登录仅在普通模式（standard）下提供；standalone 无认证，multi 由组织统一签发");
        }
    }

    private static String hash(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException badLogin() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }

    private static ResponseStatusException expired() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期");
    }

    /** 登录/刷新的响应体。字段与 dw-model 的 LoginRes 保持同名（去掉租户选择相关字段）。 */
    public record LoginResult(
            String token,
            String refreshToken,
            long expiresIn,
            long userId,
            String username,
            String displayName) {
    }
}
