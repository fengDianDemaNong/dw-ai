package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.MetaColumnRow;

/**
 * 元数据目录里的一个字段。
 *
 * @param partition 是否为分区列。dbx 同步来的一律为 false —— 它的 schema 接口不返回分区信息
 */
public record MetaColumnResponse(long id,
                                 long tableId,
                                 String columnName,
                                 String dataType,
                                 String comment,
                                 String remark,
                                 int ordinal,
                                 boolean partition,
                                 boolean nullable,
                                 boolean primary,
                                 String source) {

    public static MetaColumnResponse from(MetaColumnRow row) {
        return new MetaColumnResponse(row.id(), row.tableId(), row.columnName(), row.dataType(),
                row.comment(), row.remark(), row.ordinal(), row.partition(), row.nullable(),
                row.primary(), row.source().name());
    }
}
