package com.dwai.lineage.service;

import com.dwai.lineage.dto.ActiveContextResponse;
import com.dwai.lineage.dto.ProjectRequest;
import com.dwai.lineage.dto.ProjectResponse;
import com.dwai.lineage.dto.TenantRequest;
import com.dwai.lineage.dto.TenantResponse;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;

/**
 * 租户与项目的管理面。
 *
 * <p>这一层是「租户的定义者」，因此方法不收 {@link LineageContext} ——
 * 要列出所有租户，本来就不能先限定在某个租户里。业务侧的一切读写仍然照旧走
 * {@code TenantContextHolder.require()}。
 *
 * <p><b>创建租户/项目时会自动种一条内置 CATALOG 元数据源</b>，
 * 否则新租户的本地元数据目录参与不了解析链，见 {@link #createTenant}。
 */
public interface TenantAdminService {

    /** 全部租户，内嵌各自的项目列表。 */
    List<TenantResponse> listTenants();

    List<ProjectResponse> listProjects(long tenantId);

    /**
     * 新建租户。
     *
     * <p>同一个事务里连带做两件事，缺一个新租户就不可用：
     * <ol>
     *   <li>建一个 {@code code=default} 的默认项目 —— 业务数据都挂在项目下，没有项目无处可写</li>
     *   <li>为该 (租户, 默认项目) 种一条 {@code type=CATALOG} 的内置元数据源 ——
     *       {@code V3} 迁移只给 (1,1) 硬编码了一条，新租户不补种就没有本地元数据目录</li>
     * </ol>
     */
    TenantResponse createTenant(TenantRequest request);

    TenantResponse updateTenant(long id, TenantRequest request);

    /**
     * 删除租户。
     *
     * <p>仅当其下所有项目都没有业务数据时才真删；否则抛
     * {@link com.dwai.lineage.exception.ResourceInUseException}，提示改用停用。
     */
    void deleteTenant(long id);

    /** 新建项目。同样会为它种一条内置 CATALOG 元数据源。 */
    ProjectResponse createProject(long tenantId, ProjectRequest request);

    /**
     * 更新项目。
     *
     * <p>{@code tenantId} 不是冗余参数：带上它，仓储那条 SQL 才能写成
     * {@code where tenant_id = ? and id = ?}。只按项目 id 查会产生一条没有租户过滤的
     * 读取，{@code TenantIsolationArchTest} 会（正确地）拦下它。
     */
    ProjectResponse updateProject(long tenantId, long projectId, ProjectRequest request);

    void deleteProject(long tenantId, long projectId);

    /** 当前请求生效的上下文，供前端启动时自检。 */
    ActiveContextResponse describe(LineageContext ctx);

    /** 组织平台下发的同一项目 id，按 tenant.code / project.code 落成本地数字 id。 */
    ProjectResponse upsertFromOrg(String orgProjectId, String tenantCode, String name, String code);

    void deleteFromOrg(String orgProjectId, String tenantCode);
}
