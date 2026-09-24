package com.dwai.lineage.service.metadata.provider;

/**
 * 一个字段的描述属性快照。
 *
 * <p>与 {@link TableDescriptor} 一起，从解析当时用的那个元数据来源带出来，
 * 随血缘落库。
 *
 * <p><b>有一类字段注定拿不到</b>：{@code sum(amount) as s}、{@code case when ... end}
 * 这种派生列，源表里根本没有对应的列，类型和中文名都不存在。这不是没接上，
 * 是本来就没有 —— 留空即可，不要去猜。
 *
 * @param dataType 字段类型原文，如 {@code decimal(12,2)}
 * @param comment  字段中文名
 * @param remark   备注。只有本地元数据目录支持手工维护，远程来源留空
 */
public record ColumnDescriptor(String dataType, String comment, String remark) {

    public static ColumnDescriptor of(String dataType, String comment) {
        return new ColumnDescriptor(dataType, comment, null);
    }

    public boolean isEmpty() {
        return blank(dataType) && blank(comment) && blank(remark);
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
