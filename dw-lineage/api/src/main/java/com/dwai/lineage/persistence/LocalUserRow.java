package com.dwai.lineage.persistence;

import java.time.LocalDateTime;

/**
 * 本地账号（standard 模式的认证主体）。
 *
 * <p>不带租户维度 —— 登录发生在「知道租户之前」，账号是全局的，
 * 见 V3 迁移脚本与 {@code TenantIsolationArchTest} 的豁免说明。
 *
 * <h2>为什么权限只有一个布尔</h2>
 *
 * <p>{@code isAdmin} 决定这个账号能不能管理别人的账号。刻意做成布尔而不是角色体系：
 * standard 是「自己装一套、自己用」的单租户部署，真实的权限需求只有
 * 「管理员 / 非管理员」这一刀。加角色表、用户-角色关联表、再做角色继承，
 * 在当前规模下是没有对应问题的解法。
 *
 * <p>这条边界是刻意的，不要在需要「项目级授权」时顺手把这个布尔撑成角色字符串 ——
 * 那时正确的做法是补一张授权表并在服务层检查，见 ADR-0011 末尾的遗留说明。
 */
public record LocalUserRow(
        long id,
        String username,
        String displayName,
        String passwordHash,
        int status,
        boolean isAdmin,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** status 取值：1 启用、0 停用。停用后拒绝登录，不做物理删除。 */
    public boolean enabled() {
        return status == 1;
    }
}
