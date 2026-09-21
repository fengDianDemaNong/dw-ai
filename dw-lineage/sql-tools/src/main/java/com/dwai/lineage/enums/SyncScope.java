package com.dwai.lineage.enums;

/**
 * 元数据导入的范围。
 *
 * <p>决定任务启动后怎么把「用户选的东西」展开成一批具体的表：
 * 范围越大展开越慢（几百次外部 API 往返），所以展开放在任务线程里做，
 * 而不是在提交请求时同步完成。
 */
public enum SyncScope {

    /** 整个数据目录：先列出它下面所有库，再逐库列表。 */
    CATALOG,

    /** 整个库：列出该库下所有表。 */
    SCHEMA,

    /** 用户勾选的若干张表，不需要展开。 */
    TABLE;

    public static SyncScope fromString(String value) {
        if (value == null || value.isBlank()) {
            return TABLE;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "不支持的导入范围: " + value + "，可选值: CATALOG / SCHEMA / TABLE");
        }
    }
}
