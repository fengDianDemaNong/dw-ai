package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.MetaSource;

import java.time.LocalDateTime;

/**
 * 元数据目录里的一个字段。
 *
 * @param comment  中文名
 * @param ordinal  表内顺序，从 1 开始
 * @param source   同步时 {@link MetaSource#MANUAL} 的 comment / remark 受保护
 */
public record MetaColumnRow(long id,
                            long tenantId,
                            long projectId,
                            long tableId,
                            String columnName,
                            String fullName,
                            String dataType,
                            String comment,
                            String remark,
                            int ordinal,
                            boolean partition,
                            boolean nullable,
                            boolean primary,
                            MetaSource source,
                            LocalDateTime createdAt,
                            LocalDateTime updatedAt) {

    /** 新建时用的构造：id、tableId 与时间戳由写入方补齐。 */
    public static MetaColumnRow of(String columnName, String dataType, String comment,
                                   int ordinal, boolean partition, boolean nullable,
                                   boolean primary, MetaSource source) {
        return new MetaColumnRow(0, 0, 0, 0, columnName, null, dataType, comment, null,
                ordinal, partition, nullable, primary, source, null, null);
    }
}
