package com.dwai.lineage.dto;

/**
 * 解析失败的单条语句。
 *
 * <p>血缘解析对多语句脚本是「部分失败容忍」的：一条语句失败不影响其余语句，
 * 但失败原因必须回传给用户，否则用户只会看到一张不完整的图却不知道为什么。
 *
 * @param index   语句在本次请求中的序号，从 1 开始，便于用户在编辑器里定位
 * @param sql     失败的 SQL（过长时截断）
 * @param message 失败原因
 */
public record FailedStatement(int index, String sql, String message) {

    /** 回传给前端的 SQL 片段长度上限，避免响应体被超长语句撑爆。 */
    private static final int MAX_SQL_LENGTH = 500;

    public static FailedStatement of(int zeroBasedIndex, String sql, String message) {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.length() > MAX_SQL_LENGTH) {
            trimmed = trimmed.substring(0, MAX_SQL_LENGTH) + " ...";
        }
        return new FailedStatement(zeroBasedIndex + 1, trimmed, message);
    }
}
