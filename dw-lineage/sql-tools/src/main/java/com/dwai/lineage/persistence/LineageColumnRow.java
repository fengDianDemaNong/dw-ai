package com.dwai.lineage.persistence;

import java.time.LocalDateTime;

/**
 * 血缘目录中的一个字段。
 *
 * <p>与 {@link LineageTableRow} 同理：累积保存，描述属性在 1.0.5 起
 * 由保存血缘时从元数据来源快照下来，不再靠关联 {@code meta_column} 取。
 * 原因见 {@link LineageTableRow} 的类注释。
 *
 * @param dataType 字段类型原文。<b>派生列必然为空</b> —— {@code sum(amount) as s}
 *                 这种列源表里根本没有，类型不存在，不要去猜
 * @param comment  字段中文名
 * @param remark   备注
 */
public record LineageColumnRow(long id,
                               long tenantId,
                               long projectId,
                               long tableId,
                               String columnName,
                               String fullName,
                               int ordinal,
                               boolean partition,
                               String dataType,
                               String comment,
                               String remark,
                               LocalDateTime createdAt,
                               LocalDateTime updatedAt) {
}
