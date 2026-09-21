package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.LineageVersionRow;

import java.time.LocalDateTime;

/**
 * 一张表的一个血缘版本，供页面上的版本下拉使用。
 *
 * @param sqlHash 原始 SQL 的哈希，用来一眼看出两个版本是不是同一段 SQL 存了两次
 */
public record LineageVersionResponse(long id,
                                     long targetTableId,
                                     int versionNo,
                                     String dbType,
                                     String sqlHash,
                                     boolean current,
                                     int statTables,
                                     int statColumns,
                                     int statEdges,
                                     LocalDateTime createdAt) {

    public static LineageVersionResponse from(LineageVersionRow row) {
        return new LineageVersionResponse(row.id(), row.targetTableId(), row.versionNo(),
                row.dbType(), row.sqlHash(), row.isCurrent(),
                row.statTables(), row.statColumns(), row.statEdges(), row.createdAt());
    }
}
