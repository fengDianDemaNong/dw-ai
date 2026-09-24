package com.dwai.lineage.dto;

import java.util.List;

/**
 * 表级血缘结果。
 *
 * <p>只依赖 superior-sql-parser 的语句解析，<b>不需要表结构元数据</b>，
 * 因此比列级血缘快一个数量级，且在元数据缺失时仍然可用 ——
 * 可作为列级血缘失败时的降级方案。
 *
 * @param statements 每条写入语句的输入/输出表
 * @param nodes      去重后的全部表
 * @param edges      表级依赖边
 */
public record TableLineageResponse(List<StatementLineage> statements,
                                   List<String> nodes,
                                   List<Edge> edges) {

    /**
     * @param index         语句序号，从 1 开始
     * @param statementType 语句类型，如 INSERT / CREATE_TABLE_AS_SELECT
     * @param outputTables  写入的目标表
     * @param inputTables   读取的来源表
     */
    public record StatementLineage(int index,
                                   String statementType,
                                   List<String> outputTables,
                                   List<String> inputTables) {}

    /** 一条 {@code source -> target} 的表级依赖。 */
    public record Edge(String source, String target) {}
}
