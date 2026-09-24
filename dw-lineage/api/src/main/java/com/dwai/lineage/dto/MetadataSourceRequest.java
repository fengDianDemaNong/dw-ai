package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 新建/更新元数据服务配置。
 *
 * @param type        {@code GRAVITINO} 或 {@code DBX}
 * @param baseUrl     服务地址，如 {@code http://localhost:8090} / {@code http://localhost:4224}
 * @param credential  凭据明文。<b>更新时留空表示不修改</b>，保持库中原值；
 *                    Gravitino 当前匿名访问，可不填
 * @param extraConfig 各类型的差异化配置（JSON 文本）。DBX 用它指定要连哪个连接：
 *                    {@code {"connectionId":"xxx","database":"xxx","schema":"public"}}
 * @param priority    数字小的优先，多个来源时决定串联顺序
 */
@Schema(description = "新建或更新元数据服务。更新时 credential 留空表示不改原值。")
public record MetadataSourceRequest(

        @Schema(description = "显示名称", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "名称不能为空")
        @Size(max = 128, message = "名称长度不能超过 128")
        String name,

        @Schema(description = "GRAVITINO 或 DBX", example = "GRAVITINO", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "类型不能为空")
        String type,

        @Schema(description = "服务地址", example = "http://localhost:8090", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "服务地址不能为空")
        @Size(max = 512, message = "服务地址长度不能超过 512")
        String baseUrl,

        @Schema(description = "凭据明文；更新时留空表示不修改。Gravitino 匿名可不填")
        String credential,

        @Schema(description = "差异化 JSON。DBX 例：{\"connectionId\":\"xxx\",\"database\":\"xxx\",\"schema\":\"public\"}")
        String extraConfig,

        @Schema(description = "数字越小越优先，默认 100")
        @Min(value = 0, message = "优先级不能为负")
        @Max(value = 9999, message = "优先级不能超过 9999")
        Integer priority,

        @Schema(description = "是否启用，默认 true")
        Boolean enabled) {

    public int priorityOrDefault() {
        return priority == null ? 100 : priority;
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
