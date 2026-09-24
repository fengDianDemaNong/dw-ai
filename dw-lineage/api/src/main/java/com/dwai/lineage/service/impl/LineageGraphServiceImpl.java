package com.dwai.lineage.service.impl;

import java.util.function.Function;
import java.util.Locale;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;
import com.dwai.lineage.service.metadata.provider.ColumnDescriptor;
import com.dwai.lineage.persistence.MetaCatalogRepository;
import com.dwai.lineage.service.AnalyzedLineage;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.dto.LineageSaveRequest;
import com.dwai.lineage.dto.LineageSaveResponse;
import com.dwai.lineage.dto.LineageVersionResponse;
import com.dwai.lineage.persistence.JdbcLineageRepository;
import com.dwai.lineage.persistence.LineageCatalogRepository;
import com.dwai.lineage.persistence.LineageColumnRow;
import com.dwai.lineage.persistence.LineageEdgeRow;
import com.dwai.lineage.persistence.LineageRepository;
import com.dwai.lineage.persistence.LineageTableRow;
import com.dwai.lineage.persistence.LineageVersionRow;
import com.dwai.lineage.service.LineageGraphService;
import com.dwai.lineage.service.LineageService;
import com.dwai.lineage.service.DataCatalogService;
import com.dwai.lineage.service.MetadataSourceService;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import com.dwai.lineage.tenant.LineageContext;
import com.dwai.lineage.util.SQLLineageMerger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 已保存血缘的查询、版本管理与写入。 */
@Service
public class LineageGraphServiceImpl implements LineageGraphService {

    private static final Logger logger = LoggerFactory.getLogger(LineageGraphServiceImpl.class);

    private final LineageRepository lineage;
    private final LineageCatalogRepository catalog;
    private final LineageService lineageService;
    private final MetadataSourceService metadataSourceService;
    private final DataCatalogService dataCatalogService;
    /** 只用于「解析没带出描述时」回退查本地元数据目录，见 withLocalCatalogFallback。 */
    private final MetaCatalogRepository meta;

    public LineageGraphServiceImpl(LineageRepository lineage,
                                   LineageCatalogRepository catalog,
                                   LineageService lineageService,
                                   MetadataSourceService metadataSourceService,
                                   DataCatalogService dataCatalogService,
                                   MetaCatalogRepository meta) {
        this.lineage = lineage;
        this.catalog = catalog;
        this.lineageService = lineageService;
        this.metadataSourceService = metadataSourceService;
        this.dataCatalogService = dataCatalogService;
        this.meta = meta;
    }

    // ------------------------------------------------------------------
    // 查询
    // ------------------------------------------------------------------

    @Override
    public LineageGraph graph(LineageContext ctx, String startName, boolean upstream,
                              Integer depth, Long versionId) {
        int limit = (depth == null || depth <= 0) ? JdbcLineageRepository.MAX_DEPTH : depth;

        // 起点可能是一张表（整表血缘）或一个字段（单字段血缘）
        List<String> startColumns = resolveStartColumns(ctx, startName);
        if (startColumns.isEmpty()) {
            return SQLLineageMerger.buildGraphFromEdges(
                    Map.of(), null, SQLLineageMerger.Diagnostics.empty());
        }

        // 多个起点各走一次遍历，边合并到一张图上；同一条边被多个起点走到时天然去重
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        for (String start : startColumns) {
            List<LineageEdgeRow> rows = upstream
                    ? lineage.upstream(ctx, start, limit, versionId)
                    : lineage.downstream(ctx, start, limit, versionId);
            for (LineageEdgeRow row : rows) {
                edges.computeIfAbsent(row.targetField(), k -> new LinkedHashSet<>())
                        .add(row.sourceField());
            }
        }

        logger.info("查询已保存血缘, {}, 起点={}, 方向={}, 深度={}, 版本={}, 边={}",
                ctx, startName, upstream ? "上游" : "下游", limit, versionId, edges.size());

        // 复用与解析路径同一套渲染：层级、同层序号、环检测的口径完全一致
        return SQLLineageMerger.buildGraphFromEdges(edges, null, SQLLineageMerger.Diagnostics.empty());
    }

    /**
     * 起点解析：先当字段查，查不到再当表查（返回它的全部字段）。
     *
     * <p>顺序不能反：{@code schema.table} 与 {@code schema.table.column} 都是点分名，
     * 光看形状分不出来，只能拿实际数据判。
     */
    private List<String> resolveStartColumns(LineageContext ctx, String startName) {
        if (startName == null || startName.isBlank()) {
            return List.of();
        }
        String name = startName.trim();

        return catalog.findTableByFullName(ctx, name)
                .map(table -> catalog.listColumns(ctx, table.id()).stream()
                        .map(LineageColumnRow::fullName).toList())
                // 不是表名，那就按字段全名处理
                .orElseGet(() -> List.of(name));
    }

    // ------------------------------------------------------------------
    // 版本
    // ------------------------------------------------------------------

    @Override
    public List<LineageVersionResponse> versions(LineageContext ctx, long tableId) {
        requireTable(ctx, tableId);
        return lineage.listVersions(ctx, tableId).stream()
                .map(LineageVersionResponse::from).toList();
    }

    @Override
    public void markCurrent(LineageContext ctx, long versionId) {
        lineage.markCurrent(ctx, versionId);
    }

