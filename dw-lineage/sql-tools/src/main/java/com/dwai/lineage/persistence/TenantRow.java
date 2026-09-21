package com.dwai.lineage.persistence;

import java.time.LocalDateTime;

/**
 * {@code tenant} 表的一行。
 *
 * @param code   租户编码，全局唯一
 * @param status 1-启用 0-停用
 */
public record TenantRow(
        long id,
        String code,
        String name,
        int status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public boolean enabled() {
        return status == 1;
    }
}
