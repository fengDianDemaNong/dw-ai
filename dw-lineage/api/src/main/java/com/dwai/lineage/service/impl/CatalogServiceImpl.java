package com.dwai.lineage.service.impl;

import com.dwai.lineage.dto.CatalogColumnResponse;
import com.dwai.lineage.dto.CatalogSearchHit;
import com.dwai.lineage.dto.CatalogStats;
import com.dwai.lineage.dto.CatalogTableResponse;
import com.dwai.lineage.persistence.LineageCatalogRepository;
import com.dwai.lineage.persistence.LineageColumnRow;
import com.dwai.lineage.persistence.LineageEdgeRow;
import com.dwai.lineage.persistence.LineageRepository;
import com.dwai.lineage.persistence.LineageTableRow;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.persistence.MetaTableRow;
import com.dwai.lineage.service.CatalogService;
import com.dwai.lineage.service.metadata.MetaNames;
import com.dwai.lineage.tenant.LineageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 血缘目录查询。
 *
 * <p>每个读接口都做一次「按 full_name 关联元数据侧」的补全：
 * 表身份来自血缘侧，中文名/类型/备注来自元数据侧。关联不上就是空，不报错 ——
 * 血缘里可能有临时表，元数据目录里本来就不该有它们。
 */
@Service
public class CatalogServiceImpl implements CatalogService {

    private static final Logger logger = LoggerFactory.getLogger(CatalogServiceImpl.class);

    /** 搜索结果上限。参照项目用的是 20000，同一量级。 */
    private static final int SEARCH_LIMIT = 2000;

    /**
     * 表列表一次最多返回多少张。
     *
     * <p>这个接口没有分页（元数据侧同类接口有 size 上限 200，血缘侧一直没有），
     * 表上规模后会变成一个巨大的响应体。在改成分页之前先加个上限保护：
     * 截断比让服务因为一次请求把几万行读进内存要好。
     */
    private static final int TABLE_LIST_LIMIT = 5000;

    /** 首页「最近解析」的条数。够看出最近在做什么就行，不是审计日志。 */
    private static final int RECENT_PARSE_LIMIT = 10;

    /** 首页两张「无血缘」清单各自的条数。超出部分让用户到对应页面去筛。 */
    private static final int OVERVIEW_LIST_LIMIT = 20;

    private final LineageCatalogRepository catalog;
    private final LineageRepository lineage;
    private final MetaCatalogRepository meta;

    public CatalogServiceImpl(LineageCatalogRepository catalog,
                              LineageRepository lineage,
                              MetaCatalogRepository meta) {
        this.catalog = catalog;
        this.lineage = lineage;
        this.meta = meta;
    }

    @Override
    public List<String> listSchemas(LineageContext ctx) {
        return catalog.listSchemas(ctx);
    }

    @Override
    public List<String> listCatalogs(LineageContext ctx) {
        return catalog.listCatalogs(ctx);
    }

    @Override
    public List<CatalogTableResponse> listTables(LineageContext ctx, String catalogName, String schema) {
        List<LineageTableRow> rows = catalog.listTables(ctx, catalogName, schema);
        if (rows.size() > TABLE_LIST_LIMIT) {
            // 静默截断会让页面显示一个「看起来完整」的残缺列表，所以要吼一声
            logger.warn("表列表超过上限被截断, {}, 实际={}, 返回={}。该接口尚未分页，"
                            + "请用数据目录/库名筛选缩小范围",
                    ctx, rows.size(), TABLE_LIST_LIMIT);
            rows = rows.subList(0, TABLE_LIST_LIMIT);
        }
        return withMetaIds(ctx, rows);
    }

    @Override
    public CatalogTableResponse getTable(LineageContext ctx, long tableId) {
        LineageTableRow table = requireTable(ctx, tableId);
        return CatalogTableResponse.of(table, metaTableIdOf(ctx, table));
    }

    @Override
    public List<CatalogColumnResponse> listColumns(LineageContext ctx, long tableId) {
        requireTable(ctx, tableId);
        return toColumnResponses(catalog.listColumns(ctx, tableId));
    }

