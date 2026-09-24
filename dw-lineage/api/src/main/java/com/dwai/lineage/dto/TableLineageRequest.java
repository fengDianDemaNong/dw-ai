package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 表级血缘请求。不需要元数据，因此没有 isCreateTable 参数。
 */
@Schema(description = "表级血缘请求。不需要表结构元数据。")
public record TableLineageRequest(

        @Schema(description = "方言", example = "doris", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "数据库类型不能为空")
        String dbType,

        @Schema(description = "待解析 SQL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "SQL 不能为空")
        @Size(max = LineageAnalyzeRequest.MAX_SQL_LENGTH,
                message = "SQL 长度超过上限，请拆分后再解析")
        String querySql) {
}
