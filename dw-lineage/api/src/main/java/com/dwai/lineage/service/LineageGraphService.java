package com.dwai.lineage.service;

import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.dto.LineageSaveRequest;
import com.dwai.lineage.dto.LineageSaveResponse;
import com.dwai.lineage.dto.LineageVersionResponse;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;

/**
 * 已保存血缘的查询与版本管理。
 *
 * <p>与 {@code LineageService}（解析 SQL 出图）区分开：这里读的是库里的血缘，
 * 前者是即席解析的结果。
 */
public interface LineageGraphService {

    /**
     * 查已保存的血缘图。
     *
     * @param columnFullName 起点。传表名（{@code schema.table}）表示整表，
     *                       传字段全名表示只看那一个字段
     * @param upstream       true 查上游，false 查下游
     * @param depth          层数；{@code null} 或 &lt;=0 表示不限（受硬上限约束）
     * @param versionId      可选，把起点表钉到某个历史版本
     */
    LineageGraph graph(LineageContext ctx, String columnFullName, boolean upstream,
                       Integer depth, Long versionId);

    /** 某张目标表的版本列表，倒序。 */
    List<LineageVersionResponse> versions(LineageContext ctx, long tableId);

    void markCurrent(LineageContext ctx, long versionId);

    void deleteVersion(LineageContext ctx, long versionId);

    /** 解析 SQL 并保存血缘。每张目标表各产生一个版本。 */
    LineageSaveResponse save(LineageContext ctx, LineageSaveRequest request);
}
