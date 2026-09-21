package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.MetaSource;

import java.time.LocalDateTime;

/**
 * 元数据目录里的一张表。
 *
 * <p>这是「数据库里真实存在的表结构」，与血缘侧的 {@link LineageTableRow} <b>分开存储</b>：
 * 血缘是从 SQL 推导出来的，可能含临时表和推断出来的列，
 * 让它写进元数据会让下一次解析把猜测当事实。
 *
 * @param comment  中文名
 * @param source   这行结构的来源，{@link MetaSource#MANUAL} 的同步时受保护
 * @param sourceId 来自哪条 {@code metadata_source}；DDL / 手工录入时为 null
 * @param syncedAt 最近一次从外部同步的时间
 */
public record MetaTableRow(long id,
                           long tenantId,
                           long projectId,
                           String catalogName,
                           String schemaName,
                           String tableName,
                           String fullName,
                           String tableType,
                           String comment,
                           String remark,
                           String dbType,
                           MetaSource source,
                           Long sourceId,
                           LocalDateTime syncedAt,
                           LocalDateTime createdAt,
                           LocalDateTime updatedAt) {

    /**
     * 新建时用的构造：id 与时间戳由数据库生成。
     *
     * <p>{@code catalogName} 允许为 null —— 贴建表语句、单库 dbx 这类来源没有数据目录概念。
     * 但如果它有值，就必须同时体现在 {@code fullName} 里（见 {@code MetaNames.tableFullName}），
     * 否则两个数据目录下的同名表会撞唯一键。
     */
    public static MetaTableRow of(String catalogName, String schemaName, String tableName,
                                  String fullName, String tableType, String comment, String remark,
                                  String dbType, MetaSource source, Long sourceId) {
        return new MetaTableRow(0, 0, 0, catalogName, schemaName, tableName, fullName,
                tableType, comment, remark, dbType, source, sourceId, null, null, null);
    }

}
