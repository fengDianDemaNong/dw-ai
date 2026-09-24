package com.dwai.lineage.persistence;

import com.dwai.lineage.tenant.LineageContext;

import java.util.List;
import java.util.Optional;

/**
 * 元数据服务配置的读写。
 *
 * <p>与 {@link LineageRepository} 同一约定：<b>每个方法的第一个参数强制为
 * {@link LineageContext}</b>，实现里所有 SQL 都必须带 {@code tenant_id} 过滤。
 *
 * <p>注意这张表<b>只按租户隔离</b>，不按项目 —— 同一租户下所有项目共用一份元数据服务配置。
 * 把租户放在签名里而不是从 ThreadLocal 隐式读取，是为了让「漏带租户」在编译期就暴露。
 */
public interface MetadataSourceRepository {

    /** 全部配置，按 priority 升序（数字小的优先）。 */
    List<MetadataSourceRow> list(LineageContext ctx);

    /** 仅启用的配置，按 priority 升序。 */
    List<MetadataSourceRow> listEnabled(LineageContext ctx);

    Optional<MetadataSourceRow> findById(LineageContext ctx, long id);

    /** @return 新记录的 id */
    long insert(LineageContext ctx, MetadataSourceRow row);

    /**
     * 更新。
     *
     * @param keepCredential true 表示不改凭据（前端没有回传明文时用），保持库中原值
     * @return 是否命中了记录
     */
    boolean update(LineageContext ctx, long id, MetadataSourceRow row, boolean keepCredential);

    boolean delete(LineageContext ctx, long id);
}
