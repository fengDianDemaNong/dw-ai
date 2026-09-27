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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
  /** 派角色时要校验角色码存在（V20），见 {@link #putMember}。 */
  private final ProductRoleService productRoles;

  public ProjectService(
      TenantMapper tenants,
      TenantLicenseMapper licenses,
      UserMapper users,
      ProjectMapper projects,
      ProjectMemberMapper members,
      AccessService access,
      DwaiProperties props,
      AuthService auth,
      ProductRoleService productRoles) {
    this.tenants = tenants;
    this.licenses = licenses;
    this.users = users;
    this.projects = projects;
    this.members = members;
    this.access = access;
    this.props = props;
    this.auth = auth;
    this.productRoles = productRoles;
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

  /**
   * 按「租户编码 + 项目编码」查一个项目，供模块侧**主动拉取**用（{@code /internal/v1/projects/by-code/...}）。
   *
   * <p><b>为什么不复用 {@link #getProject(String)}</b>：那个走 {@code access.requireProject(id)}，
   * 而 {@code requireProject} 的租户归属校验是 {@code if (tid != null && ...)} 的形状 ——
   * 靠 {@code TenantContext} 提供 tid。{@code /internal/v1/**} 上没有 JWT，tid 恒为 null，
   * 那道校验会**整块跳过**，于是「A 租户的编码」能读到 B 租户的同名项目。这里改成
   * <b>显式 tenantCode 入参</b>，且查的是 {@code UNIQUE (tenant_id, code)} ——
   * 注意 {@code code} 单独并不唯一，所以租户是查询的一部分，不是过滤条件。
   *
   * <p>同样刻意**不用** {@link #resolveTenantId(String)}：它在 tenantCode 解析不到时会回落
   * {@code TenantContext.tenantId()}。在这里那就等于「没传租户也能读到某个租户的数据」。
   *
   * @return 项目 + 该租户的许可（{@code modules}/{@code aiCaps} 在「组织没有这行许可」时
   *         是 {@code null}，序列化后会<b>缺席</b> —— 组织全局配了
   *         {@code default-property-inclusion: non_null}；收方 {@code Map.get} 读到的
   *         仍是 null，语义是「别动本地」）。项目不存在时返回 {@code null}
   * @throws ResponseStatusException 400：没带 tenantCode（缺了它就没法确定查哪个租户）
   */
  public Map<String, Object> projectByCode(String tenantCode, String projectCode) {
    if (tenantCode == null || tenantCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 tenantCode");
    }
    if (projectCode == null || projectCode.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要项目编码");
    }
    TenantEntity t = tenants.selectByCode(tenantCode.trim());
    if (t == null) return null;
    ProjectEntity p = projects.selectOne(Wrappers.<ProjectEntity>lambdaQuery()
        .eq(ProjectEntity::getTenantId, t.getId())
        .eq(ProjectEntity::getCode, projectCode.trim()));
    if (p == null) return null;
    // 用 LinkedHashMap 而不是 Map.of：查不到许可行时要发 null，Map.of 不接受 null 值。
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", p.getId());
    out.put("code", p.getCode());
    out.put("name", p.getName());
    out.put("tenantCode", t.getCode());
    out.put("tenantName", t.getName() == null ? "" : t.getName());
    out.put("modules", licenseOf(t.getId(), true));
    out.put("aiCaps", licenseOf(t.getId(), false));
    return out;
  }

  /** @return 该租户的许可项；本地没有这行许可时返回 {@code null}（收方跳过校准） */
  private List<String> licenseOf(String tenantId, boolean modules) {
    if (tenantId == null || tenantId.isBlank()) return null;
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    if (lic == null) return null;
    return Jsons.strings(modules ? lic.getModules() : lic.getAiCaps());
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
    // 不再向各模块推送这个新项目：模块侧的 TenantFilter / TenantInterceptor 会在
    // 用户第一次访问时来 GET /internal/v1/projects/by-code/{code} 拉走（见
    // ProjectService.projectByCode）。这里少一次网络调用，也少一个「模块没起来时怎么办」。
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
    // 这里<b>没有</b>对应的「通知模块删掉镜像」：pull 改造之后组织不再知道模块的地址。
    // 代价是模块本地会留一行孤儿镜像，点进去报 403「项目编码未同步」而不是 404 ——
    // 不越权，只是文案错，已在 KNOWN_ISSUES 记录（模块侧的自愈留待下一版）。
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

  /**
   * 项目成员列表，<b>带显示名与登录名</b>。
   *
   * <p>为什么用 {@link #toMemberWithName} 而不是 {@link #toMember}：成员表只存 userId，
   * 不带名字的话前端只能把「显示名」列退化成 {@code u-1790068559315} 这样的内部主键 ——
   * 对人没有任何意义。这正是 {@link #membersOfTenant} 注释里写过的症状，当初只在
   * 服务间那条通道上修了，项目壳这一条（本方法）漏了。
   *
   * <p>代价是逐行回查 users 表（N+1）。一个项目的成员是个位数量级，可接受；
   * 真要优化应当一次性 `in` 查回来，而不是退回不带名字。
   */
  public List<ApiModels.MemberDto> listMembers(String projectId) {
    access.requireProject(projectId);
    access.requireMember(projectId, "spec:read");
    return members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getProjectId, projectId))
        .stream().map(this::toMemberWithName).toList();
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
    // 角色码问产品角色表（V20），不再写死 admin/modeler/viewer 三值 ——
    // 写死那三值意味着产品专属的角色码（管理员在平台上新建的）根本派不下去。
    //
    // 这比改动前**严**：以前给「数据质量」这类还没定义角色的产品派 modeler 是能存进去的，
    // 但那种行判权时谁都不认（Perms 里没有这个产品）—— 存得进去、永远无效，
    // 正是「假开关」。现在拒掉，并告诉调用方去哪里建角色。
    if (!productRoles.exists(product, role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "产品「" + product + "」下没有角色「" + role + "」。可用角色：" + productRoles.codesOf(product)
              + "（在平台后台的「产品角色」里新增）");
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

  /**
   * 本项目可派的产品角色 —— 「成员管理」页角色下拉的选项来源。
   *
   * <p><b>为什么不能前端写死三值</b>：{@link #putMember} 写侧自 V20 起改成「角色码问产品角色表」
   * （校验见 L444），平台管理员在后台新建的角色码是能派下去的。读侧若还按
   * {@code admin/modeler/viewer} 枚举，那些新角色就<b>存得进、选不出</b> ——
   * 正是本仓反复出现的「假开关」。前端那份 {@code ROLE_PERMS} 是判权矩阵、不是角色清单，
   * 不能拿来当选项。
   *
   * <p>只投影 code/label/hint/isAdmin 四项：{@code all()} 的原始行里还带 {@code perms}
   * （该角色拥有哪些权限词），那是管理面「产品角色」页要看的，成员管理页用不上 ——
   * 少发一份权限词清单给每个有 {@code spec:read} 的人。
   *
   * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：后者遇 null 抛 NPE，而
   * {@code hint} 等字段在部分行上可能是 null。
   */
  public List<Map<String, Object>> memberRoles(String projectId) {
    access.requireProject(projectId);
    access.requireMember(projectId, "spec:read");
    List<Map<String, Object>> out = new java.util.ArrayList<>();
    for (Map<String, Object> r : productRoles.all(props.productCode())) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("code", r.get("code"));
      row.put("label", r.get("label"));
      row.put("hint", r.get("hint"));
      row.put("isAdmin", r.get("isAdmin"));
      out.add(row);
    }
    return out;
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
