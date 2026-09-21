package com.dwai.platform.auth;

import com.dwai.platform.DwaiProperties;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Date;

@Component
public class JwtIssuer {
  private final DwaiProperties props;
  private final JwtSessionEpoch epoch;

  public JwtIssuer(DwaiProperties props, JwtSessionEpoch epoch) {
    this.props = props;
    this.epoch = epoch;
  }

  /** JWT 只认人，不把当前租户当 token 真源。 */
  public String issue(String userId, String username, String displayName, boolean platformAdmin) {
    Instant now = Instant.now();
    JWTClaimsSet claims = new JWTClaimsSet.Builder()
        .subject(userId)
        .claim("username", username)
        .claim("name", displayName)
        .claim("platform_admin", platformAdmin)
        .claim(JwtSessionEpoch.CLAIM, epoch.id())
        .issueTime(Date.from(now))
        .expirationTime(Date.from(now.plusSeconds(props.getSecurity().getJwtTtlSeconds())))
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
