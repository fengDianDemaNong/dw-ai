package com.dwai.lineage.persistence;

import java.time.LocalDateTime;

/**
 * 血缘目录中的一张表。
 *
 * <p>累积保存，不随版本切分 —— 解析过的表会一直留在目录里，
 * 不会因为后来解析了别的表就消失。
 *
 * <h2>描述属性为什么存在这里</h2>
 * 中文名 / 备注 / 表类型原本只在元数据侧的 {@code meta_table} 维护，
 * 展示时按 {@code fullName} 左关联带出来。但两侧的<b>数据目录段天然对不上</b>：
 * 元数据从 Gravitino / dbx 同步过来，挂在源端的 catalog 下
 * （{@code hive_prod.ods.orders}）；而人写 SQL 基本不写三段，
 * 解析后按默认目录补全（{@code default.ods.orders}）。
 * 关联永远匹配不上，页面上就一直取不到中文名。
 *
 * <p>1.0.5 起改为<b>保存血缘时从解析所用的那个元数据来源快照一份</b>，
 * 血缘行自带描述，不再依赖跨表关联。代价是元数据侧后来改了中文名，
 * 这里不会自动跟着变 —— 重新解析保存一次即可刷新。
 *
 * @param tableType 表类型。只有本地元数据目录有这个概念，远程来源为空
 * @param comment   表中文名
 * @param remark    备注
 */
public record LineageTableRow(long id,
                              long tenantId,
                              long projectId,
                              String catalogName,
                              String schemaName,
                              String tableName,
                              String fullName,
                              String dbType,
                              boolean temp,
                              String tableType,
                              String comment,
                              String remark,
                              LocalDateTime createdAt,
                              LocalDateTime updatedAt) {
}
