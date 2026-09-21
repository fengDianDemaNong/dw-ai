package com.dwai.lineage.service;

import com.dwai.lineage.dto.CatalogColumnResponse;
import com.dwai.lineage.dto.CatalogSearchHit;
import com.dwai.lineage.dto.CatalogStats;
import com.dwai.lineage.dto.CatalogTableResponse;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;

/**
 * 血缘目录的查询：表基础信息页与全局搜索页用。
 *
 * <p>读的是血缘侧的 {@code lineage_table} / {@code lineage_column}，
 * 中文名等描述属性按 {@code full_name} 关联元数据侧带出来。
 */
public interface CatalogService {

    List<String> listSchemas(LineageContext ctx);

    /** 血缘侧出现过的数据目录，供页面筛选。 */
    List<String> listCatalogs(LineageContext ctx);

    /** 某个库下的表；schema 为空则返回全部。 */
    List<CatalogTableResponse> listTables(LineageContext ctx, String catalogName, String schema);

    CatalogTableResponse getTable(LineageContext ctx, long tableId);

    List<CatalogColumnResponse> listColumns(LineageContext ctx, long tableId);

    /** 该表的直接上游表（由列级边聚合投影而来）。 */
    List<CatalogTableResponse> upstreamTables(LineageContext ctx, long tableId);

    /** 该表被哪些下游表的哪些字段引用。删表前的保护检查用。 */
    List<CatalogSearchHit> referencedBy(LineageContext ctx, long tableId);

    /** 某个字段的直接上游字段，供表基础信息页的展开行懒加载。 */
    List<CatalogColumnResponse> upstreamColumns(LineageContext ctx, long columnId);

    /** 某个字段的直接下游字段。删字段前的保护检查用。 */
    List<CatalogColumnResponse> downstreamColumns(LineageContext ctx, long columnId);

    /**
     * 全局搜索。
     *
     * @param keyword 原始输入，<b>空格分隔多个条件</b>，词间 AND
     */
    List<CatalogSearchHit> search(LineageContext ctx, String keyword, String tableType);

    /** 概览首页的统计数字与几张短清单。 */
    CatalogStats stats(LineageContext ctx);
}
