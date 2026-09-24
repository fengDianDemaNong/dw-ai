package com.dwai.lineage.controller;

import com.dwai.lineage.dto.RemoteTableDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.dwai.lineage.dto.MetaColumnResponse;
import com.dwai.lineage.dto.MetaColumnUpdateRequest;
import com.dwai.lineage.dto.MetaDdlImportRequest;
import com.dwai.lineage.dto.MetaSyncRequest;
import com.dwai.lineage.dto.MetaSyncResult;
import com.dwai.lineage.dto.SyncJobResponse;
import com.dwai.lineage.service.metadata.dbx.DbxConnectionSummary;
import com.dwai.lineage.dto.MetaTableResponse;
import com.dwai.lineage.dto.MetaTableUpdateRequest;
import com.dwai.lineage.dto.PageResponse;
import com.dwai.lineage.service.LineageAuthz;
import com.dwai.lineage.service.MetaCatalogService;
import com.dwai.lineage.service.MetaSyncService;
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
import java.util.Map;

/**
 * 元数据目录。
 *
 * <p>这里是「数据库里真实存在的表结构」，供 SQL 解析时查用，
 * 与血缘数据（{@code /api/catalog/**}、{@code /api/lineage/**}）<b>分开存储</b>：
 * 血缘是推导出来的、可能含临时表与推断列，混进元数据会让下次解析把猜测当事实。
 *
 * <p>三种更新方式：贴建表语句（{@code POST /ddl}）、
 * 从 Gravitino / dbx 同步（{@code POST /sync}）、页面上手工改（{@code PUT}）。
 */
@Tag(name = "元数据目录")
@RestController
@RequestMapping("/api/meta")
public class MetaCatalogController {

    private final MetaCatalogService catalogService;
    private final MetaSyncService syncService;
    private final LineageAuthz authz;

    public MetaCatalogController(MetaCatalogService catalogService, MetaSyncService syncService,
                                 LineageAuthz authz) {
        this.catalogService = catalogService;
        this.syncService = syncService;
        this.authz = authz;
    }

    // ---------------- 查询 ----------------

    /** 目录里出现过的库名，供左侧列表分组。 */
    @Operation(
            summary = "列出元数据侧的库名",
            description = "当前项目元数据目录里出现过的 schema，给左侧分组。血缘侧库名是 GET /api/catalog/schemas。")
    @GetMapping("/schemas")
    public List<String> schemas() {
        return catalogService.listSchemas(TenantContextHolder.require());
    }

    /** 元数据侧出现过的数据目录，供「数据目录」下拉。 */
    @Operation(
            summary = "列出元数据侧的数据目录",
            description = "元数据表所属的 catalog 名下拉。配置目录本身用 /api/data-catalogs。")
    @GetMapping("/catalogs")
    public List<String> catalogs() {
        return catalogService.listCatalogs(TenantContextHolder.require());
    }

    @Operation(
            summary = "分页查询元数据表",
            description = """
                    当前项目里真实表结构清单，供字段级解析查列。
                    不是已保存血缘表（那个是 GET /api/catalog/tables）。
                    catalog 不传=全部；空串=无目录。keyword 按表名/中文名过滤。
                    """)
    @GetMapping("/tables")
    public PageResponse<MetaTableResponse> tables(
            @Parameter(description = "数据目录名。省略=全部；空串=无目录")
            @RequestParam(required = false) String catalog,
            @Parameter(description = "库名 / schema")
            @RequestParam(required = false) String schema,
            @Parameter(description = "表名或中文名关键字")
            @RequestParam(required = false) String keyword,
            @Parameter(description = "页码，从 1 开始")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数")
            @RequestParam(defaultValue = "20") int size) {
        return catalogService.list(TenantContextHolder.require(), catalog, schema, keyword, page, size);
    }

    @Operation(
            summary = "查询元数据单表",
            description = "按元数据表 id 取详情。id 来自分页列表，不是血缘侧表 id。")
    @GetMapping("/tables/{id}")
    public MetaTableResponse table(
            @Parameter(description = "元数据表 id", required = true)
            @PathVariable long id) {
        return catalogService.get(TenantContextHolder.require(), id);
    }

    @Operation(
            summary = "列出元数据表的字段",
            description = "该表在元数据目录中的列定义（类型、注释、是否分区等）。")
    @GetMapping("/tables/{id}/columns")
    public List<MetaColumnResponse> columns(
            @Parameter(description = "元数据表 id", required = true)
            @PathVariable long id) {
        return catalogService.columns(TenantContextHolder.require(), id);
    }

