package com.dwai.lineage.auth;

import com.dwai.lineage.conf.LineageProperties;
import com.dwai.lineage.persistence.LocalUserRow;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

/**
 * standard 模式本地 JWT 签发器（HS256，共享密钥）。
 *
 * <p>与 dw-model 的 {@code JwtIssuer} 同一形态；claims 只认「人」——
 * sub / username / name / 启动世代，不携带租户（租户由请求头解析，见 TenantInterceptor）。
 *
 * <p>密钥来自 {@code lineage.security.jwt-secret}。standard 模式下令牌是自己签自己验，
 * 换密钥等于全员下线（这是特性：密钥泄露后可以用它止血）。
 */
@Component
public class LocalJwtIssuer {

    private final LineageProperties props;
    private final LocalTokenEpoch epoch;

    public LocalJwtIssuer(LineageProperties props, LocalTokenEpoch epoch) {
        this.props = props;
        this.epoch = epoch;
    }

    public String issue(LocalUserRow user) {
        Instant now = Instant.now();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(String.valueOf(user.id()))
                .claim("username", user.username())
                .claim("name", user.displayName())
                .claim(LocalTokenEpoch.CLAIM, epoch.id())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(
                        props.getSecurity().getJwtTtlSeconds())))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        try {
            jwt.sign(new MACSigner(props.getSecurity().getJwtSecret().getBytes()));
        } catch (JOSEException e) {
            throw new IllegalStateException("sign jwt", e);
        }
        return jwt.serialize();
    }
}
