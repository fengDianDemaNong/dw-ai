package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.LineageTableRow;

/**
 * 血缘目录里的一张表。
 *
 * <p>描述属性（中文名/备注/表类型）自 1.0.5 起就存在 {@code lineage_table} 上，
 * 由保存血缘时从解析所用的元数据来源快照而来，<b>不再关联 {@code meta_table} 取</b>。
 *
 * <p>为什么不关联：两侧的数据目录段天然对不上 —— 元数据从 Gravitino / dbx 同步过来，
 * 挂在源端的 catalog 下（{@code hive_prod.ods.orders}）；而人写 SQL 基本不写三段，
 * 解析后按默认目录补全（{@code default.ods.orders}）。按 {@code fullName} 关联
 * 永远匹配不上，页面上就一直是空的。
 *
 * @param metaTableId 元数据侧的表 id，供页面跳转过去查看。
 *                    <b>只有表详情接口会填</b>：列表和搜索为此加一个宽 join 不划算，
 *                    而详情页是单表点查，代价可以忽略。为 null 表示元数据目录里没有这张表
 */
public record CatalogTableResponse(long id,
                                   String catalogName,
                                   String schemaName,
                                   String tableName,
                                   String fullName,
                                   String dbType,
                                   Long metaTableId,
                                   String tableType,
                                   String comment,
                                   String remark) {

    /** 列表与搜索用：不带元数据 id。 */
    public static CatalogTableResponse of(LineageTableRow table) {
        return of(table, null);
    }

    /** 详情用：额外带上元数据侧的 id。 */
    public static CatalogTableResponse of(LineageTableRow table, Long metaTableId) {
        return new CatalogTableResponse(
                table.id(), table.catalogName(), table.schemaName(), table.tableName(),
                table.fullName(), table.dbType(),
                metaTableId,
                table.tableType(), table.comment(), table.remark());
    }
}
