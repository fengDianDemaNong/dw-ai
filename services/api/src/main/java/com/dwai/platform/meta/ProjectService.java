package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.auth.AuthService;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.ProjectMemberEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.mapper.ProjectMapper;
import com.dwai.platform.meta.mapper.ProjectMemberMapper;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.mapper.TenantMapper;
import com.dwai.platform.meta.mapper.UserMapper;
import com.dwai.platform.meta.support.AiCaps;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@Service
public class ProjectService {
  private final TenantMapper tenants;
  private final TenantLicenseMapper licenses;
  private final UserMapper users;
  private final ProjectMapper projects;
  private final ProjectMemberMapper members;
  private final SpecService spec;
  private final TableService tables;
  private final AccessService access;
  private final DwaiProperties props;
  private final AuthService auth;

  public ProjectService(
      TenantMapper tenants,
      TenantLicenseMapper licenses,
      UserMapper users,
      ProjectMapper projects,
      ProjectMemberMapper members,
      SpecService spec,
      TableService tables,
      AccessService access,
      DwaiProperties props,
      AuthService auth) {
    this.tenants = tenants;
    this.licenses = licenses;
    this.users = users;
    this.projects = projects;
    this.members = members;
    this.spec = spec;
    this.tables = tables;
    this.access = access;
    this.props = props;
    this.auth = auth;
  }

  public ApiModels.Me currentMe() {
    return auth.currentMe();
  }