    // ---------------- 三种更新方式 ----------------

    /** 一、贴建表语句导入。非建表语句会被忽略，可以直接把整个脚本粘进来。 */
    @Operation(
            summary = "用建表语句导入元数据",
            description = """
                    第一种写入方式：粘贴一段或多段 CREATE TABLE。非建表语句会被忽略，可直接贴整份脚本。
                    语句里写了三段名时以语句为准；否则用 catalogName，再否则用默认数据目录。
                    大批量从 Gravitino/dbx 拉表请用 POST /api/meta/sync，不要用本接口。
                    """)
    @PostMapping("/ddl")
    public MetaSyncResult importDdl(@Valid @RequestBody MetaDdlImportRequest request) {
        authz.require("catalog:admin");
        return catalogService.importDdl(TenantContextHolder.require(), request);
    }

    /**
     * 二、从外部元数据服务逐级浏览。
     *
     * <p>Gravitino 是 metalake → catalog → schema → table，dbx 是 database → schema → table。
     * 参数填到哪一级，就返回下一级的列表。
     */
    @Operation(
            summary = "浏览外部元数据下一级名称",
            description = """
                    从已配置的元数据服务（sourceId）逐级浏览，只返回名称列表，不写入本地。
                    Gravitino：metalake → catalog → schema → table。
                    dbx：database → schema → table（可再带 connectionId）。
                    参数填到哪一级，就返回下一级。看某张表字段用 GET /api/meta/sync/table；真正导入用 POST /api/meta/sync。
                    """)
    @GetMapping("/sync/browse")
    public List<String> browse(
            @Parameter(description = "元数据服务配置 id，来自 GET /api/metadata-sources", required = true)
            @RequestParam long sourceId,
            @Parameter(description = "Gravitino metalake，填了则列出其下 catalog")
            @RequestParam(required = false) String metalake,
            @Parameter(description = "Gravitino catalog")
            @RequestParam(required = false) String catalog,
            @Parameter(description = "dbx database")
            @RequestParam(required = false) String database,
            @Parameter(description = "schema / 库名")
            @RequestParam(required = false) String schema,
            @Parameter(description = "dbx 已保存连接 id")
            @RequestParam(required = false) String connectionId) {
        return syncService.browse(TenantContextHolder.require(), sourceId,
                metalake, catalog, database, schema, connectionId);
    }

    /**
     * 二（续）、看远程某张表的字段，<b>只读</b>。
     *
     * <p>{@code /sync/browse} 只给得出名字，而光看表名判断不了要不要导入。
     * 参数与 browse 同一套，多一个 {@code table}。
     *
     * <p>与导入共用同一份字段映射，所以这里看到的就是导进来的样子。
     */
    @Operation(
            summary = "预览远程表字段（只读）",
            description = "参数与 browse 同一套，多一个 table。不写入本地。用来决定要不要导入；导入后的列映射与这里一致。")
    @GetMapping("/sync/table")
    public RemoteTableDetail remoteTable(
            @Parameter(description = "元数据服务配置 id", required = true)
            @RequestParam long sourceId,
            @RequestParam(required = false) String metalake,
            @RequestParam(required = false) String catalog,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String schema,
            @Parameter(description = "远程表名", required = true)
            @RequestParam String table,
            @RequestParam(required = false) String connectionId) {
        return syncService.loadRemoteTable(TenantContextHolder.require(), sourceId,
                metalake, catalog, database, schema, table, connectionId);
    }

    /**
     * dbx 中已保存的连接，供导入弹窗的「连接」下拉。
     *
     * <p>有了它就不必再让用户手写 {@code extraConfig.connectionId}。
     * 返回的是脱敏摘要 —— dbx 的原始接口会明文返回数据库密码。
     */
    @Operation(
            summary = "列出 dbx 已保存连接（脱敏）",
            description = "给导入弹窗的连接下拉，避免手写 extraConfig.connectionId。返回脱敏摘要，不含密码。sourceId 必须是 DBX 类型。")
    @GetMapping("/sync/dbx-connections")
    public List<DbxConnectionSummary> dbxConnections(
            @Parameter(description = "DBX 类型的元数据服务 id", required = true)
            @RequestParam long sourceId) {
        return syncService.listDbxConnections(TenantContextHolder.require(), sourceId);
    }

