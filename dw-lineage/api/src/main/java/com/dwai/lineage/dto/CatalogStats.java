package com.dwai.lineage.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 概览首页的统计数字，一次请求给全。
 *
 * <p>刻意做成一个接口而不是让前端逐表拉：字段数、边数、最近解析这几项在
 * 现有接口里都只能按表 id 一个个查，表一多就是几百次请求。这里全部落到
 * 几条 count 上，表数量对响应时间没有影响。
 *
 * @param tables           血缘目录里的表数
 * @param columns          血缘目录里的字段数
 * @param edges            <b>当前版本</b>的字段级边数。历史版本的边还在库里，
 *                         但那不是用户在图上看到的东西，混进来只会让数字对不上
 * @param versions         血缘版本总数（含历史）
 * @param metaTables       元数据目录里的表数
 * @param recentParses     最近若干次解析（按版本生成时间倒序）
 * @param metaOnlyTables   元数据里有结构、血缘里从没出现过的表
 * @param isolatedTables   血缘里存在但当前版本下既没上游也没下游的孤立表
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CatalogStats(int tables,
                           int columns,
                           int edges,
                           int versions,
                           int metaTables,
                           List<RecentParse> recentParses,
                           List<TableBrief> metaOnlyTables,
                           List<TableBrief> isolatedTables) {

    /**
     * 一次解析留下的版本。
     *
     * @param tableId     目标表 id，前端据此跳详情
     * @param fullName    目标表全名
     * @param versionNo   该表内递增的版本号
     * @param current     是否为该表当前生效的版本
     */
    public record RecentParse(long versionId,
                              long tableId,
                              String fullName,
                              int versionNo,
                              String dbType,
                              boolean current,
                              int statTables,
                              int statColumns,
                              int statEdges,
                              LocalDateTime createdAt) {}

    /**
     * 列表里只需要认出是哪张表并能点进去。
     *
     * @param id 血缘表 id；{@code metaOnlyTables} 里的表在血缘侧不存在，故为 null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TableBrief(Long id, String fullName, String comment) {}
}
