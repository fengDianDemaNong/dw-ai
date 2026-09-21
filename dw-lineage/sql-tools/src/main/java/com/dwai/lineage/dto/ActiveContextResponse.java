package com.dwai.lineage.dto;

/**
 * 当前请求实际生效的租户与项目。
 *
 * <p>前端启动时调一次，用途有两个：确认自己发的 {@code X-Tenant-Id} / {@code X-Project-Id}
 * 确实被后端采纳（而不是悄悄落回默认租户），以及拿到名称显示在切换器上。
 *
 * <p>{@code tenantExists} / {@code projectExists} 为 false 表示请求头里的 id 在库中没有
 * 对应记录 —— 当前版本的拦截器不做存在性校验（没有登录体系，见 {@code TenantInterceptor}
 * 的说明），所以这种情况是可能出现的，由前端负责回落到默认租户。
 */
public record ActiveContextResponse(
        long tenantId,
        long projectId,
        String tenantName,
        String projectName,
        boolean tenantExists,
        boolean projectExists,
        boolean tenantEnabled,
        boolean projectEnabled) {
}
