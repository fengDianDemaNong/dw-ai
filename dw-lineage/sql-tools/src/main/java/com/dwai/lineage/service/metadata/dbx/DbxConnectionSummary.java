package com.dwai.lineage.service.metadata.dbx;

/**
 * dbx 已保存连接的<b>脱敏</b>摘要。
 *
 * <p>存在的唯一理由是安全：{@code GET /api/connection/list} 实测会
 * <b>明文返回数据库密码</b>（与其文档中「敏感信息不会出现在返回 JSON 中」的说法相反）。
 * 因此 {@link DbxClient} 解析该接口后立刻把响应收敛成本类型，
 * 只保留这四个字段，password 等一律丢弃 —— 原始响应不进日志、不出方法。
 */
public record DbxConnectionSummary(
        String id,
        String name,
        String dbType,
        String database) {
}
