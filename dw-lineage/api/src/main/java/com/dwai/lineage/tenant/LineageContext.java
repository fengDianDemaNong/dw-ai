package com.dwai.lineage.tenant;

/**
 * 租户 + 项目上下文。所有血缘数据的读写都必须携带它。
 *
 * @param tenantId  租户 id
 * @param projectId 项目 id
 */
public record LineageContext(long tenantId, long projectId) {

    public LineageContext {
        if (tenantId <= 0 || projectId <= 0) {
            throw new IllegalArgumentException(
                    "租户与项目必须有效: tenantId=" + tenantId + ", projectId=" + projectId);
        }
    }

    @Override
    public String toString() {
        return "tenant=" + tenantId + ",project=" + projectId;
    }
}
