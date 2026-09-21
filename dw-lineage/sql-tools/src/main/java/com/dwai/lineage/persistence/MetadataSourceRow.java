package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.MetadataSourceType;

import java.time.LocalDateTime;

/**
 * {@code metadata_source} 表的一行。
 *
 * <p>{@code credential} 在库中与本对象里都是<b>密文</b>，解密只发生在真正要发起调用的那一刻
 * （见 {@code MetadataSourceService#decryptCredential}），绝不放进任何对外的 DTO。
 *
 * @param credential   AES-GCM 密文；null 表示未配置凭据
 * @param extraConfig  各类型的差异化配置，JSON 文本
 * @param priority     数字小的优先，供 CompositeMetadataProvider 串联使用
 */
public record MetadataSourceRow(
        long id,
        long tenantId,
        String name,
        MetadataSourceType type,
        String baseUrl,
        String credential,
        String extraConfig,
        int priority,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public boolean hasCredential() {
        return credential != null && !credential.isBlank();
    }
}
