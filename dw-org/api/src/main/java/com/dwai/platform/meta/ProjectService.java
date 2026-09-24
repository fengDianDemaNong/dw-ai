package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.auth.AuthService;
import com.dwai.platform.internal.ModuleSyncService;
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
  private final AccessService access;
  private final DwaiProperties props;
  private final AuthService auth;
  private final ModuleSyncService moduleSync;

  public ProjectService(
      TenantMapper tenants,
      TenantLicenseMapper licenses,
      UserMapper users,
      ProjectMapper projects,
      ProjectMemberMapper members,
      AccessService access,
      DwaiProperties props,
      AuthService auth,
      ModuleSyncService moduleSync) {
    this.tenants = tenants;
    this.licenses = licenses;
    this.users = users;
    this.projects = projects;
    this.members = members;
    this.access = access;
    this.props = props;
    this.auth = auth;
    this.moduleSync = moduleSync;
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
      // 加产品维后同一个人在同一项目会有多行（每个产品一行），去重才是「参与的项目」。
      List<String> mine = members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
              .eq(ProjectMemberEntity::getUserId, me.userId()))
          .stream().map(ProjectMemberEntity::getProjectId).distinct().toList();
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
          // 与 membersForClient 同理：授权码不带产品维，合成行挂 warehouse。
          memberDtos.add(new ApiModels.MemberDto(
              p.getId(), me.userId(), "warehouse",
              access.grantProjectRole(me.userId(), me.tenantId(), p.getId())));
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

  public List<ProjectEntity> listAllEntities() {
    return projects.selectList(null);
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
    // 同 session()：一个人在同一项目会有多行（每产品一行），去重。
    List<String> mine = members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getUserId, user))
        .stream().map(ProjectMemberEntity::getProjectId).distinct().toList();
    return list.stream()
        .filter((p) -> mine.contains(p.getId()) && isActive(p))
        .map(this::toProject)
        .toList();
  }

  public ApiModels.ProjectDto getProject(String id) {
    return toProject(access.requireProject(id));
  }

  @Transactional
  public ApiModels.ProjectDto upsertInternal(String projectCode, String name, String tenantCode, String preferredId) {
    if (projectCode == null || projectCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要项目编码");
    }
    String code = projectCode.trim();
    String tid = resolveTenantId(tenantCode);
    ProjectEntity existing = null;
    if (tid != null && !tid.isBlank()) {
      existing = projects.selectOne(Wrappers.<ProjectEntity>lambdaQuery()
          .eq(ProjectEntity::getTenantId, tid)
          .eq(ProjectEntity::getCode, code));
    }
    if (existing == null && preferredId != null && !preferredId.isBlank()) {
      existing = projects.selectById(preferredId.trim());
    }
    if (existing == null) {
      existing = projects.selectById(code);
    }
    if (existing != null) {
      if (name != null && !name.isBlank()) existing.setName(name.trim());
      existing.setCode(code);
      if (tid != null && !tid.isBlank()) existing.setTenantId(tid);
      existing.setStatus("active");
      projects.updateById(existing);
      return toProject(existing);
    }
    if (tid == null || tid.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "跨服务落项目需要 tenantCode");
    }
    ProjectEntity p = new ProjectEntity();
    p.setId(preferredId != null && !preferredId.isBlank() ? preferredId.trim() : "p-" + System.currentTimeMillis());
    p.setTenantId(tid);
    p.setCode(code);
    p.setName(name == null || name.isBlank() ? code : name.trim());
    p.setDescription("");
    p.setOwner(TenantContext.user());
    p.setCreatedAt(LocalDate.now());
    p.setStatus("active");
    p.setEngines(Jsons.toJson(List.of()));
    projects.insert(p);
    return toProject(p);
  }

  @Transactional
  public void deleteByCode(String projectCode, String tenantCode) {
    if (projectCode == null || projectCode.isBlank()) return;
    String tid = resolveTenantId(tenantCode);
    ProjectEntity p = null;
    if (tid != null && !tid.isBlank()) {
      p = projects.selectOne(Wrappers.<ProjectEntity>lambdaQuery()
          .eq(ProjectEntity::getTenantId, tid)
          .eq(ProjectEntity::getCode, projectCode.trim()));
    }
    if (p == null) p = projects.selectById(projectCode.trim());
    if (p == null) return;
    projects.deleteById(p.getId());
  }

  private String resolveTenantId(String tenantCode) {
    if (tenantCode != null && !tenantCode.isBlank()) {
      TenantEntity t = tenants.selectByCode(tenantCode.trim());
      if (t != null) return t.getId();
      TenantEntity byId = tenants.selectById(tenantCode.trim());
      if (byId != null) return byId.getId();
    }
    String ctx = TenantContext.tenantId();
    return ctx == null || ctx.isBlank() ? null : ctx;
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
    grantProjectAdmin(p.getId(), tid, adminId);
    moduleSync.syncProject(p);
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
        grantProjectAdmin(p.getId(), p.getTenantId(), p.getOwner());
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
    moduleSync.removeProject(p);
    projects.deleteById(p.getId());
  }

  public ApiModels.SnapshotDto snapshot(String projectId) {
    ProjectEntity p = access.requireProject(projectId);
    access.requireMember(projectId, "spec:read");
    return new ApiModels.SnapshotDto(
        toProject(p),
        membersForClient(projectId),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
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
      // 授权码的 project_roles 只有「项目 → 角色」，没有产品维，所以这条合成行
      // 挂在 warehouse 下。平台持码访客因此在数据地图里没有角色 —— 已知限制，
      // 要修得先让 tenant_grants 也带产品维。
      list.add(new ApiModels.MemberDto(
          projectId, me.userId(), "warehouse", access.grantProjectRole(me.userId(), tid, projectId)));
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
    // 缺产品按仓建设处理：让还没跟上产品维的旧调用点先照原样工作。
    String product = req.product() == null || req.product().isBlank()
        ? "warehouse" : req.product().trim();
    String role = req.role() == null ? "viewer" : req.role();
    if (!List.of("admin", "modeler", "viewer").contains(role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法角色");
    }
    access.requireTenantUser(req.userId());
    upsertMember(projectId, req.userId(), product, role);
    return new ApiModels.MemberDto(projectId, req.userId(), product, role);
  }

  /**
   * 把一个人移出项目 —— <b>所有产品</b>，不是某一个。
   *
   * <p>只想摘掉某人在某个产品下的角色，用 {@link #putMember} 把那行删掉或改成 viewer；
   * 这个接口的语义是「这个人不再属于本项目」。
   */
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

  /**
   * 把某人设为项目在<b>每个已开通产品</b>下的管理角色。
   *
   * <p>PRD §3：项目管理员各已启用产品自动映射为该产品管理角色。建项目、换 owner、
   * 组织后台「指定项目管理员」都走这里 —— 只给仓建设建行的结果是这个人进不去数据地图，
   * 而成员列表上还看不出缺了什么。
   */
  @Transactional
  public void grantProjectAdmin(String projectId, String tenantId, String userId) {
    for (String product : access.licensedProducts(tenantId)) {
      upsertMember(projectId, userId, product, "admin");
    }
  }

  private void upsertMember(String projectId, String userId, String product, String role) {
    ProjectMemberEntity exist = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
        .eq(ProjectMemberEntity::getProjectId, projectId)
        .eq(ProjectMemberEntity::getUserId, userId)
        .eq(ProjectMemberEntity::getProduct, product));
    if (exist == null) {
      ProjectMemberEntity m = new ProjectMemberEntity();
      m.setProjectId(projectId);
      m.setUserId(userId);
      m.setProduct(product);
      m.setRole(role);
      members.insert(m);
    } else {
      exist.setRole(role);
      members.update(exist, Wrappers.<ProjectMemberEntity>lambdaQuery()
          .eq(ProjectMemberEntity::getProjectId, projectId)
          .eq(ProjectMemberEntity::getUserId, userId)
          .eq(ProjectMemberEntity::getProduct, product));
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
    return new ApiModels.MemberDto(m.getProjectId(), m.getUserId(), m.getProduct(), m.getRole());
  }

  /**
   * 某租户全部项目的成员，带显示名。服务间接口 {@code GET /internal/v1/members} 用它。
   *
   * <p><b>为什么要有这个</b>：模块侧只有项目镜像、没有成员表，所以 multi 下仓建设的
   * 「项目成员」页此前只能给每个项目合成一条「我自己」（见 dw-model 的
   * {@code ProjectService.session} 的 {@code warehouseRemote} 分支），且那条成员只有
   * userId —— 前端因此只能把「显示名」列退化成 {@code u-1790068559315} 这样的内部 id。
   * 成员和显示名的权威都在组织，这里一次性给全，模块调一次就够。
   *
   * <p>入参用 {@code tenantCode} 而不是 id：模块侧手上只有 code（项目镜像里带的就是它），
   * 与 fan-out、authz/check 两个既有通道保持一致。
   */
  public List<ApiModels.MemberDto> membersOfTenant(String tenantCode) {
    if (tenantCode == null || tenantCode.isBlank()) return List.of();
    TenantEntity t = tenants.selectByCode(tenantCode.trim());
    if (t == null) t = tenants.selectById(tenantCode.trim());
    if (t == null) return List.of();
    List<String> pids = projects.selectList(
            Wrappers.<ProjectEntity>lambdaQuery().eq(ProjectEntity::getTenantId, t.getId()))
        .stream().map(ProjectEntity::getId).toList();
    if (pids.isEmpty()) return List.of();
    return members.selectList(
            Wrappers.<ProjectMemberEntity>lambdaQuery().in(ProjectMemberEntity::getProjectId, pids))
        .stream().map(this::toMemberWithName).toList();
  }

  /** 成员 + 显示名/登录名。成员表只存 userId，名字与账号要回 users 表取。 */
  private ApiModels.MemberDto toMemberWithName(ProjectMemberEntity m) {
    UserEntity u = m.getUserId() == null ? null : users.selectById(m.getUserId());
    return new ApiModels.MemberDto(
        m.getProjectId(), m.getUserId(), m.getProduct(), m.getRole(),
        u == null ? null : u.getDisplayName(),
        u == null ? null : u.getUsername());
  }

  private ApiModels.LicenseDto toLicense(TenantLicenseEntity lic, String tenantId) {
    List<String> modules = lic == null ? List.of("warehouse") : Jsons.strings(lic.getModules());
    return new ApiModels.LicenseDto(tenantId, modules, access.licensedAiCaps(tenantId));
  }
}
