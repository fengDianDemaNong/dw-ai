package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.ProjectRow;

import java.time.LocalDateTime;

/** 项目。{@code enabled} 是 {@code status} 的布尔投影，省得前端到处写 {@code status === 1}。 */
public record ProjectResponse(
        long id,
        long tenantId,
        String code,
        String name,
        String description,
        int status,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static ProjectResponse from(ProjectRow row) {
        return new ProjectResponse(row.id(), row.tenantId(), row.code(), row.name(),
                row.description(), row.status(), row.enabled(), row.createdAt(), row.updatedAt());
    }
}