    @Override
    public List<CatalogTableResponse> upstreamTables(LineageContext ctx, long tableId) {
        requireTable(ctx, tableId);
        // 表级血缘是列级边的投影：取该表所有字段的上游边，按上游字段所属的表去重
        Set<String> upstreamTableNames = new LinkedHashSet<>();
        for (LineageEdgeRow edge : lineage.directUpstreamOfTable(ctx, tableId)) {
            upstreamTableNames.add(tableOf(edge.sourceField()));
        }
        return resolveTablesByFullName(ctx, upstreamTableNames);
    }

    @Override
    public List<CatalogSearchHit> referencedBy(LineageContext ctx, long tableId) {
        LineageTableRow table = requireTable(ctx, tableId);

        List<CatalogSearchHit> hits = new ArrayList<>();
        List<LineageEdgeRow> downstreamEdges = lineage.directDownstreamOfTable(ctx, tableId);

        // 只查这些边真正涉及的下游表，而不是把项目里所有表都读进来建索引
        Set<String> downstreamNames = new LinkedHashSet<>();
        downstreamEdges.forEach(e -> downstreamNames.add(tableOf(e.targetField())));
        Map<String, LineageTableRow> byFullName = new LinkedHashMap<>();
        catalog.findTablesByFullNames(ctx, downstreamNames)
                .forEach(t -> byFullName.put(t.fullName(), t));

        for (LineageEdgeRow edge : downstreamEdges) {
            String downstreamTable = tableOf(edge.targetField());
            if (downstreamTable.equals(table.fullName())) {
                // 自引用（insert overwrite 自己）不算被别人引用，不该阻止删除
                continue;
            }
            LineageTableRow downstream = byFullName.get(downstreamTable);
            hits.add(new CatalogSearchHit(
                    downstream == null ? 0 : downstream.id(),
                    downstream == null ? null : downstream.catalogName(),
                    downstream == null ? schemaOf(downstreamTable) : downstream.schemaName(),
                    downstream == null ? simpleNameOf(downstreamTable) : downstream.tableName(),
                    downstreamTable, null, null, null,
                    null, columnOf(edge.targetField()),
                    // 借 columnComment 位把「被引用的是本表哪个字段」带出去，页面上要显示
                    columnOf(edge.sourceField())));
        }
        return hits;
    }

    @Override
    public List<CatalogColumnResponse> upstreamColumns(LineageContext ctx, long columnId) {
        LineageColumnRow column = requireColumn(ctx, columnId);
        Set<String> upstream = new LinkedHashSet<>();
        for (LineageEdgeRow edge : lineage.upstream(ctx, column.fullName(), 1, null)) {
            upstream.add(edge.sourceField());
        }
        return resolveColumnsByFullName(ctx, upstream);
    }

    @Override
    public List<CatalogColumnResponse> downstreamColumns(LineageContext ctx, long columnId) {
        LineageColumnRow column = requireColumn(ctx, columnId);
        Set<String> downstream = new LinkedHashSet<>();
        for (LineageEdgeRow edge : lineage.downstream(ctx, column.fullName(), 1, null)) {
            downstream.add(edge.targetField());
        }
        return resolveColumnsByFullName(ctx, downstream);
    }

    @Override
    public List<CatalogSearchHit> search(LineageContext ctx, String keyword, String tableType) {
        // 空格分隔多条件，词间 AND —— 与参照项目的输入习惯一致
        List<String> keywords = keyword == null ? List.of()
                : List.of(keyword.trim().split("\\s+")).stream()
                        .filter(k -> !k.isBlank()).toList();
        if (keywords.isEmpty() && (tableType == null || tableType.isBlank())) {
            throw new IllegalArgumentException("请输入查询条件");
        }
        return catalog.search(ctx, keywords, tableType, SEARCH_LIMIT);
    }

    /**
     * 概览统计。
     *
     * <p>清单类结果都截断：首页要的是「有没有问题、大概多少」，不是把几百张表
     * 铺在首屏。想看全的到对应页面去筛。
     */
    @Override
    public CatalogStats stats(LineageContext ctx) {
        return catalog.stats(ctx, RECENT_PARSE_LIMIT, OVERVIEW_LIST_LIMIT);
    }

    // ------------------------------------------------------------------
    // 与元数据侧关联
    // ------------------------------------------------------------------

