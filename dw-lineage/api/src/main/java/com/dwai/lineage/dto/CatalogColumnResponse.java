package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.LineageColumnRow;

/**
 * 血缘目录里的一个字段。
 *
 * <p>类型与中文名自 1.0.5 起存在 {@code lineage_column} 上，保存血缘时快照而来，
 * 不再关联 {@code meta_column}。理由见 {@link CatalogTableResponse}。
 *
 * @param dataType 字段类型。<b>派生列必然为空</b> —— {@code sum(amount) as s}
 *                 这种列源表里根本没有，不是没接上
 * @param partition 是否分区字段。这一项仍来自血缘侧记录：元数据缺失时它是解析器
 *                  能给出的唯一依据
 */
public record CatalogColumnResponse(long id,
                                    long tableId,
                                    String columnName,
                                    String fullName,
                                    int ordinal,
                                    boolean partition,
                                    String dataType,
                                    String comment,
                                    String remark) {

    public static CatalogColumnResponse of(LineageColumnRow column) {
        return new CatalogColumnResponse(
                column.id(), column.tableId(), column.columnName(), column.fullName(),
                column.ordinal(), column.partition(),
                column.dataType(), column.comment(), column.remark());
    }
}