    /**
     * 二（续）、提交导入任务。
     *
     * <p><b>立刻返回任务 id，导入在后台跑</b>：按数据目录导入可能是上万张表，
     * 同步等待必然超时。前端拿 id 轮询 {@code /sync/jobs/{id}} 显示进度。
     */
    @Operation(
            summary = "提交外部元数据导入任务",
            description = """
                    第二种写入方式：从 Gravitino/dbx 异步导入。立刻返回 {jobId}，导入在后台跑。
                    用 GET /api/meta/sync/jobs/{id} 轮询，看 finished 决定是否继续。
                    scope：CATALOG / SCHEMA / TABLE（TABLE 时 tables 必填）。
                    贴本地建表语句请用 POST /api/meta/ddl，不要用本接口。
                    """)
    @PostMapping("/sync")
    public Map<String, Object> sync(@Valid @RequestBody MetaSyncRequest request) {
        authz.require("catalog:admin");
        long jobId = syncService.submitSync(TenantContextHolder.require(), request);
        return Map.of("jobId", jobId);
    }

    /** 查导入进度。前端按 {@code finished} 决定是否继续轮询。 */
    @Operation(
            summary = "查询导入任务进度",
            description = "按 jobId 查进度。finished=true 即可停止轮询。jobId 来自 POST /api/meta/sync。")
    @GetMapping("/sync/jobs/{id}")
    public SyncJobResponse syncJob(
            @Parameter(description = "导入任务 id", required = true)
            @PathVariable long id) {
        return syncService.getJob(TenantContextHolder.require(), id);
    }

    /** 最近的导入任务。 */
    @Operation(
            summary = "列出最近的导入任务",
            description = "当前项目最近若干条同步任务，默认 10 条。")
    @GetMapping("/sync/jobs")
    public List<SyncJobResponse> syncJobs(
            @Parameter(description = "最多返回条数")
            @RequestParam(defaultValue = "10") int limit) {
        return syncService.listJobs(TenantContextHolder.require(), limit);
    }

    /** 三、手工修改。改完该行 source 会变成 MANUAL，后续同步不再覆盖它。 */
    @Operation(
            summary = "手工修改元数据表描述",
            description = """
                    第三种写入：只改 tableType、中文名 comment、备注 remark。库名表名不可改。
                    提交后该行 source=MANUAL，后续同步默认不再覆盖（除非 overwriteManual=true）。
                    """)
    @PutMapping("/tables/{id}")
    public MetaTableResponse updateTable(
            @Parameter(description = "元数据表 id", required = true)
            @PathVariable long id,
            @Valid @RequestBody MetaTableUpdateRequest request) {
        authz.require("catalog:admin");
        return catalogService.updateTable(TenantContextHolder.require(), id, request);
    }

    @Operation(
            summary = "手工修改元数据字段描述",
            description = "可改 dataType、中文名、备注。字段名不可改。提交后 source=MANUAL。")
    @PutMapping("/columns/{id}")
    public MetaColumnResponse updateColumn(
            @Parameter(description = "元数据字段 id", required = true)
            @PathVariable long id,
            @Valid @RequestBody MetaColumnUpdateRequest request) {
        authz.require("catalog:admin");
        return catalogService.updateColumn(TenantContextHolder.require(), id, request);
    }

    // ---------------- 删除 ----------------

    /** 删除元数据表<b>不会</b>影响已保存的血缘 —— 两者隔离存储。 */
    @Operation(
            summary = "删除元数据表",
            description = "只删元数据目录中的表结构，不影响已保存血缘。删错只会影响之后字段级解析缺列。")
    @DeleteMapping("/tables/{id}")
    public void deleteTable(
            @Parameter(description = "元数据表 id", required = true)
            @PathVariable long id) {
        authz.require("catalog:admin");
        catalogService.deleteTable(TenantContextHolder.require(), id);
    }

    @Operation(
            summary = "删除元数据字段",
            description = "只删该列的元数据定义，不影响已保存血缘。")
    @DeleteMapping("/columns/{id}")
    public void deleteColumn(
            @Parameter(description = "元数据字段 id", required = true)
            @PathVariable long id) {
        authz.require("catalog:admin");
        catalogService.deleteColumn(TenantContextHolder.require(), id);
    }
}
