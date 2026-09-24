package com.dwai.lineage.dto;

import java.util.List;

/**
 * 同步结果。
 *
 * <p>分开报「新增 / 更新 / 跳过 / 失败」而不是只给一个总数：
 * 跳过通常是因为该表被人工维护过（{@code source=MANUAL}），
 * 用户需要知道自己勾的表里有哪些没被动，否则会以为同步没生效。
 *
 * @param skippedTables 因人工维护而跳过的表名
 * @param failures      逐表的失败原因，一张表失败不影响其它表
 */
public record MetaSyncResult(int created,
                             int updated,
                             int skipped,
                             int failed,
                             List<String> skippedTables,
                             List<TableFailure> failures) {

    /** 单张表的同步失败。 */
    public record TableFailure(String table, String message) {
    }
}
