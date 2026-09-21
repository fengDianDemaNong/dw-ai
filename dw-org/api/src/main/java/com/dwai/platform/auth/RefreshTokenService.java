package com.dwai.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.entity.RefreshTokenEntity;
import com.dwai.platform.meta.mapper.RefreshTokenMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class RefreshTokenService {
  private static final SecureRandom RNG = new SecureRandom();

  private final RefreshTokenMapper tokens;
  private final DwaiProperties props;

  public RefreshTokenService(RefreshTokenMapper tokens, DwaiProperties props) {
    this.tokens = tokens;
    this.props = props;
  }

  public record Issued(String userId, String refreshToken) {}

  public String issue(String userId) {
    purgeExpired();
    String raw = randomRaw();
    RefreshTokenEntity row = new RefreshTokenEntity();
    row.setId("rt-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    row.setUserId(userId);
    row.setTokenHash(hash(raw));
    OffsetDateTime now = OffsetDateTime.now();
    row.setCreatedAt(now);
    row.setExpiresAt(now.plusSeconds(props.getSecurity().getRefreshTtlSeconds()));
    tokens.insert(row);
    return raw;
  }

  /** 校验 refresh。空闲超时由前端按最后一次操作判断；此处把 refresh 有效期顺延到「还能再闲 idle 那么久」。 */
  @Transactional
  public Issued rotate(String raw) {
    RefreshTokenEntity row = requireLive(raw);
    row.setExpiresAt(OffsetDateTime.now().plusSeconds(props.getSecurity().getRefreshTtlSeconds()));
    tokens.updateById(row);
    return new Issued(row.getUserId(), raw);
  }

  public void revoke(String raw) {
    if (raw == null || raw.isBlank()) return;
    tokens.delete(Wrappers.<RefreshTokenEntity>lambdaQuery()
        .eq(RefreshTokenEntity::getTokenHash, hash(raw)));
  }

  public void revokeAll(String userId) {
    if (userId == null || userId.isBlank()) return;
    tokens.delete(Wrappers.<RefreshTokenEntity>lambdaQuery()
        .eq(RefreshTokenEntity::getUserId, userId));
  }

  private RefreshTokenEntity requireLive(String raw) {
    if (raw == null || raw.isBlank()) throw expired();
    RefreshTokenEntity row = tokens.selectOne(Wrappers.<RefreshTokenEntity>lambdaQuery()
        .eq(RefreshTokenEntity::getTokenHash, hash(raw)));
    if (row == null) throw expired();
    if (row.getExpiresAt() == null || row.getExpiresAt().isBefore(OffsetDateTime.now())) {
      tokens.deleteById(row.getId());
      throw expired();
    }
    return row;
  }

  private void purgeExpired() {
    tokens.delete(Wrappers.<RefreshTokenEntity>lambdaQuery()
        .lt(RefreshTokenEntity::getExpiresAt, OffsetDateTime.now()));
  }

  private static String randomRaw() {
    byte[] buf = new byte[32];
    RNG.nextBytes(buf);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
  }

  private static String hash(String raw) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static ResponseStatusException expired() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期");
  }
}
