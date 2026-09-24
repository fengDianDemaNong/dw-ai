package com.dwai.lineage.persistence;

import java.time.LocalDateTime;

/**
 * 一张目标表的一个血缘版本。
 *
 * <p>版本挂在<b>目标表</b>上，不是挂在项目上：同一张表的血缘被重新解析时产生新版本，
 * 解析别的表不影响它。这样「表基础信息」看到的是累积目录，
 * 「血缘关系」看到的是某张表的某个版本。
 *
 * @param targetTableId 该版本描述的是哪张表的血缘（{@code lineage_table.id}）
 * @param versionNo     <b>同一目标表内</b>递增的版本号，从 1 开始
 * @param isCurrent     是否为该目标表的当前版本，<b>同一目标表内</b>至多一条
 */
public record LineageVersionRow(long id,
                                long tenantId,
                                long projectId,
                                long targetTableId,
                                int versionNo,
                                String name,
                                String dbType,
                                String sqlHash,
                                boolean isCurrent,
                                int statTables,
                                int statColumns,
                                int statEdges,
                                LocalDateTime createdAt) {
}
