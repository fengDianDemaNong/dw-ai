package com.dwai.lineage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import com.dwai.lineage.dto.DataCatalogRequest;
import com.dwai.lineage.dto.DataCatalogResponse;
import com.dwai.lineage.dto.TempRuleRequest;
import com.dwai.lineage.dto.TempRuleResponse;
import com.dwai.lineage.dto.TempRuleTestResponse;
import com.dwai.lineage.service.DataCatalogService;
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
 * 数据目录与临时库表规则的配置接口。
 *
 * <p>对应「配置 - 数据目录」页面。租户上下文由 {@code TenantInterceptor} 从请求头解析。
 */
@Tag(name = "数据目录配置")
@RestController
@RequestMapping("/api/data-catalogs")
public class DataCatalogController {

    private final DataCatalogService service;

    public DataCatalogController(DataCatalogService service) {
        this.service = service;
    }

    // ==== 数据目录 ====

    @Operation(
            summary = "列出当前项目的数据目录",
            description = "配置页上的目录清单（可被设为默认、出现在表全名第一段）。血缘里实际出现过的目录名见 GET /api/catalog/catalogs。")
    @GetMapping
    public List<DataCatalogResponse> list() {
        return service.listCatalogs(TenantContextHolder.require());
    }

    @Operation(
            summary = "新建数据目录",
            description = "名称会成为表全名第一段 catalog.schema.table，不能含点号。新建后可再调「设为默认目录」。")
    @PostMapping
    public DataCatalogResponse create(@Valid @RequestBody DataCatalogRequest request) {
        return service.createCatalog(TenantContextHolder.require(), request);
    }

    @Operation(
            summary = "更新数据目录说明",
            description = "按 id 改名称或说明。已被表引用时改名需谨慎（全名会变）。")
    @PutMapping("/{id}")
    public DataCatalogResponse update(
            @Parameter(description = "数据目录 id", required = true)
            @PathVariable long id,
            @Valid @RequestBody DataCatalogRequest request) {
        return service.updateCatalog(TenantContextHolder.require(), id, request);
    }

    /** 设为默认目录。默认目录只能有一个，设置新的会自动把旧的取消。 */
    @Operation(
            summary = "设为默认数据目录",
            description = "默认目录全局只能有一个；设置新的会自动取消旧的。导入 DDL 未写三段名、也未指定 catalogName 时落到默认目录。")
    @PostMapping("/{id}/default")
    public DataCatalogResponse setDefault(
            @Parameter(description = "数据目录 id", required = true)
            @PathVariable long id) {
        return service.setDefault(TenantContextHolder.require(), id);
    }

    /** 默认目录、以及下面还有表的目录会返回 409。 */
    @Operation(
            summary = "删除数据目录",
            description = "默认目录、或下面还有表的目录会返回 409，需先改默认或迁走表。")
    @DeleteMapping("/{id}")
    public void delete(
            @Parameter(description = "数据目录 id", required = true)
            @PathVariable long id) {
        service.deleteCatalog(TenantContextHolder.require(), id);
    }

    // ==== 临时库表规则 ====

    @Operation(
            summary = "列出临时库表规则",
            description = "当前项目用于识别临时 schema/表的规则。解析和保存时默认会按这些规则从图上滤掉临时对象。")
    @GetMapping("/temp-rules")
    public List<TempRuleResponse> listRules() {
        return service.listRules(TenantContextHolder.require());
    }

    @Operation(
            summary = "新建临时库表规则",
            description = "target：SCHEMA 或 TABLE。matchType：GLOB（默认）或 REGEX。写完应用「试算临时规则」确认会命中。")
    @PostMapping("/temp-rules")
    public TempRuleResponse createRule(@Valid @RequestBody TempRuleRequest request) {
        return service.createRule(TenantContextHolder.require(), request);
    }

    @Operation(
            summary = "更新临时库表规则",
            description = "按 id 改匹配对象、表达式或启停。改完建议再调试算接口。")
    @PutMapping("/temp-rules/{id}")
    public TempRuleResponse updateRule(
            @Parameter(description = "规则 id", required = true)
            @PathVariable long id,
            @Valid @RequestBody TempRuleRequest request) {
        return service.updateRule(TenantContextHolder.require(), id, request);
    }

    @Operation(
            summary = "删除临时库表规则",
            description = "删除后对应名称不再被当成临时表（除非还命中其它规则）。")
    @DeleteMapping("/temp-rules/{id}")
    public void deleteRule(
            @Parameter(description = "规则 id", required = true)
            @PathVariable long id) {
        service.deleteRule(TenantContextHolder.require(), id);
    }

    /**
     * 试算：给一个库名或表名，返回会不会被判为临时、命中哪条规则。
     *
     * <p>这个接口不是锦上添花。规则写错了不会报错，只会默默匹配不上，
     * 用户要等到血缘图不对才发现 —— 试算是唯一能当场确认的手段。
     *
     * @param name 库名或表名，也可以是 {@code catalog.schema.table} 全名
     */
    @Operation(
            summary = "试算名称是否被判为临时",
            description = """
                    传入库名、表名或 catalog.schema.table 全名，返回是否判为临时以及命中哪条规则。
                    规则写错不会报错，只会匹配不上；保存规则后应先调本接口确认。
                    """)
    @GetMapping("/temp-rules/test")
    public TempRuleTestResponse testRule(
            @Parameter(description = "库名、表名或 catalog.schema.table", required = true, example = "tmp.ods_order")
            @RequestParam String name) {
        return service.testRule(TenantContextHolder.require(), name);
    }
}
