package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 更新项目。
 *
 * <p>与 {@link TenantRequest} 同理，{@code code} 只在新建时生效。
 *
 * @param status 1-启用 0-停用；新建时忽略，一律为启用
 */
@Schema(description = "新建或更新项目。路径须带归属租户。code 仅新建生效。")
public record ProjectRequest(

        @Schema(description = "项目编码，稳定标识，仅新建时写入", example = "dw")
        @NotBlank(message = "项目编码不能为空")
        @Size(max = 64, message = "项目编码长度不能超过 64")
        @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
                message = "项目编码只能用小写字母、数字、下划线和短横线，且以字母或数字开头")
        String code,

        @Schema(description = "项目显示名称", example = "数仓")
        @NotBlank(message = "项目名称不能为空")
        @Size(max = 128, message = "项目名称长度不能超过 128")
        String name,

        @Schema(description = "项目说明")
        @Size(max = 512, message = "项目描述长度不能超过 512")
        String description,

        @Schema(description = "1 启用 / 0 停用；新建时忽略，一律启用", example = "1")
        Integer status) {

    public int statusOrDefault() {
        return status == null ? 1 : (status == 0 ? 0 : 1);
    }
}
