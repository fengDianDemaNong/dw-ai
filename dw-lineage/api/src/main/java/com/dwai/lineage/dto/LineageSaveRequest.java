package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解析并保存血缘。
 *
 * <p>刻意<b>不</b>接收前端已经拿到的血缘图，而是拿 SQL 重新解析一次再存 ——
 * 前端传来的图是可以被篡改的，而且它经过了列过滤（{@code columnName}）等展示层处理，
 * 存进去的会是残缺的血缘。多花一次解析换数据可信。
 *
 * @param sourceId 可选的外部元数据来源，语义与 {@code /api/lineage/analyze} 一致
 */
@Schema(description = "解析并保存血缘。只传 SQL，不要回传已有图。")
public record LineageSaveRequest(

        @Schema(description = "方言，取值见 GET /api/dialects", example = "doris", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "数据库类型不能为空")
        String dbType,

        @Schema(description = "待解析并落库的 SQL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "SQL 不能为空")
        @Size(max = LineageAnalyzeRequest.MAX_SQL_LENGTH,
                message = "SQL 长度超过上限，请拆分后再保存")
        String querySql,

        @Schema(description = "true 时把 SQL 内 CREATE TABLE 当作表结构")
        Boolean isCreateTable,

        @Schema(description = "外部元数据服务 id，语义同 /api/lineage/analyze")
        Long sourceId,

        @Schema(description = "Gravitino metalake")
        String metalake,

        @Schema(description = "Gravitino catalog")
        String catalog) {

    public boolean createTableAsMetadata() {
        return Boolean.TRUE.equals(isCreateTable);
    }
}
