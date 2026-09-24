package com.dwai.lineage.persistence;

import java.time.LocalDateTime;

/** 刷新令牌的库内记录。只存 SHA-256，明文令牌不落库。 */
public record RefreshTokenRow(
        String id,
        long userId,
        String tokenHash,
        LocalDateTime expiresAt,
        LocalDateTime createdAt) {
}
