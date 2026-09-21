package com.dwai.lineage.persistence;

import java.time.LocalDateTime;

/**
 * {@code project} 表的一行。
 *
 * @param code   项目编码，租户内唯一
 * @param status 1-启用 0-停用
 */
public record ProjectRow(
        long id,
        long tenantId,
        String code,
        String name,
        String description,
        int status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public boolean enabled() {
        return status == 1;
    }
}
