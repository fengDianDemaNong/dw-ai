package com.dwai.lineage.persistence;

import com.dwai.lineage.tenant.LineageContext;

import java.util.List;
import java.util.Optional;

/**
 * 租户与项目自身的存取 —— 也就是「管理面」。
 *
 * <p>与本包其它仓储的区别：这里操作的 {@code tenant} / {@code project} 两张表是
 * <b>租户维度的定义者</b>，不是被租户隔离的业务数据。所以列表方法刻意不收
 * {@link LineageContext}：要列出所有租户，本来就不能先限定在某个租户里。
 *
 * <p>{@code project} 侧的方法一律带 {@code tenantId} 并落进 {@code where tenant_id = ?}，
 * 既是正确的归属约束，也让它们照常通过 {@code TenantIsolationArchTest}。
 * 只有 {@code tenant} 表自身的 SQL 无从带租户列，该测试对本文件按文件名整体豁免。
 *
 * <p>两张表合用一个仓储而不是拆成两个，是为了让上面那条豁免只覆盖一个文件。
 */
public interface TenantAdminRepository {

    // ---------- 租户 ----------

    List<TenantRow> listTenants();

    Optional<TenantRow> findTenant(long id);

    Optional<TenantRow> findTenantByCode(String code);

    long createTenant(String code, String name);

    /** @return 是否命中了记录 */
    boolean updateTenant(long id, String name, int status);

    boolean deleteTenant(long id);

    // ---------- 项目 ----------

    List<ProjectRow> listProjects(long tenantId);

    /** 一次取出多个租户下的全部项目，供列表页避免 N+1。 */
    List<ProjectRow> listAllProjects();

    Optional<ProjectRow> findProject(long tenantId, long projectId);

    Optional<ProjectRow> findProjectByCode(long tenantId, String code);

    long createProject(long tenantId, String code, String name, String description);

    boolean updateProject(long tenantId, long id, String name, String description, int status);

    boolean deleteProject(long tenantId, long id);

    /**
     * 该 (租户, 项目) 下的业务数据行数合计，用于删除前的保护检查。
     *
     * <p>统计 6 张业务表，外加 {@code metadata_source} 里<b>非内置</b>的那些 ——
     * 内置的 CATALOG 源是建项目时自动种的，把它算进去会让任何一个新建项目都永远删不掉。
     */
    long countBusinessRows(LineageContext ctx);
}
