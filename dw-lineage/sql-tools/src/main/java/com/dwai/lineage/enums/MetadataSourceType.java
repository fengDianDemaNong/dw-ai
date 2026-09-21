package com.dwai.lineage.enums;

/**
 * 元数据服务类型，对应 {@code metadata_source.type}。
 *
 * <p>各有侧重，页面上会标注出来：
 * <ul>
 *   <li>{@link #GRAVITINO}：直连 HMS，<b>能拿到分区列</b>，分区表场景的首选</li>
 *   <li>{@link #DBX}：通用数据库管理工具，OLTP 与 OLAP 都覆盖
 *       （原生 + Agent 驱动 80+ 种数据库，含 Hive / Trino / Databricks 等），
 *       但实测其 schema 接口<b>不返回分区列</b></li>
 *   <li>{@link #CATALOG}：我们自己维护的本地元数据目录。不走网络、不受外部服务可用性影响，
 *       内容可以被人工修正过。作为一条内置记录参与同一套 priority 编排</li>
 * </ul>
 */
public enum MetadataSourceType {

    GRAVITINO,
    DBX,
    /** 本地元数据目录（{@code meta_table} / {@code meta_column}），内置且不可删除。 */
    CATALOG;

    public static MetadataSourceType fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("元数据服务类型不能为空");
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不支持的元数据服务类型: " + value + "，可选值: GRAVITINO / DBX / CATALOG");
        }
    }

    /** 该类型是否需要凭据。Gravitino 当前以匿名方式访问，dbx 需要登录密码，本地目录无需凭据。 */
    public boolean requiresCredential() {
        return this == DBX;
    }

    /** 是否为本地来源：不走网络，没有地址与凭据，页面上要隐藏这些字段。 */
    public boolean isLocal() {
        return this == CATALOG;
    }

    /** 内置记录不允许用户删除 —— 删了本地元数据就再也参与不了解析。 */
    public boolean isBuiltIn() {
        return this == CATALOG;
    }
}
