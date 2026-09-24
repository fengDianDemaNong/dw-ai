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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class ProjectService {
  /**
   * 本进程的产品码。仓建设只服务自己，本地成员行的产品维恒为它。
   *
   * <p>与 {@link AccessService} 取的是同一个配置项（{@code dwai.product-code}）——
   * 两处各写一份字面量的话，改一处漏一处会让「写进去的行」和「判权查的行」对不上，
   * 表现为成员列表空掉而没人知道为什么。
   */
  private final String product;

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
    this.product = props.productCode();
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
    boolean warehouseRemote = props.isWarehouseOnly() && props.isMulti();
    boolean platformVisitor = !warehouseRemote
        && Boolean.TRUE.equals(me.platformAdmin())
        && !access.isRealTenantAdmin()
        && access.hasValidPlatformAccess(me.userId(), me.tenantId());
    if (platformVisitor) {
      List<String> scoped = access.grantProjectIds(me.userId(), me.tenantId());
      if (!scoped.isEmpty()) {
        list = list.stream().filter((p) -> scoped.contains(p.getId())).toList();
      }
    } else if (!warehouseRemote && !access.isRealTenantAdmin()) {
      List<String> mine = members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
              .eq(ProjectMemberEntity::getUserId, me.userId()))
          .stream().map(ProjectMemberEntity::getProjectId).toList();
      list = list.stream().filter((p) -> mine.contains(p.getId())).toList();
    }
    if (!access.isRealTenantAdmin()) {
      list = list.stream().filter(ProjectService::isActive).toList();
    }
    List<ApiModels.MemberDto> memberDtos;
    if (warehouseRemote) {
      String uid = me.userId();
      List<ProjectEntity> allowed = new ArrayList<>();
      memberDtos = new ArrayList<>();
      // 成员名单问组织要一次（权威在组织，与 remoteProjectRole 同一条通道）。此前这里
      // 只给每个项目造一条「我自己」，且只有 userId —— 前端于是只能把「显示名」列退化成
      // 内部 id。准入判断仍逐项目走 remoteProjectRole，不受这份镜像影响。
      // 拉不到时回落成「只造自己」：成员不全好过项目列表整个空掉。
      Map<String, List<ApiModels.MemberDto>> byProject = access.remoteMembers(me.tenantId());
      for (ProjectEntity p : list) {
        String role = access.remoteProjectRole(p.getId());
        if (role == null) continue;
        allowed.add(p);
        List<ApiModels.MemberDto> mirror = byProject.get(p.getId());
        if (mirror == null || mirror.isEmpty()) {
          memberDtos.add(new ApiModels.MemberDto(p.getId(), uid, product, role));
        } else {
          memberDtos.addAll(mirror);
        }
      }
      list = allowed;
    } else {
      List<String> pids = list.stream().map(ProjectEntity::getId).toList();
      List<ProjectMemberEntity> mems = pids.isEmpty()
          ? List.of()
          : members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery().in(ProjectMemberEntity::getProjectId, pids));
      memberDtos = new ArrayList<>(mems.stream().map(this::toMember).toList());
    }
    if (platformVisitor) {
      for (ProjectEntity p : list) {
        boolean has = memberDtos.stream().anyMatch((m) -> m.projectId().equals(p.getId()) && m.userId().equals(me.userId()));
        if (!has) {
          // 授权码的 project_roles 不带产品维，合成行挂 warehouse（与组织侧同口径）。
          memberDtos.add(new ApiModels.MemberDto(
              p.getId(), me.userId(), product,
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
    if (props.isWarehouseOnly() && props.isMulti()) {
      return list.stream()
          .filter(ProjectService::isActive)
          .filter((p) -> access.remoteProjectRole(p.getId()) != null)
          .map(this::toProject)
          .toList();
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
  public ApiModels.ProjectDto upsertInternal(
      String projectCode,
      String name,
      String tenantCode,
      String preferredId,
      String tenantName,
      List<String> modules,
      List<String> aiCaps) {
    if (projectCode == null || projectCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要项目编码");
    }
    String code = projectCode.trim();
    String tid = ensureTenant(tenantCode, tenantName, modules, aiCaps);
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
      String nextName = name == null || name.isBlank() ? existing.getName() : name.trim();
      boolean same = Objects.equals(existing.getCode(), code)
          && (tid == null || tid.isBlank() || tid.equals(existing.getTenantId()))
          && Objects.equals(nz(existing.getName()), nz(nextName))
          && "active".equalsIgnoreCase(nz(existing.getStatus(), "active"));
      if (same) return toProject(existing);
      existing.setName(nextName);
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

  /** 组织 fan-out 时本地可能还没有租户行，按 code / 上下文 id 补一份。 */
  /**
   * 解析（必要时新建）租户，并用组织侧的许可校准本地行。
   *
   * <p><b>许可的权威在组织。</b>这里以前对新租户写死 {@code [warehouse, metadata]}，
   * 后果是「组织里只开通了仓建设」的租户在仓建设里照样看得见数据地图入口，点进去必然报错；
   * 反过来只开通数据地图的租户会被凭空赋予仓建设。现在两项由组织随项目镜像带过来
   * （见 {@code com.dwai.platform.internal.ModuleSyncService#bodyOf}），这里只负责落库。
   *
   * <p>两个参数都是 null 表示组织没带这项（老版本组织），此时**不动**本地行 ——
   * 与「组织说这个租户一项都没开通」（空数组）必须区分开。
   */
  private String ensureTenant(
      String tenantCode, String tenantName, List<String> modules, List<String> aiCaps) {
    String existing = resolveTenantId(tenantCode);
    TenantEntity found = existing == null ? null : tenants.selectById(existing);
    String display = blankToNull(tenantName);
    if (found != null) {
      if (display != null && (found.getName() == null || found.getName().isBlank() || found.getName().equals(found.getCode()))) {
        found.setName(display);
        tenants.updateById(found);
      }
      syncLicense(found.getId(), modules, aiCaps);
      return found.getId();
    }
    String code = blankToNull(tenantCode);
    if (code == null) code = blankToNull(TenantContext.tenantCode());
    String id = blankToNull(TenantContext.tenantId());
    if (id != null) {
      TenantEntity byId = tenants.selectById(id);
      if (byId != null) {
        syncLicense(byId.getId(), modules, aiCaps);
        return byId.getId();
      }
    }
    if (code != null) {
      TenantEntity byCode = tenants.selectByCode(code);
      if (byCode != null) {
        syncLicense(byCode.getId(), modules, aiCaps);
        return byCode.getId();
      }
    }
    if (code == null && id == null) return null;
    if (id == null) id = code.startsWith("t-") ? code : "t-" + code;
    if (code == null) code = id.startsWith("t-") && id.length() > 2 ? id.substring(2) : id;
    TenantEntity t = new TenantEntity();
    t.setId(id);
    t.setCode(code);
    t.setName(display == null ? code : display);
    t.setOwner("org");
    t.setStatus("active");
    try {
      tenants.insert(t);
    } catch (DuplicateKeyException e) {
      TenantEntity raced = code != null ? tenants.selectByCode(code) : null;
      if (raced == null) raced = tenants.selectById(id);
      if (raced == null) throw e;
      syncLicense(raced.getId(), modules, aiCaps);
      return raced.getId();
    }
    syncLicense(id, modules, aiCaps);
    return id;
  }

  /**
   * 用组织侧的许可校准本地 {@code tenant_licenses} 行。
   *
   * <p>只写「传了、而且确实不一样」的字段 —— modules 与 aiCaps 各自独立，缺一项不该把另一项抹掉。
   * 比对用集合语义：这个方法的调用频率跟着心跳走（组织每 120 秒全量补发一次项目），
   * 顺序不同不该被判成变更、白写一次库。
   */
  private void syncLicense(String tenantId, List<String> modules, List<String> aiCaps) {
    if (tenantId == null || tenantId.isBlank()) return;
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    if (lic == null) {
      // 本地还没有这行：按组织给的值建。组织没带（老版本）时回落最小集 ——
      // 能走到这里说明该租户至少开了仓建设，别再像以前那样顺手把数据地图也开上。
      lic = new TenantLicenseEntity();
      lic.setTenantId(tenantId);
      lic.setModules(Jsons.toJson(modules != null ? modules : List.of("warehouse")));
      lic.setAiCaps(Jsons.toJson(aiCaps != null ? aiCaps : List.of()));
      licenses.insert(lic);
      return;
    }
    boolean changed = false;
    if (modules != null && !sameSet(Jsons.strings(lic.getModules()), modules)) {
      lic.setModules(Jsons.toJson(modules));
      changed = true;
    }
    if (aiCaps != null && !sameSet(Jsons.strings(lic.getAiCaps()), aiCaps)) {
      lic.setAiCaps(Jsons.toJson(aiCaps));
      changed = true;
    }
    if (changed) licenses.updateById(lic);
  }

  private static boolean sameSet(List<String> a, List<String> b) {
    return new HashSet<>(a).equals(new HashSet<>(b));
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
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
    upsertMember(p.getId(), adminId, product, "admin");
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
        upsertMember(p.getId(), p.getOwner(), product, "admin");
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
      list.add(new ApiModels.MemberDto(
          projectId, me.userId(), product, access.grantProjectRole(me.userId(), tid, projectId)));
    }
    return list;
  }

  public List<ApiModels.MemberDto> listMembers(String projectId) {
    access.requireProject(projectId);
    access.requireMember(projectId, "spec:read");
    return members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getProjectId, projectId)
            .eq(ProjectMemberEntity::getProduct, product))
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
    upsertMember(projectId, req.userId(), product, role);
    return new ApiModels.MemberDto(projectId, req.userId(), product, role);
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

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static String nz(String v, String d) {
    return v == null || v.isBlank() ? d : v;
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

  private ApiModels.LicenseDto toLicense(TenantLicenseEntity lic, String tenantId) {
    List<String> modules = lic == null ? List.of("warehouse") : Jsons.strings(lic.getModules());
    return new ApiModels.LicenseDto(tenantId, modules, access.licensedAiCaps(tenantId));
  }
}
