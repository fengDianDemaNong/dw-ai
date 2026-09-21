package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 更新数据目录。
 *
 * <p>名称限制得比项目编码还严：它会成为表全名的第一段（{@code catalog.schema.table}），
 * 而全名要按点号切分，名字里带点会把切分切错位。
 */
@Schema(description = "新建或更新数据目录。名称会成为表全名第一段，不能含点号。")
public record DataCatalogRequest(

        @Schema(description = "目录名，不能含点号", example = "hive", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "数据目录名称不能为空")
        @Size(max = 128, message = "数据目录名称长度不能超过 128")
        @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]*$",
                message = "数据目录名称只能用字母、数字、下划线和短横线，且以字母或数字开头（不能含点号）")
        String name,

        @Schema(description = "说明")
        @Size(max = 512, message = "说明长度不能超过 512")
        String description) {
}
