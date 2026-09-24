package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.TenantRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户，内嵌其下的项目列表。
 *
 * <p>内嵌而不是让前端再逐个租户请求一次：切换器要的就是完整的两级级联数据，
 * 一次拿全省掉 N+1。管理页需要单独刷新某个租户的项目时另有
 * {@code GET /api/tenants/{id}/projects}。
 */
public record TenantResponse(
        long id,
        String code,
        String name,
        int status,
        boolean enabled,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        List<ProjectResponse> projects) {

    public static TenantResponse from(TenantRow row, List<ProjectResponse> projects) {
        return new TenantResponse(row.id(), row.code(), row.name(), row.status(), row.enabled(),
                row.createdAt(), row.updatedAt(), projects);
    }
}
