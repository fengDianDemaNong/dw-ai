package com.dwai.lineage.persistence;

import java.time.LocalDateTime;

/**
 * 本地元数据的一个数据目录。
 *
 * <p>每个项目<b>必须有且只有一个</b>默认目录：贴建表语句导入时若没指定目录，就落到它。
 * 这样本地元数据里不存在「无数据目录」的表，全名恒为三段 {@code catalog.schema.table}。
 */
public record DataCatalogRow(long id,
                             long tenantId,
                             long projectId,
                             String name,
                             boolean isDefault,
                             String description,
                             LocalDateTime createdAt,
                             LocalDateTime updatedAt) {

    public static DataCatalogRow of(String name, boolean isDefault, String description) {
        return new DataCatalogRow(0, 0, 0, name, isDefault, description, null, null);
    }
}
