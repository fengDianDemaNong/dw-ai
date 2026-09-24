package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.LocalUserRow;

import java.time.LocalDateTime;

/**
 * 本地账号（standard 模式）对外的形态。
 *
 * <p><b>不包含 {@code passwordHash}，也不包含刷新令牌。</b>
 * 这不是「顺手省掉一个字段」：账号列表是会打到前端、会进日志、会被贴进群里的东西，
 * 一旦把哈希放进去，即使只是 BCrypt，也等于把离线爆破的素材散发了一遍。
 * 记录类型有 {@code toString()}，漏一个字段就是一次事故 —— 所以这里用显式的
 * {@link #from} 白名单式赋值，而不是把行对象直接透出去。
 *
 * <p>同时给出 {@code status} 原值与 {@code enabled} 派生值，与 {@link TenantResponse}
 * 一致：原值用于回填表单，派生值用于渲染，省掉前端各处写 {@code status === 1}。
 */
public record LocalUserResponse(
        long id,
        String username,
        String displayName,
        int status,
        boolean enabled,
        boolean admin,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static LocalUserResponse from(LocalUserRow row) {
        return new LocalUserResponse(
                row.id(),
                row.username(),
                row.displayName(),
                row.status(),
                row.enabled(),
                row.isAdmin(),
                row.createdAt(),
                row.updatedAt());
    }
}
