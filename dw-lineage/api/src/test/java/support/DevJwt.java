package support;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;

/**
 * 造一个与组织（dw-org / dw-model）dev 模式<b>同形</b>的测试令牌。
 *
 * <p>multi 模式下数据地图要求 {@code /api/**} 带组织签发的 JWT，所以凡是覆盖 multi
 * 行为的测试都得先有这个令牌。这里刻意手写 claims，而不是复用某个内部工具：
 * 手写才等于「照着跨服务契约造」，能暴露契约本身的变化（比如某天组织改了 claim 名，
 * 这里不会被自动带上，测试会红）。
 *
 * <p>密钥与 {@code application.yml} 里 {@code security.jwt-secret} 的 dev 默认值一致。
 * 不校验世代（{@code boot}）—— 数据地图刻意不做这一项，因为令牌是组织签发的，
 * 携带的是组织那次启动的世代，拿本进程的去比只会把所有令牌判成过期。
 */
public final class DevJwt {

    /** 与三个服务 application.yml 里 jwt-secret 的 dev 默认值一致。 */
    public static final String SECRET = "dw-ai-dev-secret-change-me-please-32b";

    private DevJwt() {
    }

    public static String token(String userId) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId)
                    .claim("username", userId)
                    .claim("name", userId)
                    .claim("platform_admin", false)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(900)))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
            jwt.sign(new MACSigner(SECRET.getBytes()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("签测试令牌失败", e);
        }
    }

    /** 直接拼成 {@code Authorization} 头的值。 */
    public static String bearer() {
        return "Bearer " + token("u-test");
    }
}
