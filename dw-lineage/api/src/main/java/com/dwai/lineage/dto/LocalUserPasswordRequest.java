package com.dwai.lineage.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 管理员给别人重置密码。
 *
 * <p>和「改自己的密码」（{@code PUT /api/auth/password}）是<b>两条不同的路径</b>，
 * 刻意不复用同一个请求体：那条要 {@code currentPassword}（证明你是本人），
 * 这条不要 —— 管理员本来也不知道别人的旧密码。
 *
 * <p>正因为少了「证明你是本人」这一步，这条路径的权限检查必须更严：
 * 只有管理员可用，且重置后立刻撤销该账号的全部刷新令牌
 * （见 {@code LocalUserService.resetPassword}）。
 *
 * <p><b>不提供「查看密码」</b>：库里只有 BCrypt 哈希，谁都取不回明文。
 * 忘了密码只能重置，这是唯一正确的形态。
 *
 * @param password 新密码，至少 4 位
 */
@Schema(description = "管理员重置指定账号的密码。")
public record LocalUserPasswordRequest(

        @Schema(description = "新密码，至少 4 位", example = "123456", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "请填写新密码")
        @Size(min = 4, max = 128, message = "密码长度需在 4 到 128 之间")
        String password) {
}
