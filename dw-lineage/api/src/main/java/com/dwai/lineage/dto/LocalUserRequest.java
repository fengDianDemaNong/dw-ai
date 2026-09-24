package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新建 / 更新本地账号（standard 模式）。
 *
 * <p>与 {@link TenantRequest} 同一套路数：{@code username} 与 {@code password}
 * <b>只在新建时生效</b>，更新接口一律忽略 —— 用户名是账号的稳定标识（外部记录可能
 * 已经按它写了日志、配了权限），改名会让那些引用失配；改密码则是一条独立的高风险
 * 操作，走 {@code PUT /api/users/{id}/password}，需要单独确认。
 *
 * @param admin  是否管理员。新建时缺省为 false；更新时 null 表示不动
 * @param status 1-启用 0-停用。新建时忽略（一律启用）；停用建议走
 *               {@code PUT /api/users/{id}/status}，走本接口也可以
 */
@Schema(description = "新建或更新本地账号。username / password 仅新建生效，更新接口会忽略。")
public record LocalUserRequest(

        @Schema(description = "登录名，稳定标识，仅新建时写入", example = "zhangsan")
        @NotBlank(message = "用户名不能为空")
        @Size(max = 64, message = "用户名长度不能超过 64")
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]*$",
                message = "用户名只能用字母、数字、点、下划线和短横线，且以字母或数字开头")
        String username,

        @Schema(description = "展示名称", example = "张三")
        @NotBlank(message = "显示名不能为空")
        @Size(max = 128, message = "显示名长度不能超过 128")
        String displayName,

        @Schema(description = "初始密码，仅新建时使用；至少 4 位", example = "123456")
        @Size(min = 4, max = 128, message = "密码长度需在 4 到 128 之间")
        String password,

        @Schema(description = "是否管理员。新建缺省 false；更新传 null 表示不改", example = "false")
        Boolean admin,

        @Schema(description = "1 启用 / 0 停用；新建时忽略，一律启用", example = "1")
        Integer status) {

    public boolean adminOrDefault() {
        return Boolean.TRUE.equals(admin);
    }

    public int statusOrDefault() {
        return status == null ? 1 : (status == 0 ? 0 : 1);
    }
}