  public ApiModels.SessionDto session() {
    ApiModels.Me me = currentMe();
    if (me.tenantId() == null || me.tenantId().isBlank()) {
      return new ApiModels.SessionDto(me, auth.myTenants(), List.of(), List.of(), List.of());
    }
    TenantLicenseEntity lic = licenses.selectById(me.tenantId());
    List<ProjectEntity> list = projects.selectList(
        Wrappers.<ProjectEntity>lambdaQuery().eq(ProjectEntity::getTenantId, me.tenantId()));
    boolean platformVisitor = Boolean.TRUE.equals(me.platformAdmin())
        && !access.isRealTenantAdmin()
        && access.hasValidPlatformAccess(me.userId(), me.tenantId());
    if (platformVisitor) {
      List<String> scoped = access.grantProjectIds(me.userId(), me.tenantId());
      if (!scoped.isEmpty()) {
        list = list.stream().filter((p) -> scoped.contains(p.getId())).toList();
      }
    } else if (!access.isRealTenantAdmin()) {
      List<String> mine = members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
              .eq(ProjectMemberEntity::getUserId, me.userId()))
          .stream().map(ProjectMemberEntity::getProjectId).toList();
      list = list.stream().filter((p) -> mine.contains(p.getId())).toList();
    }
    if (!access.isRealTenantAdmin()) {
      list = list.stream().filter(ProjectService::isActive).toList();
    }
    List<String> pids = list.stream().map(ProjectEntity::getId).toList();
    List<ProjectMemberEntity> mems = pids.isEmpty()
        ? List.of()
        : members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery().in(ProjectMemberEntity::getProjectId, pids));
    List<ApiModels.MemberDto> memberDtos = new java.util.ArrayList<>(mems.stream().map(this::toMember).toList());
    if (platformVisitor) {
      for (ProjectEntity p : list) {
        boolean has = memberDtos.stream().anyMatch((m) -> m.projectId().equals(p.getId()) && m.userId().equals(me.userId()));
        if (!has) {
          memberDtos.add(new ApiModels.MemberDto(
              p.getId(), me.userId(), access.grantProjectRole(me.userId(), me.tenantId(), p.getId())));
        }
      }
    }
    List<String> modules = lic == null ? List.of("warehouse") : Jsons.strings(lic.getModules());
    if (platformVisitor) {
      List<String> gm = access.grantModules(me.userId(), me.tenantId());
      if (!gm.isEmpty()) {
        modules = modules.stream().filter(gm::contains).toList();
      }
    }
    return new ApiModels.SessionDto(
        me,
        auth.myTenants(),
        list.stream().map(this::toProject).toList(),
        memberDtos,
        List.of(new ApiModels.LicenseDto(me.tenantId(), modules, access.effectiveAiCaps(me.userId(), me.tenantId()))));
  }

  public List<ApiModels.TenantDto> listTenants() {
    return auth.myTenants();
  }

  public List<ApiModels.TenantDto> listAllTenants() {
    return tenants.selectList(null).stream().map(auth::toTenant).toList();
  }

  @Transactional
  public ApiModels.TenantDto createTenant(ApiModels.CreateTenantReq req) {
    if (req.code() == null || req.code().isBlank() || req.name() == null || req.name().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写租户编码和名称");
    }
    String code = req.code().trim();
    if (!code.matches("[a-z][a-z0-9_]{1,31}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "编码用小写字母开头，仅字母数字下划线");
    }
    long clash = tenants.selectCount(Wrappers.<TenantEntity>lambdaQuery().eq(TenantEntity::getCode, code));
    if (clash > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "租户编码已存在");
    TenantEntity t = new TenantEntity();
    t.setId("t-" + code);
    t.setCode(code);
    t.setName(req.name().trim());
    t.setOwner(req.owner() == null || req.owner().isBlank() ? "admin" : req.owner().trim());
    tenants.insert(t);
    TenantLicenseEntity lic = new TenantLicenseEntity();
    lic.setTenantId(t.getId());
    lic.setModules(Jsons.toJson(List.of("warehouse", "serve", "quality", "materialize", "dev")));
    lic.setAiCaps(Jsons.toJson(AiCaps.ALL));
    licenses.insert(lic);
    return auth.toTenant(t);
  }

  public List<ApiModels.ProjectDto> listProjects() {
    access.requireTenant();
    String tid = TenantContext.tenantId();
    String user = TenantContext.user();
    List<ProjectEntity> list = projects.selectList(
        Wrappers.<ProjectEntity>lambdaQuery().eq(ProjectEntity::getTenantId, tid));
    if (access.isRealTenantAdmin()) {
      return list.stream().map(this::toProject).toList();
    }
    List<String> mine = members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getUserId, user))
        .stream().map(ProjectMemberEntity::getProjectId).toList();
    return list.stream()
        .filter((p) -> mine.contains(p.getId()) && isActive(p))
        .map(this::toProject)
        .toList();
  }

  public ApiModels.ProjectDto getProject(String id) {
    return toProject(access.requireProject(id));
  }

  @Transactional
  public ApiModels.ProjectDto createProject(ApiModels.CreateProjectReq req) {
    access.requireTenantAdmin();
    if (req.code() == null || req.code().isBlank() || req.name() == null || req.name().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写编码和名称");
    }
    String tid = TenantContext.tenantId();
    long clash = projects.selectCount(Wrappers.<ProjectEntity>lambdaQuery()
        .eq(ProjectEntity::getTenantId, tid).eq(ProjectEntity::getCode, req.code().trim()));
    if (clash > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "项目编码已存在");
    String adminId = req.adminUserId() == null || req.adminUserId().isBlank()
        ? (req.owner() == null || req.owner().isBlank() ? TenantContext.user() : req.owner())
        : req.adminUserId();
    access.requireTenantUser(adminId);
    ProjectEntity p = new ProjectEntity();
    p.setId("p-" + System.currentTimeMillis());
    p.setTenantId(tid);
    p.setCode(req.code().trim());
    p.setName(req.name().trim());
    p.setDescription(req.description() == null ? "" : req.description());
    p.setOwner(adminId);
    p.setCreatedAt(LocalDate.now());
    p.setStatus("active");
    p.setEngines(Jsons.toJson(AiCaps.normalizeEngines(req.engines())));
    projects.insert(p);
    upsertMember(p.getId(), adminId, "admin");
    if (Boolean.TRUE.equals(req.bootstrapSpec())) {
      spec.bootstrap(p.getId());
    }
    return toProject(p);
  }

  @Transactional
  public ApiModels.ProjectDto patchProject(String projectId, ApiModels.PatchProjectReq req) {
    access.requireTenantAdmin();
    ProjectEntity p = access.requireProject(projectId);
    if (req != null) {
      if (req.name() != null && !req.name().isBlank()) p.setName(req.name().trim());
      if (req.description() != null) p.setDescription(req.description());
      if (req.code() != null && !req.code().isBlank()) {
        String code = req.code().trim();
        if (!code.equals(p.getCode())) {
          long clash = projects.selectCount(Wrappers.<ProjectEntity>lambdaQuery()
              .eq(ProjectEntity::getTenantId, p.getTenantId())
              .eq(ProjectEntity::getCode, code)
              .ne(ProjectEntity::getId, p.getId()));
          if (clash > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "项目编码已存在");
          p.setCode(code);
        }
      }
      if (req.owner() != null && !req.owner().isBlank()) {
        access.requireTenantUser(req.owner());
        p.setOwner(req.owner().trim());
        upsertMember(p.getId(), p.getOwner(), "admin");
      }
      if (req.status() != null && !req.status().isBlank()) {
        String st = req.status().trim().toLowerCase();
        if (!List.of("active", "disabled").contains(st)) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法状态");
        }
        p.setStatus(st);
      }
      if (req.engines() != null) {
        p.setEngines(Jsons.toJson(AiCaps.normalizeEngines(req.engines())));
      }
      projects.updateById(p);
    }
    return toProject(p);
  }

  @Transactional
  public void deleteProject(String projectId) {
    access.requireTenantAdmin();
    ProjectEntity p = access.requireProject(projectId);
    projects.deleteById(p.getId());
  }

  public ApiModels.SnapshotDto snapshot(String projectId) {
    ProjectEntity p = access.requireProject(projectId);
    access.requireMember(projectId, "spec:read");
    return new ApiModels.SnapshotDto(
        toProject(p),
        membersForClient(projectId),
        spec.listDomains(projectId),
        spec.listLayers(projectId),
        spec.listGrades(projectId),
        spec.listRoots(projectId),
        tables.listTables(projectId),
        tables.listDrafts(projectId));
  }

  /** 平台持码访客不在 project_members 里，快照仍带上只读成员，避免控制台侧栏被冲成只剩概况。 */
  private List<ApiModels.MemberDto> membersForClient(String projectId) {
    List<ApiModels.MemberDto> list = new java.util.ArrayList<>(listMembers(projectId));
    ApiModels.Me me = currentMe();
    if (!Boolean.TRUE.equals(me.platformAdmin()) || access.isRealTenantAdmin()) return list;
    String tid = me.tenantId();
    if (tid == null || tid.isBlank() || !access.grantCoversProject(me.userId(), tid, projectId)) return list;
    boolean has = list.stream().anyMatch((m) -> m.userId().equals(me.userId()) && m.projectId().equals(projectId));
    if (!has) {
      list.add(new ApiModels.MemberDto(projectId, me.userId(), access.grantProjectRole(me.userId(), tid, projectId)));
    }
    return list;
  }

  public List<ApiModels.MemberDto> listMembers(String projectId) {
    access.requireProject(projectId);
    access.requireMember(projectId, "spec:read");
    return members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getProjectId, projectId))
        .stream().map(this::toMember).toList();
  }

  @Transactional
  public ApiModels.MemberDto putMember(String projectId, ApiModels.MemberReq req) {
    access.requireProject(projectId);
    access.requireMember(projectId, "iam:member");
    if (req.userId() == null || req.userId().isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId required");
    }
    String role = req.role() == null ? "viewer" : req.role();
    if (!List.of("admin", "modeler", "viewer").contains(role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法角色");
    }
    access.requireTenantUser(req.userId());
    upsertMember(projectId, req.userId(), role);
    return new ApiModels.MemberDto(projectId, req.userId(), role);
  }

  @Transactional
  public void deleteMember(String projectId, String userId) {
    access.requireProject(projectId);
    access.requireMember(projectId, "iam:member");
    members.delete(Wrappers.<ProjectMemberEntity>lambdaQuery()
        .eq(ProjectMemberEntity::getProjectId, projectId)
        .eq(ProjectMemberEntity::getUserId, userId));
  }

  public void ensureDevUser(String username, String tenantId) {
    TenantEntity t = tenants.selectById(tenantId);
    if (t == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "租户不存在");
    ensureUser(username, username, tenantId, null);
  }

  private void ensureUser(String id, String display, String tenantId, String casdoorId) {
    UserEntity exist = users.selectById(id);
    if (exist != null) return;
    UserEntity u = new UserEntity();
    u.setId(id);
    u.setTenantId(tenantId);
    u.setUsername(id);
    u.setDisplayName(display == null ? id : display);
    u.setStatus("active");
    u.setCasdoorId(casdoorId);
    users.insert(u);
  }

  private void upsertMember(String projectId, String userId, String role) {
    ProjectMemberEntity exist = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
        .eq(ProjectMemberEntity::getProjectId, projectId)
        .eq(ProjectMemberEntity::getUserId, userId));
    if (exist == null) {
      ProjectMemberEntity m = new ProjectMemberEntity();
      m.setProjectId(projectId);
      m.setUserId(userId);
      m.setRole(role);
      members.insert(m);
    } else {
      exist.setRole(role);
      members.update(exist, Wrappers.<ProjectMemberEntity>lambdaQuery()
          .eq(ProjectMemberEntity::getProjectId, projectId)
          .eq(ProjectMemberEntity::getUserId, userId));
    }
  }

  private ApiModels.TenantDto toTenant(TenantEntity t) {
    return auth.toTenant(t);
  }

  private static boolean isActive(ProjectEntity p) {
    return p.getStatus() == null || p.getStatus().isBlank() || "active".equalsIgnoreCase(p.getStatus());
  }

  private ApiModels.ProjectDto toProject(ProjectEntity p) {
    return new ApiModels.ProjectDto(
        p.getId(), p.getTenantId(), p.getCode(), p.getName(), p.getDescription(), p.getOwner(),
        p.getCreatedAt() == null ? null : p.getCreatedAt().toString(),
        p.getStatus() == null || p.getStatus().isBlank() ? "active" : p.getStatus(),
        Jsons.strings(p.getEngines()));
  }

  private ApiModels.MemberDto toMember(ProjectMemberEntity m) {
    return new ApiModels.MemberDto(m.getProjectId(), m.getUserId(), m.getRole());
  }

  private ApiModels.LicenseDto toLicense(TenantLicenseEntity lic, String tenantId) {
    List<String> modules = lic == null ? List.of("warehouse") : Jsons.strings(lic.getModules());
    return new ApiModels.LicenseDto(tenantId, modules, access.licensedAiCaps(tenantId));
  }
}
