package com.dwai.lineage.service.impl;

import com.dwai.lineage.dto.ActiveContextResponse;
import com.dwai.lineage.dto.MetadataSourceRequest;
import com.dwai.lineage.dto.ProjectRequest;
import com.dwai.lineage.dto.ProjectResponse;
import com.dwai.lineage.dto.TenantRequest;
import com.dwai.lineage.dto.TenantResponse;
import com.dwai.lineage.exception.ResourceInUseException;
import com.dwai.lineage.persistence.MetadataSourceRepository;
import com.dwai.lineage.persistence.MetadataSourceRow;
import com.dwai.lineage.persistence.ProjectRow;
import com.dwai.lineage.persistence.TenantAdminRepository;
import com.dwai.lineage.persistence.TenantRow;
import com.dwai.lineage.service.MetadataSourceService;
import com.dwai.lineage.service.TenantAdminService;
import com.dwai.lineage.tenant.LineageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TenantAdminServiceImpl implements TenantAdminService {

    private static final Logger logger = LoggerFactory.getLogger(TenantAdminServiceImpl.class);

    /**
     * 默认租户/项目不允许删除。
     *
     * <p>与 {@code MetadataSourceServiceImpl} 保护内置 CATALOG 源同理：请求头缺失时
     * {@code TenantInterceptor} 会回落到这一对 id，删掉它们会让所有不带头的请求
     * （curl、swagger、健康检查脚本）指向一个不存在的租户。
     */
    private static final long DEFAULT_TENANT_ID = 1;
    private static final long DEFAULT_PROJECT_ID = 1;

    /** 新建<b>租户</b>时自动种的内置元数据源，字段与建表脚本里给租户 1 种的那条保持一致。 */
    private static final String CATALOG_SOURCE_NAME = "本地元数据目录";
    private static final String CATALOG_SOURCE_URL = "local://catalog";
    private static final int CATALOG_SOURCE_PRIORITY = 50;

    private final TenantAdminRepository repository;
    private final MetadataSourceService metadataSourceService;
    private final MetadataSourceRepository metadataSourceRepository;

    public TenantAdminServiceImpl(TenantAdminRepository repository,
                                  MetadataSourceService metadataSourceService,
                                  MetadataSourceRepository metadataSourceRepository) {
        this.repository = repository;
        this.metadataSourceService = metadataSourceService;
        this.metadataSourceRepository = metadataSourceRepository;
    }

    // ---------- 租户 ----------

    @Override
    public List<TenantResponse> listTenants() {
        // 一次取全部项目再按租户分组，避免每个租户查一次
        Map<Long, List<ProjectResponse>> byTenant = repository.listAllProjects().stream()
                .map(ProjectResponse::from)
                .collect(Collectors.groupingBy(ProjectResponse::tenantId));

        return repository.listTenants().stream()
                .map(t -> TenantResponse.from(t, byTenant.getOrDefault(t.id(), List.of())))
                .toList();
    }

    @Override
    public List<ProjectResponse> listProjects(long tenantId) {
        requireTenant(tenantId);
        return repository.listProjects(tenantId).stream().map(ProjectResponse::from).toList();
    }

    @Override
    @Transactional
    public TenantResponse createTenant(TenantRequest request) {
        String code = request.code().strip();
        // 先查一次给出准确提示；并发下仍可能撞唯一键，那时由 DuplicateKeyException 兜底
        if (repository.findTenantByCode(code).isPresent()) {
            throw new IllegalArgumentException("租户编码「" + code + "」已存在，请换一个");
        }

        long tenantId = repository.createTenant(code, request.name().strip());
        long projectId = repository.createProject(tenantId, "default", "默认项目",
                "创建租户时自动生成");
        seedCatalogSource(new LineageContext(tenantId, projectId));

        logger.info("新建租户, id={}, code={}, 默认项目 id={}", tenantId, code, projectId);
        return loadTenant(tenantId);
    }

    @Override
    public TenantResponse updateTenant(long id, TenantRequest request) {
        requireTenant(id);
        // code 不可改：它是稳定标识，改了会让外部引用失配
        repository.updateTenant(id, request.name().strip(), request.statusOrDefault());
        logger.info("更新租户, id={}, status={}", id, request.statusOrDefault());
        return loadTenant(id);
    }

    @Override
    @Transactional
    public void deleteTenant(long id) {
        TenantRow tenant = requireTenant(id);
        if (id == DEFAULT_TENANT_ID) {
            throw new IllegalArgumentException("「" + tenant.name()
                    + "」是系统默认租户（请求不带租户头时会落到它），不能删除。如不需要，请将其停用");
        }

        List<ProjectRow> projects = repository.listProjects(id);
        long rows = projects.stream()
                .mapToLong(p -> repository.countBusinessRows(new LineageContext(id, p.id())))
                .sum();
        if (rows > 0) {
            throw new ResourceInUseException("租户「" + tenant.name() + "」下还有 " + rows
                    + " 条业务数据（血缘、元数据或元数据服务配置），不能删除。"
                    + "请先清理这些数据，或改为将该租户停用");
        }

        for (ProjectRow project : projects) {
            repository.deleteProject(id, project.id());
        }
        // 元数据服务挂在租户上，租户没了才跟着删。
        // LineageContext 要求 projectId > 0，但这张表只按租户过滤，projectId 传什么都一样
        purgeCatalogSources(new LineageContext(id, DEFAULT_PROJECT_ID));
        repository.deleteTenant(id);
        logger.info("删除租户, id={}, code={}, 连带删除 {} 个空项目", id, tenant.code(), projects.size());
    }

    // ---------- 项目 ----------

    @Override
    @Transactional
    public ProjectResponse createProject(long tenantId, ProjectRequest request) {
        requireTenant(tenantId);
        String code = request.code().strip();
        boolean duplicated = repository.listProjects(tenantId).stream()
                .anyMatch(p -> p.code().equals(code));
        if (duplicated) {
            throw new IllegalArgumentException("该租户下已存在编码为「" + code + "」的项目，请换一个");
        }

        long projectId = repository.createProject(tenantId, code, request.name().strip(),
                emptyToNull(request.description()));
        // 不种 CATALOG 源：元数据服务是租户级的，建租户时已经种过一条，全租户共用

        logger.info("新建项目, tenantId={}, id={}, code={}", tenantId, projectId, code);
        return ProjectResponse.from(requireProject(tenantId, projectId));
    }

    @Override
    public ProjectResponse updateProject(long tenantId, long projectId, ProjectRequest request) {
        requireProject(tenantId, projectId);
        repository.updateProject(tenantId, projectId, request.name().strip(),
                emptyToNull(request.description()), request.statusOrDefault());
        logger.info("更新项目, tenantId={}, id={}, status={}", tenantId, projectId,
                request.statusOrDefault());
        return ProjectResponse.from(requireProject(tenantId, projectId));
    }

    @Override
    @Transactional
    public void deleteProject(long tenantId, long projectId) {
        ProjectRow project = requireProject(tenantId, projectId);
        if (projectId == DEFAULT_PROJECT_ID) {
            throw new IllegalArgumentException("「" + project.name()
                    + "」是系统默认项目（请求不带项目头时会落到它），不能删除。如不需要，请将其停用");
        }

        LineageContext ctx = new LineageContext(tenantId, projectId);
        long rows = repository.countBusinessRows(ctx);
        if (rows > 0) {
            throw new ResourceInUseException("项目「" + project.name() + "」下还有 " + rows
                    + " 条业务数据（血缘、元数据或元数据服务配置），不能删除。"
                    + "请先清理这些数据，或改为将该项目停用");
        }

        // 元数据服务是租户级的，不随项目删除
        repository.deleteProject(tenantId, projectId);
        logger.info("删除项目, tenantId={}, id={}", tenantId, projectId);
    }

    @Override
    @Transactional
    public ProjectResponse upsertFromOrg(String orgProjectId, String tenantCode, String name, String code) {
        if (orgProjectId == null || orgProjectId.isBlank()) {
            throw new IllegalArgumentException("需要项目 id");
        }
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new IllegalArgumentException("需要租户编码");
        }
        String projectCode = orgProjectId.strip();
        String codeKey = tenantCode.strip();
        TenantRow tenant = repository.findTenantByCode(codeKey).orElse(null);
        if (tenant == null) {
            try {
                long id = repository.createTenant(codeKey, codeKey);
                long defaultProject = repository.createProject(id, "default", "默认项目", "创建租户时自动生成");
                seedCatalogSource(new LineageContext(id, defaultProject));
                tenant = repository.findTenant(id).orElseThrow();
            } catch (DataIntegrityViolationException e) {
                tenant = repository.findTenantByCode(codeKey).orElseThrow();
            }
        }
        Optional<ProjectRow> existing = repository.findProjectByCode(tenant.id(), projectCode);
        String display = name == null || name.isBlank() ? projectCode : name.strip();
        if (existing.isPresent()) {
            ProjectRow row = existing.get();
            if (display.equals(row.name()) && row.status() == 1) {
                return ProjectResponse.from(requireProject(tenant.id(), row.id()));
            }
            repository.updateProject(tenant.id(), row.id(), display, row.description(), 1);
            return ProjectResponse.from(requireProject(tenant.id(), row.id()));
        }
        long projectId = repository.createProject(tenant.id(), projectCode, display,
                code == null || code.isBlank() ? null : code.strip());
        logger.info("从组织平台同步项目, tenantId={}, id={}, code={}", tenant.id(), projectId, projectCode);
        return ProjectResponse.from(requireProject(tenant.id(), projectId));
    }

    @Override
    @Transactional
    public void deleteFromOrg(String orgProjectId, String tenantCode) {
        if (orgProjectId == null || orgProjectId.isBlank()) return;
        if (tenantCode == null || tenantCode.isBlank()) return;
        Optional<TenantRow> tenant = repository.findTenantByCode(tenantCode.strip());
        if (tenant.isEmpty()) return;
        Optional<ProjectRow> project = repository.findProjectByCode(tenant.get().id(), orgProjectId.strip());
        if (project.isEmpty()) return;
        if (project.get().id() == DEFAULT_PROJECT_ID) return;
        repository.deleteProject(tenant.get().id(), project.get().id());
    }

    // ---------- 当前上下文 ----------

    @Override
    public ActiveContextResponse describe(LineageContext ctx) {
        Optional<TenantRow> tenant = repository.findTenant(ctx.tenantId());
        Optional<ProjectRow> project = repository.findProject(ctx.tenantId(), ctx.projectId());
        return new ActiveContextResponse(
                ctx.tenantId(),
                ctx.projectId(),
                tenant.map(TenantRow::name).orElse(null),
                project.map(ProjectRow::name).orElse(null),
                tenant.isPresent(),
                project.isPresent(),
                tenant.map(TenantRow::enabled).orElse(false),
                project.map(ProjectRow::enabled).orElse(false));
    }

    // ---------- 内部 ----------

    /**
     * 为新租户种内置的本地元数据目录。
     *
     * <p>走 {@code MetadataSourceService.create} 而不是自己拼一条 INSERT：字段默认值、
     * extraConfig 校验、加密边界都在那一层，另写一份迟早会漂移。
     * CATALOG 类型不需要凭据，因此没配 {@code METADATA_SECRET_KEY} 也能建租户。
     *
     * <p><b>每个租户一条，不是每个项目一条</b> —— 元数据服务是租户级共享的。
     * 传进来的 {@code LineageContext} 只有 tenantId 有意义。
     */
    private void seedCatalogSource(LineageContext ctx) {
        metadataSourceService.create(ctx, new MetadataSourceRequest(
                CATALOG_SOURCE_NAME, "CATALOG", CATALOG_SOURCE_URL,
                null, null, CATALOG_SOURCE_PRIORITY, true));
    }

    /**
     * 删租户前清掉它名下的全部元数据服务配置。
     *
     * <p>直接走仓储而不是 {@code MetadataSourceService.delete} —— 后者会以
     * 「内置来源不可删除」为由拒绝，那条保护是针对用户手动操作的，
     * 租户整个都要没了，它自然要跟着走。
     */
    private void purgeCatalogSources(LineageContext ctx) {
        for (MetadataSourceRow row : metadataSourceRepository.list(ctx)) {
            metadataSourceRepository.delete(ctx, row.id());
        }
    }

    private TenantRow requireTenant(long id) {
        return repository.findTenant(id)
                .orElseThrow(() -> new IllegalArgumentException("租户不存在: " + id));
    }

    private ProjectRow requireProject(long tenantId, long id) {
        return repository.findProject(tenantId, id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "项目不存在: " + id + "（租户 " + tenantId + " 下）"));
    }

    private TenantResponse loadTenant(long id) {
        TenantRow row = requireTenant(id);
        return TenantResponse.from(row, repository.listProjects(id).stream()
                .map(ProjectResponse::from).toList());
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
