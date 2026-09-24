package com.dwai.lineage.service;

import com.dwai.lineage.dto.MetaColumnResponse;
import com.dwai.lineage.dto.MetaColumnUpdateRequest;
import com.dwai.lineage.dto.MetaDdlImportRequest;
import com.dwai.lineage.dto.MetaSyncResult;
import com.dwai.lineage.dto.MetaTableResponse;
import com.dwai.lineage.dto.MetaTableUpdateRequest;
import com.dwai.lineage.dto.PageResponse;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;

/** 元数据目录的查询与维护。 */
public interface MetaCatalogService {

    List<String> listSchemas(LineageContext ctx);

    /** 元数据侧出现过的数据目录，供页面筛选。 */
    List<String> listCatalogs(LineageContext ctx);

    PageResponse<MetaTableResponse> list(LineageContext ctx, String catalog, String schema,
                                         String keyword, int page, int size);

    MetaTableResponse get(LineageContext ctx, long id);

    List<MetaColumnResponse> columns(LineageContext ctx, long tableId);

    /**
     * 贴建表语句导入。
     *
     * <p>复用与 SQL 解析同一套方言拆分，因此各方言的建表语法都能吃。
     * 结果沿用同步的计数结构，前端两处可以共用一个展示组件。
     */
    MetaSyncResult importDdl(LineageContext ctx, MetaDdlImportRequest request);

    MetaTableResponse updateTable(LineageContext ctx, long id, MetaTableUpdateRequest request);

    MetaColumnResponse updateColumn(LineageContext ctx, long id, MetaColumnUpdateRequest request);

    void deleteTable(LineageContext ctx, long id);

    void deleteColumn(LineageContext ctx, long id);
}
