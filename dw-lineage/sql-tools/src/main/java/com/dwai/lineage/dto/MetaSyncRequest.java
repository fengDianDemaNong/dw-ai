package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import com.dwai.lineage.enums.SyncScope;

import java.util.List;

/**
 * 从外部元数据服务同步表结构。
 *
 * @param sourceId        要从哪条元数据服务同步（{@code GRAVITINO} 或 {@code DBX} 类型）
 * @param metalake        Gravitino 专用
 * @param catalog         Gravitino 专用
 * @param database        dbx 专用
 * @param schema          两者都用；dbx 的部分数据源可以为空
 * @param scope           导入范围：{@code CATALOG} 整个数据目录 / {@code SCHEMA} 整个库 /
 *                        {@code TABLE} 指定的若干张表。不传按 TABLE 处理
 * @param connectionId    dbx 中已保存的连接 id。不传则回落到该来源 extraConfig 里的配置 ——
 *                        页面上做成下拉，用户不必再手写 JSON
 * @param targetCatalog   导入到哪个数据目录。不传时沿用源端。
 *                        <b>库名与表名不可改</b>：改了之后 SQL 解析按 {@code schema.table}
 *                        就对不上，元数据也就失去了意义
 * @param tables          要同步的表名。仅 {@code scope=TABLE} 时必填 ——
 *                        其余范围由任务自己展开，不需要前端一个个列出来
 * @param overwriteManual 是否覆盖人工维护过的内容（{@code source=MANUAL} 的行）。
 *                        默认 false：用户刚补好的中文名不该被下一次同步冲掉
 */
@Schema(description = "从 Gravitino/dbx 异步导入元数据。立刻返回 jobId。")
public record MetaSyncRequest(

        @Schema(description = "元数据服务 id", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotNull(message = "请选择要同步的元数据服务")
        Long sourceId,

        @Schema(description = "Gravitino metalake")
        String metalake,

        @Schema(description = "Gravitino catalog")
        String catalog,

        @Schema(description = "dbx database")
        String database,

        @Schema(description = "schema / 库名")
        String schema,

        @Schema(description = "CATALOG / SCHEMA / TABLE，默认 TABLE")
        String scope,

        @Schema(description = "dbx 已保存连接 id")
        String connectionId,

        @Schema(description = "导入到本地哪个数据目录；不传则沿用源端。库名表名不可改。")
        String targetCatalog,

        @Schema(description = "scope=TABLE 时必填的远程表名列表")
        List<String> tables,

        @Schema(description = "是否覆盖手工维护行，默认 false")
        Boolean overwriteManual) {

    public boolean overwriteManualOrDefault() {
        return Boolean.TRUE.equals(overwriteManual);
    }

    public SyncScope scopeOrDefault() {
        return SyncScope.fromString(scope);
    }

    public List<String> tablesOrEmpty() {
        return tables == null ? List.of() : tables;
    }
}
