package com.dwai.lineage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.dwai.lineage.dto.DialectInfo;
import com.dwai.lineage.dto.LineageAnalyzeRequest;
import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.dto.SqlValidateRequest;
import com.dwai.lineage.dto.SqlValidateResponse;
import com.dwai.lineage.dto.TableLineageRequest;
import com.dwai.lineage.dto.TableLineageResponse;
import com.dwai.lineage.service.LineageService;
import com.dwai.lineage.service.MetadataSourceService;
import com.dwai.lineage.service.SqlSyntaxService;
import com.dwai.lineage.service.TableLineageService;
import com.dwai.lineage.service.DataCatalogService;
import com.dwai.lineage.service.metadata.CatalogPolicy;
import com.dwai.lineage.service.metadata.provider.MetadataProvider;
import com.dwai.lineage.tenant.LineageContext;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "血缘解析")
@RestController
@RequestMapping("/api")
public class LineageController {

    private final LineageService lineageService;
    private final TableLineageService tableLineageService;
    private final SqlSyntaxService sqlSyntaxService;
    private final MetadataSourceService metadataSourceService;
    private final DataCatalogService dataCatalogService;

    public LineageController(LineageService lineageService,
                             TableLineageService tableLineageService,
                             SqlSyntaxService sqlSyntaxService,
                             MetadataSourceService metadataSourceService,
                             DataCatalogService dataCatalogService) {
        this.lineageService = lineageService;
        this.tableLineageService = tableLineageService;
        this.sqlSyntaxService = sqlSyntaxService;
        this.metadataSourceService = metadataSourceService;
        this.dataCatalogService = dataCatalogService;
    }

    /** 支持的数据库/引擎类型（仅名称，保留以兼容既有前端）。 */
    @Operation(
            summary = "列出支持的数据库类型名称",
            description = "只返回方言字符串列表（如 mysql、doris、hive），兼容旧前端。新调用请优先用「列出方言及能力」，那里会标明哪些方言不能从 CREATE TABLE 抽表结构。")
    @GetMapping("/dbType")
    public List<String> getSupportedDatabases() {
        return lineageService.getSupportedDatabases();
    }

    /**
     * 支持的方言及其能力。
     *
     * <p>部分方言（trino / presto / sqlserver）无法从 SQL 内的 CREATE TABLE 提取表结构，
     * 必须依赖外部元数据服务，前端据此提示用户。
     */
    @Operation(
            summary = "列出方言及能力",
            description = """
                    返回每种方言的名称、是否支持从 SQL 内 CREATE TABLE 提取表结构等能力。
                    解析前应先看这里：trino / presto / sqlserver 不能靠 isCreateTable，必须配置元数据服务或先导入 /api/meta。
                    """)
    @GetMapping("/dialects")
    public List<DialectInfo> getDialects() {
        return lineageService.getDialects();
    }

    /**
     * 解析 SQL 字段级血缘。
     *
     * <p>返回领域对象，由 Spring 统一序列化。
     *
     * <p>请求里带 {@code sourceId} 时，会用对应的元数据服务配置（Gravitino / dbx）
     * 作为表结构来源；不带则只用 SQL 内的 DDL 与结构推断。
     */
    @Operation(
            summary = "解析字段级血缘（不落库）",
            description = """
                    当场解析 SQL，返回字段级血缘图。结果不写入数据库。
                    适用：预览、调试、编辑器画图。要持久化请改调 POST /api/lineage/save。
                    只要「哪张表写到哪张表」、没有列结构时，用更快的 POST /api/lineage/table。
                    查已经保存过的图用 GET /api/lineage/graph，不要用本接口。
                    元数据：isCreateTable=true 时用 SQL 里的 CREATE TABLE；sourceId 指向已配置的 Gravitino/dbx；两者都没有则靠推断，缺列会进 unresolvedTables。
                    includeTemp 默认 false，与保存行为一致。多语句部分失败见 failedStatements。
                    """)
    @PostMapping(value = "/lineage/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    public LineageGraph analyzeSqlLineage(@Valid @RequestBody LineageAnalyzeRequest request) {
        LineageContext ctx = TenantContextHolder.require();
        MetadataProvider external = request.sourceId() == null ? null
                : metadataSourceService.externalProvider(
                        ctx, request.sourceId(), request.metalake(), request.catalog())
                .orElse(null);

        // 勾了「包含临时表」就不过滤；默认过滤，这样默认看到的图与保存进库的一致。
        // 注意用 withoutTempFilter() 而不是传 null —— 只关掉临时过滤，
        // 补全数据目录照做，否则图上的表名会退回两段，与库里存的对不上
        CatalogPolicy policy = dataCatalogService.policy(ctx);
        if (request.includeTempOrDefault()) {
            policy = policy.withoutTempFilter();
        }

        return lineageService.analyzeSqlLineage(
                request.dbType(),
                request.createTableAsMetadata(),
                request.columnName(),
                request.querySql(),
                external,
                policy);
    }

    /**
     * 表级血缘：只解析「哪张表写入、来自哪些表」。
     *
     * <p>不需要表结构元数据，比列级血缘快一个数量级，
     * 可作为列级血缘因元数据缺失而失败时的降级方案。
     */
    @Operation(
            summary = "解析表级血缘（不落库、无需元数据）",
            description = """
                    只回答「目标表来自哪些源表」，不展开字段。不需要 CREATE TABLE 或外部元数据。
                    适用：列级解析因缺元数据失败时的降级、快速看表依赖。
                    需要字段边请用 POST /api/lineage/analyze。本接口同样不落库。
                    """)
    @PostMapping("/lineage/table")
    public TableLineageResponse analyzeTableLineage(@Valid @RequestBody TableLineageRequest request) {
        return tableLineageService.analyze(request.dbType(), request.querySql());
    }

    /** SQL 语法校验，返回带行列号的错误，供编辑器标注。 */
    @Operation(
            summary = "校验 SQL 语法",
            description = """
                    按指定方言做语法检查，返回带行号列号的错误，供编辑器红线标注。
                    只检查能不能解析，不计算血缘。解析前可先调本接口。
                    """)
    @PostMapping("/sql/validate")
    public SqlValidateResponse validateSql(@Valid @RequestBody SqlValidateRequest request) {
        return sqlSyntaxService.validate(request.dbType(), request.querySql());
    }

    /** 指定方言的关键字，供编辑器做补全。 */
    @Operation(
            summary = "查询方言关键字",
            description = "返回指定方言的 SQL 关键字列表，供编辑器自动补全。dbType 取值见 GET /api/dbType 或 GET /api/dialects。")
    @GetMapping("/sql/keywords")
    public List<String> sqlKeywords(
            @Parameter(description = "方言名称，如 mysql、doris、hive", required = true, example = "doris")
            @RequestParam String dbType) {
        return sqlSyntaxService.keywords(dbType);
    }
}
