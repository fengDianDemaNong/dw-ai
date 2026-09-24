package com.dwai.lineage.enums;

/** 临时规则作用在库名还是表名上。 */
public enum TempRuleTarget {

    /** 匹配库名（schema）。整个库都算临时，如 {@code tmp}、{@code test}。 */
    SCHEMA,

    /** 匹配表名，如 {@code tmp_*}。 */
    TABLE;

    public static TempRuleTarget fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("请指定规则作用对象: SCHEMA（库）/ TABLE（表）");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不支持的作用对象: " + value + "，可选值: SCHEMA（库）/ TABLE（表）");
        }
    }
}
