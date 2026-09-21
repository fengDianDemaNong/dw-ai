package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.MetadataSourceRow;

import java.time.LocalDateTime;

/**
 * 元数据服务配置的对外表示。
 *
 * <p>刻意<b>没有</b> credential 字段：凭据在库中是密文，且任何情况下都不回传明文。
 * 页面只需要知道「有没有配过」，即 {@link #credentialConfigured()}。
 *
 * @param applicableScope 适用范围提示，直接显示在页面上，避免用户对 Hive 表选了 dbx
 *                        却拿不到分区信息
 */
public record MetadataSourceResponse(
        long id,
        String name,
        String type,
        String baseUrl,
        boolean credentialConfigured,
        String extraConfig,
        int priority,
        boolean enabled,
        String applicableScope,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static MetadataSourceResponse from(MetadataSourceRow row) {
        return new MetadataSourceResponse(
                row.id(),
                row.name(),
                row.type().name(),
                row.baseUrl(),
                row.hasCredential(),
                row.extraConfig(),
                row.priority(),
                row.enabled(),
                scopeOf(row),
                row.createdAt(),
                row.updatedAt());
    }

    private static String scopeOf(MetadataSourceRow row) {
        return switch (row.type()) {
            case GRAVITINO -> "直连 HMS，可获取分区列，适合 Hive / Spark / Iceberg 等分区表场景";
            case DBX -> "通用数据库管理工具，OLTP 与 OLAP 都覆盖，原生 + Agent 驱动支持 80+ 种数据库"
                    + "（含 Hive / Trino / Databricks 等）；其 schema 接口不返回分区列，"
                    + "分区表建议用 Gravitino 交叉验证";
            case CATALOG -> "我们自己维护的本地元数据目录，可由建表语句导入或从 Gravitino / dbx 同步；"
                    + "不走网络，也不受外部服务可用性影响";
        };
    }
}
