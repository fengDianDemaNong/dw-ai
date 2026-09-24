package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * SQL 语法校验请求。
 *
 * @param dbType   方言
 * @param querySql 待校验的 SQL
 */
@Schema(description = "SQL 语法校验请求，不计算血缘。")
public record SqlValidateRequest(

        @Schema(description = "方言", example = "doris", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "数据库类型不能为空")
        String dbType,

        @Schema(description = "待校验 SQL", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "SQL 不能为空")
        @Size(max = LineageAnalyzeRequest.MAX_SQL_LENGTH,
                message = "SQL 长度超过上限，请拆分后再校验")
        String querySql) {
}
