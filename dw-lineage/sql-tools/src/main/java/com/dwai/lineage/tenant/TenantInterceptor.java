package com.dwai.lineage.tenant;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.dwai.lineage.persistence.ProjectRow;
import com.dwai.lineage.persistence.TenantAdminRepository;
import com.dwai.lineage.persistence.TenantRow;
import com.dwai.lineage.service.TenantAdminService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 在 HTTP 层解析租户上下文，塞进 {@link TenantContextHolder}。
 *
 * <p>取值来源为请求头 {@code X-Tenant-Id} / {@code X-Project-Id}。
 * 数字按本地 id 用；非数字按 {@code tenant.code} / {@code project.code} 解析
 * （组织平台的 {@code qihang} / {@code default}）。编码尚未落户时按组织侧
 * 同步逻辑补齐，不静默回落到默认租户（避免把启航的数据写进星河）。
 */
@Component
public class TenantInterceptor implements HandlerInterceptor {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String PROJECT_HEADER = "X-Project-Id";

    private final long defaultTenantId;
    private final long defaultProjectId;
    private final TenantAdminRepository repository;
    private final ObjectProvider<TenantAdminService> tenants;

    public TenantInterceptor(
            @Value("${tenant.default-tenant-id:1}") long defaultTenantId,
            @Value("${tenant.default-project-id:1}") long defaultProjectId,
            TenantAdminRepository repository,
            ObjectProvider<TenantAdminService> tenants) {
        this.defaultTenantId = defaultTenantId;
        this.defaultProjectId = defaultProjectId;
        this.repository = repository;
        this.tenants = tenants;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        long tenantId = resolveTenant(request);
        long projectId = resolveProject(request, tenantId);
        TenantContextHolder.set(new LineageContext(tenantId, projectId));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantContextHolder.clear();
    }

    private long resolveTenant(HttpServletRequest request) {
        String raw = firstHeader(request, "X-Tenant-Code", TENANT_HEADER);
        if (raw == null || raw.isBlank()) return defaultTenantId;
        String v = raw.trim();
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return repository.findTenantByCode(v)
                    .map(TenantRow::id)
                    .orElseGet(() -> provisionOrg(v, request));
        }
    }

    private long resolveProject(HttpServletRequest request, long tenantId) {
        String raw = firstHeader(request, "X-Project-Code", PROJECT_HEADER);
        if (raw == null || raw.isBlank()) return defaultProjectId;
        String v = raw.trim();
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return repository.findProjectByCode(tenantId, v)
                    .map(ProjectRow::id)
                    .orElseGet(() -> {
                        String tenantCode = repository.findTenant(tenantId)
                                .map(TenantRow::code)
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "请求头 " + TENANT_HEADER + " 对不上租户: " + tenantId));
                        tenants.getObject().upsertFromOrg(v, tenantCode, v, v);
                        return repository.findProjectByCode(tenantId, v)
                                .map(ProjectRow::id)
                                .orElseThrow(() -> new IllegalArgumentException(
                                        "无法同步组织项目编码: " + v));
                    });
        }
    }

    /** 组织侧 code 首次打进来时落户，与 {@code /internal/v1/projects} 同一套。 */
    private long provisionOrg(String tenantCode, HttpServletRequest request) {
        String projectCode = firstHeader(request, "X-Project-Code", PROJECT_HEADER);
        if (projectCode == null || projectCode.isBlank() || isNumeric(projectCode.trim())) {
            projectCode = "default";
        } else {
            projectCode = projectCode.trim();
        }
        tenants.getObject().upsertFromOrg(projectCode, tenantCode, projectCode, projectCode);
        return repository.findTenantByCode(tenantCode)
                .map(TenantRow::id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "无法同步组织租户编码: " + tenantCode));
    }

    private static boolean isNumeric(String v) {
        try {
            Long.parseLong(v);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String firstHeader(HttpServletRequest request, String... names) {
        for (String name : names) {
            String v = request.getHeader(name);
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
