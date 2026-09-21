package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.MetaTableRow;

import java.time.LocalDateTime;

/**
 * 元数据目录里的一张表。
 *
 * @param source   DDL / GRAVITINO / DBX / MANUAL，页面上要显示出来 ——
 *                 用户得知道这份结构是哪来的，以及为什么某些表同步时被跳过
 * @param syncedAt 最近一次从外部同步的时间；DDL 导入与手工录入为 null
 */
public record MetaTableResponse(long id,
                                String catalogName,
                                String schemaName,
                                String tableName,
                                String fullName,
                                String tableType,
                                String comment,
                                String remark,
                                String dbType,
                                String source,
                                Long sourceId,
                                LocalDateTime syncedAt,
                                LocalDateTime updatedAt) {

    public static MetaTableResponse from(MetaTableRow row) {
        return new MetaTableResponse(row.id(), row.catalogName(), row.schemaName(),
                row.tableName(), row.fullName(),
                row.tableType(), row.comment(), row.remark(), row.dbType(),
                row.source().name(), row.sourceId(), row.syncedAt(), row.updatedAt());
    }
}
