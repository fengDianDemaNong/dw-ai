package com.dwai.lineage.enums;

/**
 * 一行元数据是怎么来的，对应 {@code meta_table.source} / {@code meta_column.source}。
 *
 * <p>{@link #MANUAL} 是特殊的：人手工改过的内容，后续从外部同步时<b>默认不覆盖</b>，
 * 否则用户刚补好的中文名会被下一次同步冲掉。确实想覆盖时在同步请求里显式声明。
 */
public enum MetaSource {

    /** 从建表语句解析而来。 */
    DDL,
    /** 从 Gravitino 同步。 */
    GRAVITINO,
    /** 从 dbx 同步。 */
    DBX,
    /** 页面上手工录入或修改。 */
    MANUAL;

    public static MetaSource fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("元数据来源不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不支持的元数据来源: " + value + "，可选值: DDL / GRAVITINO / DBX / MANUAL");
        }
    }

    /** 是否为人工维护的内容 —— 同步时要保护它。 */
    public boolean isManual() {
        return this == MANUAL;
    }
}
