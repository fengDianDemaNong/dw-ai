package com.dwai.lineage.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.dwai.lineage.service.GravitinoMetadataServic;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Gravitino 元数据浏览。
 *
 * <p>所有接口都接受可选的 {@code sourceId}，指向「元数据服务」页面上配置的某条 Gravitino 服务。
 * 不传时退回 yml 里的 {@code gravitino.url}，保持旧调用方可用。
 */
@Tag(name = "Gravitino直连（旧）")
@RestController
@RequestMapping("/api/mate")
//@CrossOrigin(origins = "*")
public class GravitinoMetadataController {

    private final GravitinoMetadataServic metadataService;

    public GravitinoMetadataController(GravitinoMetadataServic metadataService) {
        this.metadataService = metadataService;
    }

    @Operation(
            summary = "列出 Gravitino metalake",
            description = """
                    旧接口，路径拼写为 matelakes。可选 sourceId 指向已配置的 Gravitino 服务；不传则用 yml 的 gravitino.url。
                    新代码请用 GET /api/meta/sync/browse?sourceId=。
                    """)
    @GetMapping("/matelakes")
    public ResponseEntity<Object> getSupportedMateLakes(
            @Parameter(description = "元数据服务 id；不传则用 yml 默认 Gravitino 地址")
            @RequestParam(required = false) Long sourceId) {
        List<String> supportedMateLakes = metadataService.getSupportedMateLakes(sourceId);
        return ResponseEntity.ok().body(supportedMateLakes);
    }

    @Operation(
            summary = "列出某 metalake 下的 catalog",
            description = "旧接口。mateLake 参数名保持历史拼写。新代码请用 /api/meta/sync/browse。")
    @GetMapping("/catalogs")
    public ResponseEntity<Object> getSupportedCatalogs(
            @Parameter(description = "元数据服务 id；不传则用 yml 默认地址")
            @RequestParam(required = false) Long sourceId,
            @Parameter(description = "metalake 名称（参数名历史拼写为 mateLake）", required = true)
            @RequestParam String mateLake) {
        List<String> supportedCatalogs = metadataService.getSupportedCatalogs(sourceId, mateLake);
        return ResponseEntity.ok().body(supportedCatalogs);
    }

    @Operation(
            summary = "列出某 catalog 下的 schema",
            description = """
                    旧接口，请求体是扁平 Map：sourceId（可选）、mateLake、catalog。
                    新代码请用 GET /api/meta/sync/browse。
                    """)
    @PostMapping("/schemas")
    public ResponseEntity<Object> getSupportedSchemas(
            @RequestBody Map<String, String> requestParams) {
        Long sourceId = longParam(requestParams, "sourceId");
        String mateLake = requestParams.get("mateLake");
        String catalog = requestParams.get("catalog");
        List<String> supportedSchemas = metadataService.getSupportedSchemas(sourceId, mateLake, catalog);
        return ResponseEntity.ok().body(supportedSchemas);
    }

    @Operation(
            summary = "用 Gravitino 元数据解析字段级血缘（旧）",
            description = """
                    旧接口。请求体扁平 Map：querySql、可选 columnName / sourceId / mateLake / catalog。
                    新代码请用 POST /api/lineage/analyze，并把 sourceId 指向 metadata-sources 里的配置。
                    """)
    @PostMapping("/lineage/analyze")
    public ResponseEntity<Object> analyzeSqlLineage(
            @RequestBody
            @Schema(description = "扁平键值：querySql 必填；sourceId、mateLake、catalog、columnName 可选")
            Map<String, String> requestParams) {
        Long sourceId = longParam(requestParams, "sourceId");
        String mateLake = requestParams.get("mateLake");
        String catalog = requestParams.get("catalog");
        String columnName = requestParams.get("columnName");
        String querySql = requestParams.get("querySql");
        com.dwai.lineage.dto.LineageGraph result =
                metadataService.analyzeSqlLineage(sourceId, mateLake, catalog, columnName, querySql);
        return ResponseEntity.ok().body(result);
    }

    /** 请求体是 Map&lt;String,String&gt;，sourceId 到这里是字符串，需要自己转。 */
    private static Long longParam(Map<String, String> params, String name) {
        String raw = params.get(name);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 不是合法的数字: " + raw);
        }
    }
}