    @Override
    public void deleteVersion(LineageContext ctx, long versionId) {
        if (!lineage.deleteVersion(ctx, versionId)) {
            throw new IllegalArgumentException("血缘版本不存在: " + versionId);
        }
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    /**
     * 描述取数：解析时带出来的优先，没有就退回本地元数据目录。
     *
     * <h2>为什么还需要这个回退</h2>
     * 用户可以<b>不选元数据来源</b>直接解析保存（前端的 sourceId 为空时就是这条路）。
     * 那种情况下解析链里根本没有元数据 provider，一份描述也带不出来 ——
     * 而这些表的中文名很可能早就通过「贴建表语句导入」存在本地目录里了。
     * 不兜这一下，用户会觉得中文名平白消失了。
     *
     * <h2>只按完整全名精确匹配</h2>
     * 不做「忽略 catalog 按库.表匹配」那种模糊查找。跨 catalog 的场景由前面的
     * provider 快照负责（用谁的元数据解析就存谁的描述），这里只是补上
     * 「本地目录里恰好有同一张表」这一种，语义与 1.0.5 之前那个
     * {@code left join meta_table on full_name} 完全一致，不引入新的猜测。
     *
     * <p>按表懒查并缓存：一次保存涉及的表通常只有几张到几十张，点查的代价可以忽略，
     * 换来的是不必把「图上有哪些表」的推导逻辑从仓储层搬出来。
     */
    private Function<String, TableDescriptor> withLocalCatalogFallback(
            LineageContext ctx, AnalyzedLineage analyzed) {
        Map<String, TableDescriptor> cache = new LinkedHashMap<>();
        return fullName -> {
            TableDescriptor fromParse = analyzed.descriptorOf(fullName);
            if (fromParse != null && !fromParse.isEmpty()) {
                return fromParse;
            }
            // 用 containsKey 判断而不是 get()!=null：查不到也要缓存，否则每次都白查一遍
            if (cache.containsKey(fullName)) {
                return cache.get(fullName);
            }
            TableDescriptor fromCatalog = loadFromLocalCatalog(ctx, fullName);
            cache.put(fullName, fromCatalog);
            return fromCatalog;
        };
    }

    private TableDescriptor loadFromLocalCatalog(LineageContext ctx, String tableFullName) {
        return meta.findByFullName(ctx, tableFullName.toLowerCase(Locale.ROOT))
                .map(table -> {
                    Map<String, ColumnDescriptor> columns = new LinkedHashMap<>();
                    meta.listColumnsByTableIds(ctx, List.of(table.id()))
                            .getOrDefault(table.id(), List.of())
                            .forEach(c -> columns.put(
                                    TableDescriptor.normalizeColumnKey(c.columnName()),
                                    new ColumnDescriptor(c.dataType(), c.comment(), c.remark())));
                    return new TableDescriptor(
                            table.comment(), table.tableType(), table.remark(), columns);
                })
                .orElse(null);
    }

    @Override
    public LineageSaveResponse save(LineageContext ctx, LineageSaveRequest request) {
        // 刻意重新解析一次，而不是收前端传来的图：那个图可被篡改，
        // 而且经过了列过滤等展示层处理，存进去会是残缺的血缘
        MetadataProvider external = request.sourceId() == null ? null
                : metadataSourceService.externalProvider(ctx, request.sourceId(),
                        request.metalake(), request.catalog()).orElse(null);

        // 保存一律用完整策略，不受前端「包含临时表」开关影响：
        // 那个开关是展示偏好，而「库里不存临时表」「表全名恒为三段」是数据约束。
        //
        // 走 analyzeDetailed 而不是 analyzeSqlLineage：除了图，还要拿到各表的描述属性
        // （中文名、字段类型…）。它们由解析当时用的那个元数据来源顺带带出来，
        // 随血缘一起落库，页面上就不必再去关联 meta_*（两侧的数据目录段对不上，
        // 关联注定失败）—— 理由见 TableDescriptor
        AnalyzedLineage analyzed = lineageService.analyzeDetailed(
                request.dbType(), request.createTableAsMetadata(), null,
                request.querySql(), external, dataCatalogService.policy(ctx));
        LineageGraph graph = analyzed.graph();

        List<LineageVersionRow> versions = lineage.saveVersions(
                ctx, request.dbType(), request.querySql(), graph,
                withLocalCatalogFallback(ctx, analyzed));

        if (versions.isEmpty()) {
            throw new IllegalArgumentException(
                    "这段 SQL 没有解析出任何血缘，没有可保存的内容。"
                            + "常见原因是缺少表结构元数据导致 select * 展不开，"
                            + "可在解析时选择一个元数据来源后重试");
        }

        List<LineageSaveResponse.SavedVersion> saved = new ArrayList<>();
        int totalEdges = 0;
        Set<String> tables = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();

        for (LineageVersionRow version : versions) {
            String targetTable = catalog.findTableById(ctx, version.targetTableId())
                    .map(LineageTableRow::fullName).orElse(String.valueOf(version.targetTableId()));
            saved.add(new LineageSaveResponse.SavedVersion(version.id(), version.targetTableId(),
                    targetTable, version.versionNo(), version.statEdges()));
            totalEdges += version.statEdges();
            tables.add(targetTable);
            columns.add(targetTable);
        }

        logger.info("血缘保存完成, {}, 目标表={}, 版本={}", ctx, tables, saved.size());
        return new LineageSaveResponse(
                versions.stream().mapToInt(LineageVersionRow::statTables).max().orElse(0),
                versions.stream().mapToInt(LineageVersionRow::statColumns).sum(),
                totalEdges, saved);
    }

    private LineageTableRow requireTable(LineageContext ctx, long tableId) {
        return catalog.findTableById(ctx, tableId)
                .orElseThrow(() -> new IllegalArgumentException("表不存在: " + tableId));
    }
}