    /**
     * 表行 → 响应，并批量带上元数据侧的 id。
     *
     * <p><b>描述属性不再从这里取</b>：1.0.5 之前要按 {@code fullName} 去
     * {@code meta_table} 捞中文名，而那个关联本来就大面积失效（两侧的数据目录段
     * 对不上，见 {@link CatalogTableResponse}）。现在描述就在血缘行上。
     *
     * <p>但 {@code metaTableId} 还得查：页面上「已关联 / 元数据缺失」那个标记靠它。
     * 这里只是一次<b>批量点查</b>（{@code findByFullNames} 内部按 IN 分批），
     * 不是原先塞在搜索 SQL 里的宽 join —— 精确按全名匹配，语义与从前一致。
     */
    private List<CatalogTableResponse> withMetaIds(LineageContext ctx,
                                                   List<LineageTableRow> tables) {
        if (tables.isEmpty()) {
            return List.of();
        }
        Map<String, Long> idByFullName = new LinkedHashMap<>();
        meta.findByFullNames(ctx, tables.stream()
                        .map(t -> t.fullName().toLowerCase(Locale.ROOT)).toList())
                .forEach(m -> idByFullName.put(m.fullName(), m.id()));

        return tables.stream()
                .map(t -> CatalogTableResponse.of(t,
                        idByFullName.get(t.fullName().toLowerCase(Locale.ROOT))))
                .toList();
    }

    private static List<CatalogColumnResponse> toColumnResponses(List<LineageColumnRow> columns) {
        return columns.stream().map(CatalogColumnResponse::of).toList();
    }

    /**
     * 元数据侧那张表的 id，供详情页给出「去元数据管理里看」的入口。
     *
     * <p>只在<b>单表详情</b>上查：为它给列表和搜索加一个宽 join 不划算，
     * 而这里是一次点查。查不到就是 null —— 元数据目录里还没有这张表，
     * 页面据此显示「元数据缺失」。
     */
    private Long metaTableIdOf(LineageContext ctx, LineageTableRow table) {
        return meta.findByFullName(ctx, table.fullName().toLowerCase(Locale.ROOT))
                .map(MetaTableRow::id)
                .orElse(null);
    }

    private List<CatalogTableResponse> resolveTablesByFullName(LineageContext ctx, Set<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        // 按名字直接查这几张表。原先是 listTables(ctx, null, null) 全量加载再在 Java 里
        // filter —— 为了找几张上游表，把整个项目的表都读进内存
        return withMetaIds(ctx, catalog.findTablesByFullNames(ctx, names));
    }

    private List<CatalogColumnResponse> resolveColumnsByFullName(LineageContext ctx, Set<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        // 一次按字段全名查回来。原先要先全量加载所有表、挑出相关的，再逐表查字段
        // 过滤（全表扫 + N+1），而字段全名本身就有唯一索引，直接查即可
        return toColumnResponses(catalog.findColumnsByFullNames(ctx, names));
    }

    // ------------------------------------------------------------------

    private LineageTableRow requireTable(LineageContext ctx, long tableId) {
        return catalog.findTableById(ctx, tableId)
                .orElseThrow(() -> new IllegalArgumentException("表不存在: " + tableId));
    }

    private LineageColumnRow requireColumn(LineageContext ctx, long columnId) {
        return catalog.findColumnById(ctx, columnId)
                .orElseThrow(() -> new IllegalArgumentException("字段不存在: " + columnId));
    }

    private static String tableOf(String columnFullName) {
        int i = columnFullName.lastIndexOf('.');
        return i < 0 ? columnFullName : columnFullName.substring(0, i);
    }

    private static String columnOf(String columnFullName) {
        int i = columnFullName.lastIndexOf('.');
        return i < 0 ? columnFullName : columnFullName.substring(i + 1);
    }

    private static String schemaOf(String tableFullName) {
        int i = tableFullName.lastIndexOf('.');
        return i < 0 ? MetaNames.DEFAULT_SCHEMA : tableFullName.substring(0, i);
    }

    private static String simpleNameOf(String tableFullName) {
        int i = tableFullName.lastIndexOf('.');
        return i < 0 ? tableFullName : tableFullName.substring(i + 1);
    }
}
