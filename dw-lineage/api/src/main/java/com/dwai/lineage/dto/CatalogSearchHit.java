package com.dwai.lineage.dto;

/**
 * 全局搜索的一条命中。
 *
 * <p>扁平结构：命中表信息时 {@code columnName} 为空，命中字段时才有 ——
 * 前端按「同一张表跨行合并」的方式展示，与参照项目 mdm 的搜索页一致。
 *
 * @param tableId     血缘侧的表 id，供「查看」跳转到表基础信息页
 * @param catalogName 数据目录，没有归属时为 null —— 两个目录下可能有同名表，
 *                    结果里不带它就分不清命中的是哪一张
 */
public record CatalogSearchHit(long tableId,
                               String catalogName,
                               String schemaName,
                               String tableName,
                               String fullName,
                               String tableType,
                               String tableComment,
                               String remark,
                               Long columnId,
                               String columnName,
                               String columnComment) {
}
