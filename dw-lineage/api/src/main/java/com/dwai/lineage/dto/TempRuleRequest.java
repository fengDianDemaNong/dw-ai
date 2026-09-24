package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 更新临时库表规则。
 *
 * @param catalogName 留空表示对该项目所有数据目录生效
 * @param target      {@code SCHEMA}（库）/ {@code TABLE}（表）
 * @param matchType   {@code GLOB}（通配符）/ {@code REGEX}（正则），留空按 GLOB
 * @param enabled     留空按启用
 */
@Schema(description = "临时库表识别规则。写完请调 GET /api/data-catalogs/temp-rules/test 试算。")
public record TempRuleRequest(

        @Schema(description = "限定在哪个数据目录；留空表示本项目全部目录")
        @Size(max = 128, message = "数据目录名称长度不能超过 128")
        String catalogName,

        @Schema(description = "SCHEMA（库）或 TABLE（表）", example = "TABLE", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请指定规则作用对象：SCHEMA（库）/ TABLE（表）")
        String target,

        @Schema(description = "GLOB（默认）或 REGEX")
        String matchType,

        @Schema(description = "匹配表达式，如 tmp_* 或 ^tmp_.*", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "匹配表达式不能为空")
        @Size(max = 256, message = "匹配表达式长度不能超过 256")
        String pattern,

        @Schema(description = "是否启用，默认 true")
        Boolean enabled,

        @Schema(description = "说明")
        @Size(max = 512, message = "说明长度不能超过 512")
        String description) {

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
