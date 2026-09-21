package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 更新租户。
 *
 * <p>{@code code} 只在新建时生效：它是租户的稳定标识，改了会让所有引用它的外部记录失配，
 * 更新接口一律忽略该字段。
 *
 * @param status 1-启用 0-停用；新建时忽略，一律为启用
 */
@Schema(description = "新建或更新租户。code 仅新建生效，更新接口会忽略。")
public record TenantRequest(

        @Schema(description = "租户编码，稳定标识，仅新建时写入", example = "acme")
        @NotBlank(message = "租户编码不能为空")
        @Size(max = 64, message = "租户编码长度不能超过 64")
        @Pattern(regexp = "^[a-z0-9][a-z0-9_-]*$",
                message = "租户编码只能用小写字母、数字、下划线和短横线，且以字母或数字开头")
        String code,

        @Schema(description = "租户显示名称", example = "示例租户")
        @NotBlank(message = "租户名称不能为空")
        @Size(max = 128, message = "租户名称长度不能超过 128")
        String name,

        @Schema(description = "1 启用 / 0 停用；新建时忽略，一律启用", example = "1")
        Integer status) {

    public int statusOrDefault() {
        return status == null ? 1 : (status == 0 ? 0 : 1);
    }
}
