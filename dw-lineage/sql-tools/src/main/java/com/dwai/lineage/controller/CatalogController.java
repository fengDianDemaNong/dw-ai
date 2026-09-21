package com.dwai.lineage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.dwai.lineage.dto.CatalogColumnResponse;
import com.dwai.lineage.dto.CatalogSearchHit;
import com.dwai.lineage.dto.CatalogStats;
import com.dwai.lineage.dto.CatalogTableResponse;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.dto.LineageSaveRequest;
import com.dwai.lineage.dto.LineageSaveResponse;
import com.dwai.lineage.dto.LineageVersionResponse;
import com.dwai.lineage.service.CatalogService;
import com.dwai.lineage.service.LineageGraphService;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 已保存血缘的查询：表基础信息、血缘关系、全局搜索三个页面用。
 *
 * <p>与 {@code /api/meta/**}（元数据目录）分开：那边是「数据库里真实存在的表结构」，
 * 这边是「从 SQL 推导并保存下来的血缘」。读接口会按 full_name 把元数据侧的
 * 中文名等描述属性关联出来，但两者的存储互不写入。
 */
@Tag(name = "已保存血缘")
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;
    private final LineageGraphService graphService;

    public CatalogController(CatalogService catalogService, LineageGraphService graphService) {
        this.catalogService = catalogService;
        this.graphService = graphService;
    }

    // ---------------- 表基础信息 ----------------

    @Operation(
            summary = "列出血缘侧出现过的库名",
            description = "当前项目里已经保存过血缘的 schema 列表，给「表基础信息」库名下拉。不是元数据目录的库列表（那个是 GET /api/meta/schemas）。")
    @GetMapping("/catalog/schemas")
    public List<String> schemas() {
        return catalogService.listSchemas(TenantContextHolder.require());
    }

    /**
     * 血缘侧出现过的数据目录，供「数据目录」下拉。
     *
     * <p>与库名下拉是级联关系：先选目录，再选库，最后选表。
     */
    @Operation(
            summary = "列出血缘侧出现过的数据目录",
            description = "已保存血缘里出现过的 catalog 名，与库名下拉级联：先目录再库再表。配置目录本身请用 /api/data-catalogs。")
    @GetMapping("/catalog/catalogs")
    public List<String> catalogs() {
        return catalogService.listCatalogs(TenantContextHolder.require());
    }

    /**
     * @param catalog 数据目录。<b>不传 = 全部；传空串 = 只看不属于任何目录的表</b>
     */
    @Operation(
            summary = "列出已保存血缘中的表",
            description = """
                    当前项目血缘侧的表清单（不是元数据表，元数据用 GET /api/meta/tables）。
                    catalog 不传=全部；传空字符串=只看不属于任何目录的表。
                    """)
    @GetMapping("/catalog/tables")
    public List<CatalogTableResponse> tables(
            @Parameter(description = "数据目录名。省略=全部；空串=无目录的表")
            @RequestParam(required = false) String catalog,
            @Parameter(description = "库名 / schema，可与 catalog 组合过滤")
            @RequestParam(required = false) String schema) {
        return catalogService.listTables(TenantContextHolder.require(), catalog, schema);
    }

    @Operation(
            summary = "查询血缘侧单表详情",
            description = "按血缘表 id 取表基础信息。id 来自列出表或搜索命中。")
    @GetMapping("/catalog/tables/{id}")
    public CatalogTableResponse table(
            @Parameter(description = "血缘侧表 id", required = true)
            @PathVariable long id) {
        return catalogService.getTable(TenantContextHolder.require(), id);
    }

    @Operation(
            summary = "列出血缘侧某表的字段",
            description = "该表在已保存血缘中的字段。元数据字段请用 GET /api/meta/tables/{id}/columns。")
    @GetMapping("/catalog/tables/{id}/columns")
    public List<CatalogColumnResponse> columns(
            @Parameter(description = "血缘侧表 id", required = true)
            @PathVariable long id) {
        return catalogService.listColumns(TenantContextHolder.require(), id);
    }

    /** 直接上游表，由列级边聚合投影而来。 */
    @Operation(
            summary = "查询某表的直接上游表",
            description = "由列级边聚合出的直接上游表。看多层图请用 GET /api/lineage/graph。")
    @GetMapping("/catalog/tables/{id}/upstream")
    public List<CatalogTableResponse> upstreamTables(
            @Parameter(description = "血缘侧表 id", required = true)
            @PathVariable long id) {
        return catalogService.upstreamTables(TenantContextHolder.require(), id);
    }

    /** 被哪些下游表的哪些字段引用。删表前的保护检查。 */
    @Operation(
            summary = "查询引用了该表的下游",
            description = "哪些下游表的哪些字段引用了本表。删表或改名之前应用本接口做保护检查。")
    @GetMapping("/catalog/tables/{id}/referenced")
    public List<CatalogSearchHit> referenced(
            @Parameter(description = "血缘侧表 id", required = true)
            @PathVariable long id) {
        return catalogService.referencedBy(TenantContextHolder.require(), id);
    }

    /** 字段的直接上游，供表格展开行懒加载。 */
    @Operation(
            summary = "查询某字段的直接上游字段",
            description = "列级直接上游，供表格展开行懒加载。多层字段图用 GET /api/lineage/graph，start 写成 schema.table.column。")
    @GetMapping("/catalog/columns/{id}/upstream")
    public List<CatalogColumnResponse> upstreamColumns(
            @Parameter(description = "血缘侧字段 id", required = true)
            @PathVariable long id) {
        return catalogService.upstreamColumns(TenantContextHolder.require(), id);
    }

    @Operation(
            summary = "查询某字段的直接下游字段",
            description = "列级直接下游。")
    @GetMapping("/catalog/columns/{id}/downstream")
    public List<CatalogColumnResponse> downstreamColumns(
            @Parameter(description = "血缘侧字段 id", required = true)
            @PathVariable long id) {
        return catalogService.downstreamColumns(TenantContextHolder.require(), id);
    }

    /**
     * 概览首页的统计。
     *
     * <p>单独一个接口而不是让前端拼：字段数、边数、最近解析在其它接口里都只能
     * 按表 id 逐个查，表一多就是几百次请求。
     */
    @Operation(
            summary = "血缘目录汇总统计",
            description = "当前项目血缘侧的表数、字段数、边数、最近解析时间等，给概览页一次取齐。项目级更多数字见 GET /api/stats/project。")
    @GetMapping("/catalog/stats")
    public CatalogStats stats() {
        return catalogService.stats(TenantContextHolder.require());
    }

    // ---------------- 全局搜索 ----------------

    /**
     * 全局搜索。
     *
     * @param keyword 空格分隔多个条件，词间 AND、词内跨字段 OR，大小写不敏感
     */
    @Operation(
            summary = "全局搜索已保存的表和字段",
            description = """
                    在当前项目已保存血缘中搜表/字段。keyword 用空格分隔多个条件：词与词 AND，同一词跨字段 OR，大小写不敏感。
                    tableType 可按数仓表类型收窄。
                    """)
    @GetMapping("/catalog/search")
    public List<CatalogSearchHit> search(
            @Parameter(description = "关键字，空格分词，词间 AND")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "数仓表类型过滤，如 FULL、INCRE")
            @RequestParam(required = false) String tableType) {
        return catalogService.search(TenantContextHolder.require(), keyword, tableType);
    }

    // ---------------- 血缘关系 ----------------

    /**
     * 已保存的血缘图。
     *
     * @param start     起点：表名 {@code schema.table}（整表）或字段全名 {@code schema.table.column}
     * @param direction {@code up} 上游 / {@code down} 下游
     * @param depth     层数，不传或 &lt;=0 表示不限（仍受硬上限约束）
     * @param versionId 可选，把起点表钉到某个历史版本
     */
    @Operation(
            summary = "查询已保存的血缘图",
            description = """
                    从已落库的血缘里按起点展开图，不是当场解析 SQL。
                    start：整表用 schema.table，字段用 schema.table.column。
                    direction：up 上游（默认），down 下游。
                    depth：层数，不传或 <=0 表示不限（仍有服务端硬上限）。
                    versionId：把起点表钉到某个历史版本；省略则用当前版本。
                    要先根据 SQL 算出图请用 POST /api/lineage/analyze 或 /save。
                    """)
    @GetMapping("/lineage/graph")
    public LineageGraph graph(
            @Parameter(description = "起点：schema.table 或 schema.table.column", required = true, example = "ads.ads_order")
            @RequestParam String start,
            @Parameter(description = "方向：up 上游（默认）/ down 下游")
            @RequestParam(defaultValue = "up") String direction,
            @Parameter(description = "展开层数；不传或 <=0 表示不限")
            @RequestParam(required = false) Integer depth,
            @Parameter(description = "可选，起点表钉到该历史版本 id")
            @RequestParam(required = false) Long versionId) {
        return graphService.graph(TenantContextHolder.require(), start,
                !"down".equalsIgnoreCase(direction), depth, versionId);
    }

    @Operation(
            summary = "列出某表的血缘版本",
            description = "每次 POST /api/lineage/save 会给目标表新增版本。返回该表历史版本列表，用于切换当前版本或对照。")
    @GetMapping("/catalog/tables/{id}/versions")
    public List<LineageVersionResponse> versions(
            @Parameter(description = "血缘侧表 id", required = true)
            @PathVariable long id) {
        return graphService.versions(TenantContextHolder.require(), id);
    }

    @Operation(
            summary = "将某血缘版本标为当前",
            description = "把指定 version id 标成该表当前版本，之后 GET /api/lineage/graph 不传 versionId 时走这一版。")
    @PutMapping("/lineage/versions/{id}/current")
    public void markCurrent(
            @Parameter(description = "血缘版本 id", required = true)
            @PathVariable long id) {
        graphService.markCurrent(TenantContextHolder.require(), id);
    }

    @Operation(
            summary = "删除某血缘版本",
            description = "删除指定历史版本。不要删仍标记为当前的唯一版本，除非确认不再需要该表血缘。")
    @DeleteMapping("/lineage/versions/{id}")
    public void deleteVersion(
            @Parameter(description = "血缘版本 id", required = true)
            @PathVariable long id) {
        graphService.deleteVersion(TenantContextHolder.require(), id);
    }

    /** 解析 SQL 并保存血缘。每张目标表各产生一个版本。 */
    @Operation(
            summary = "解析 SQL 并保存血缘",
            description = """
                    用请求里的 SQL 重新解析后写入当前项目，每张目标表各产生一个新版本。
                    不要把前端已有的图画结果回传——接口不接收图，只接收 SQL，以保证入库数据可信。
                    只想预览不落库：POST /api/lineage/analyze。
                    元数据参数（isCreateTable、sourceId、metalake、catalog）语义与 analyze 相同。
                    """)
    @PostMapping("/lineage/save")
    public LineageSaveResponse save(@Valid @RequestBody LineageSaveRequest request) {
        return graphService.save(TenantContextHolder.require(), request);
    }
}
