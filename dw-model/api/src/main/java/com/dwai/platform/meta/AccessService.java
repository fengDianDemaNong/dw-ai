package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.auth.TenantFilter;
import com.dwai.platform.internal.OrgClient;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.PlatformAccessEntity;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.ProjectMemberEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantGrantEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.entity.UserTenantEntity;
import com.dwai.platform.meta.mapper.PlatformAccessMapper;
import com.dwai.platform.meta.mapper.ProjectMapper;
import com.dwai.platform.meta.mapper.ProjectMemberMapper;
import com.dwai.platform.meta.mapper.TenantGrantMapper;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.mapper.TenantMapper;
import com.dwai.platform.meta.mapper.UserMapper;
import com.dwai.platform.meta.mapper.UserTenantMapper;
import com.dwai.platform.meta.support.AiCaps;
import com.dwai.platform.meta.support.Jsons;
import com.dwai.platform.meta.support.Perms;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class AccessService {
  /**
   * 本进程的产品码，权限表（{@link Perms}）的第一维。
   *
   * <p>权限词（`spec:read` / `model:write` …）本身就属于某个产品，所以这个值不是随部署
   * 乱变的开关 —— 它是「本进程这些权限词属于谁」的自述。默认 warehouse，来自
   * {@code dwai.product-code}（见 {@link DwaiProperties#productCode()}）；配成权限表里
   * 没有的值会让全部判权变成 403，而不是静默换个产品。
   */
  private final String product;

  private final ProjectMapper projects;
  private final ProjectMemberMapper members;
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final TenantMapper tenants;
  private final PlatformAccessMapper access;
  private final TenantGrantMapper grants;
  private final TenantLicenseMapper licenses;
  private final DwaiProperties props;
  private final ObjectProvider<OrgClient> orgClient;

  public AccessService(
      ProjectMapper projects,
      ProjectMemberMapper members,
      UserMapper users,
      UserTenantMapper userTenants,
      TenantMapper tenants,
      PlatformAccessMapper access,
      TenantGrantMapper grants,
      TenantLicenseMapper licenses,
      DwaiProperties props,
      ObjectProvider<OrgClient> orgClient) {
    this.projects = projects;
    this.members = members;
    this.users = users;
    this.userTenants = userTenants;
    this.tenants = tenants;
    this.access = access;
    this.grants = grants;
    this.licenses = licenses;
    this.props = props;
    this.product = props.productCode();
    this.orgClient = orgClient;
  }

  public UserEntity requireUser() {
    if (props.isStandalone() || (props.isWarehouseOnly() && props.isMulti())) {
      String id = TenantContext.user();
      if (id != null && !id.isBlank() && !"anonymous".equals(id)) {
        UserEntity existing = users.selectById(id);
        if (existing != null) return existing;
      }
      UserEntity fake = new UserEntity();
      fake.setId(id == null || id.isBlank() ? "remote" : id);
      fake.setUsername(fake.getId());
      fake.setDisplayName(TenantContext.displayName() == null || TenantContext.displayName().isBlank()
          ? fake.getId() : TenantContext.displayName());
      fake.setStatus("active");
      fake.setPlatformAdmin(TenantContext.platformAdmin());
      return fake;
    }
    String id = TenantContext.user();
    if (id == null || id.isBlank() || "anonymous".equals(id)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    }
    UserEntity u = users.selectById(id);
    if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
    if (!"active".equalsIgnoreCase(nz(u.getStatus(), "active"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已停用");
    }
    return u;
  }

  public void requirePlatform() {
    requireMulti();
    UserEntity u = requireUser();
    if (!Boolean.TRUE.equals(u.getPlatformAdmin())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要平台用户权限");
    }
  }

  public void requireMulti() {
    if (!props.isMulti()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前运行模式没有此功能");
    }
  }

  public TenantEntity requireTenant() {
    requireUser();
    String tid = TenantContext.tenantId();
    if ((tid == null || tid.isBlank()) && (props.isStandard() || props.isStandalone())) {
      tid = props.implicitTenantId();
    }
    if (tid == null || tid.isBlank()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请先选择组织");
    }
    TenantEntity t = tenants.selectById(tid);
    if (t == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织不存在");
    if (!"active".equalsIgnoreCase(nz(t.getStatus(), "active"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织已停用");
    }
    return t;
  }

  public void requireTenantAdmin() {
    requireTenant();
    if (!isRealTenantAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要管理员权限");
    }
  }

  public void requireRealTenantAdmin() {
    requireTenant();
    if (!isRealTenantAdmin()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "需要本组织管理员权限");
    }
  }

  public boolean isRealTenantAdmin() {
    if (props.isStandalone()) return true;
    return "admin".equalsIgnoreCase(TenantContext.tenantRole());
  }

  public boolean canAccessTenant(String userId, String tenantId) {
    UserEntity u = users.selectById(userId);
    if (u == null) return false;
    UserTenantEntity ut = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getUserId, userId)
        .eq(UserTenantEntity::getTenantId, tenantId));
    if (ut != null) return true;
    if (!Boolean.TRUE.equals(u.getPlatformAdmin()) || !props.isMulti()) return false;
    return hasValidPlatformAccess(userId, tenantId);
  }

  public boolean hasValidPlatformAccess(String userId, String tenantId) {
    return platformGrantOf(userId, tenantId) != null;
  }

  public TenantGrantEntity platformGrantOf(String userId, String tenantId) {
    PlatformAccessEntity pa = access.selectOne(Wrappers.<PlatformAccessEntity>lambdaQuery()
        .eq(PlatformAccessEntity::getUserId, userId)
        .eq(PlatformAccessEntity::getTenantId, tenantId));
    if (pa == null) return null;
    TenantGrantEntity g = grants.selectById(pa.getGrantId());
    return TenantFilter.grantValid(g) ? g : null;
  }

  /** 空列表表示该侧不限制。 */
  public List<String> grantModules(String userId, String tenantId) {
    TenantGrantEntity g = platformGrantOf(userId, tenantId);
    return g == null ? List.of() : Jsons.strings(g.getModules());
  }

  public List<String> grantProjectIds(String userId, String tenantId) {
    TenantGrantEntity g = platformGrantOf(userId, tenantId);
    return g == null ? List.of() : Jsons.strings(g.getProjectIds());
  }

  /** 持码进入某项目时的角色；未指定则只读访客。 */
  public String grantProjectRole(String userId, String tenantId, String projectId) {
    TenantGrantEntity g = platformGrantOf(userId, tenantId);
    if (g == null) return "viewer";
    java.util.Map<String, String> roles = Jsons.stringMap(g.getProjectRoles());
    String r = projectId == null ? null : roles.get(projectId);
    if (r == null || r.isBlank()) r = roles.get("*");
    if ("admin".equals(r) || "modeler".equals(r) || "viewer".equals(r)) return r;
    return "viewer";
  }

  public List<String> licensedAiCaps(String tenantId) {
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    List<String> modules = lic == null ? List.of("warehouse") : Jsons.strings(lic.getModules());
    boolean warehouse = modules.contains("warehouse");
    return AiCaps.licensed(warehouse, lic == null ? List.of() : Jsons.strings(lic.getAiCaps()));
  }

  public List<String> effectiveAiCaps(String userId, String tenantId) {
    List<String> licensed = licensedAiCaps(tenantId);
    if (!props.isMulti() || userId == null || tenantId == null) return licensed;
    TenantGrantEntity g = platformGrantOf(userId, tenantId);
    if (g == null) return licensed;
    return AiCaps.effective(licensed, Jsons.strings(g.getAiCaps()));
  }

  public List<String> currentAiCaps() {
    String tid = TenantContext.tenantId();
    if (tid == null || tid.isBlank()) return List.of();
    return effectiveAiCaps(TenantContext.user(), tid);
  }

  public boolean hasAiCap(String cap) {
    return currentAiCaps().contains(cap);
  }

  public void requireAiCap(String cap) {
    if (!hasAiCap(cap)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "本组织未开通此项");
    }
  }

  public boolean grantCoversProject(String userId, String tenantId, String projectId) {
    if (platformGrantOf(userId, tenantId) == null) return false;
    List<String> ids = grantProjectIds(userId, tenantId);
    return ids.isEmpty() || ids.contains(projectId);
  }

  public UserTenantEntity membership(String userId, String tenantId) {
    return userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getUserId, userId)
        .eq(UserTenantEntity::getTenantId, tenantId));
  }

  public ProjectEntity requireProject(String projectId) {
    ProjectEntity p = projects.selectById(projectId);
    if (p == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    String tid = TenantContext.tenantId();
    if (tid != null && !tid.isBlank() && !tid.equals(p.getTenantId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "项目不属于当前组织");
    }
    return p;
  }

  /**
   * 校验「按 id 取回来的实体」确实属于本次操作的项目。
   *
   * <p>各 {@code save*} 都是「带了 id 就更新、没带就新增」，而 id 来自请求体、projectId 来自路径。
   * 只按 id 取实体再 {@code updateById}，等于假设「调用方给的 id 一定属于它有权操作的那个项目」——
   * 传一个别人的 id 就能改到别人的行（跨项目、跨租户都成立）。
   * 因此所有接受 id 的保存路径都必须过这一关。
   *
   * <p>返回 404 而不是 403：不必告诉调用方「这个 id 存在，只是不是你的」。
   */
  public static void requireSameProject(String projectId, String entityProjectId) {
    if (entityProjectId != null && !entityProjectId.equals(projectId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "资源不属于当前项目");
    }
  }

  public Map<String, Object> checkAuthz(
      String userId,
      String tenantCode,
      String projectCode,
      String product,
      String action,
      String legacyTenantId,
      String legacyProjectId) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("product", product == null || product.isBlank() ? "warehouse" : product.trim());
    out.put("action", action == null || action.isBlank() ? "model:read" : action.trim());
    if (props.isStandalone()) {
      out.put("allow", true);
      out.put("role", "admin");
      return out;
    }
    TenantEntity tenant = resolveTenant(tenantCode, legacyTenantId);
    if (tenant == null) {
      out.put("allow", false);
      out.put("reason", "租户编码未同步");
      return out;
    }
    out.put("tenantCode", tenant.getCode());
    ProjectEntity project = resolveProject(tenant.getId(), projectCode, legacyProjectId);
    if ((notBlank(projectCode) || notBlank(legacyProjectId)) && project == null) {
      out.put("allow", false);
      out.put("reason", "项目编码未同步");
      return out;
    }
    if (project != null) out.put("projectCode", project.getCode());
    List<String> modules = licensedModules(tenant.getId());
    String prod = (String) out.get("product");
    if (!modules.contains(prod)) {
      out.put("allow", false);
      out.put("reason", "平台未给本组织开通此产品");
      return out;
    }
    UserEntity u = resolveUser(userId);
    if (u == null) {
      out.put("allow", false);
      out.put("reason", "用户不存在");
      return out;
    }
    try {
      String role = roleOf(
          u.getId(), tenant.getId(), project == null ? null : project.getId(),
          (String) out.get("product"), u);
      Perms.require((String) out.get("product"), role, (String) out.get("action"));
      out.put("allow", true);
      out.put("role", role);
    } catch (ResponseStatusException e) {
      out.put("allow", false);
      out.put("reason", e.getReason());
    }
    return out;
  }

  public void requireMember(String projectId, String perm) {
    if (props.isStandalone()) {
      requireProject(projectId);
      return;
    }
    if (props.isWarehouseOnly() && props.isMulti()) {
      // 先确认「路径里的项目」属于当前租户，再拿<b>这个项目</b>去问组织。
      //
      // 少了 requireProject 这一步就是一个越权口子：组织只按请求头里的 tenantCode/projectCode
      // 判权，而数据操作按路径里的 projectId 落库。攻击者带自己项目的头（判权通过）、
      // 把路径换成别的租户的项目 id，就能读写别人的数据 —— 因为本项目没有 MyBatis 租户
      // 拦截器，业务表也不带 tenant_id 列，隔离完全依赖这一步。
      remoteAuthz(blank(projectId) ? null : requireProject(projectId), perm);
      return;
    }
    ProjectEntity p = requireProject(projectId);
    if (!isRealTenantAdmin() && !isActive(p)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "项目已停用");
    }
    String user = TenantContext.user();
    // 必须带产品过滤：加产品维之后同一个人在同一项目下有多行（仓建设一行、数据地图一行），
    // 只按 (projectId, userId) 取会 selectOne 抛多行异常。org 的同名方法一直带着这一条，
    // 这里原先漏了 —— 本库只写 warehouse 行时看不出来，多产品行一进来就炸。
    ProjectMemberEntity m = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
        .eq(ProjectMemberEntity::getProjectId, projectId)
        .eq(ProjectMemberEntity::getUserId, user)
        .eq(ProjectMemberEntity::getProduct, product));
    if (m == null) {
      String tid = TenantContext.tenantId();
      if (tid != null && grantCoversProject(user, tid, projectId)) {
        Perms.require(product, grantProjectRole(user, tid, projectId), perm);
        return;
      }
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未加入该项目");
    }
    Perms.require(product, m.getRole(), perm);
  }

  public UserEntity requireTenantUser(String userId) {
    if (props.isStandalone()) return requireUser();
    String tid = requireTenant().getId();
    UserEntity u = users.selectById(userId);
    if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
    UserTenantEntity ut = membership(userId, tid);
    if (ut == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能选择本组织用户");
    return u;
  }

  /**
   * 向组织平台核查权限。
   *
   * @param project 已由 {@link #requireProject} 校验过归属的项目；为空表示本次操作没有指定项目
   *                （少数只按租户维度的调用），此时退回请求头里的项目
   */
  private void remoteAuthz(ProjectEntity project, String perm) {
    OrgClient org = orgClient.getIfAvailable();
    if (org == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "组织鉴权不可用");
    }
    // 优先用「已校验的实体」取 code，请求头只作为兜底 —— 头与路径冲突时以路径为准，
    // 避免出现「按 A 判权、往 B 写」的分叉。
    String tenantCode = project != null
        ? firstNonBlank(tenantCodeOf(project.getTenantId()), TenantContext.tenantCode())
        : firstNonBlank(TenantContext.tenantCode(), tenantCodeOf(TenantContext.tenantId()));
    String projectCode = project != null
        ? project.getCode()
        : TenantContext.projectCode();
    if (tenantCode == null || projectCode == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "跨服务鉴权需要 tenantCode 与 projectCode");
    }
    Map<String, Object> r = org.check(TenantContext.user(), tenantCode, projectCode, product, perm);
    if (!Boolean.TRUE.equals(r.get("allow"))) {
      Object reason = r.get("reason");
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, reason == null ? "无权执行此操作" : String.valueOf(reason));
    }
  }

  /**
   * 当前租户全部项目的成员，按 {@code projectId} 分组。
   *
   * <p>只在「仓建设 multi」这条路径上问组织 —— 其余模式的成员就在本地库里，不必绕一圈。
   * 与 {@link #remoteProjectRole} 是同一条通道（服务间令牌 + tenantCode），区别是它一次
   * 拉全租户而不是逐项目问，所以 {@code session()} 里只调一次。
   *
   * <p>拿不到时返回空表：调用方（{@code ProjectService.session}）对空表的回落是
   * 「只显示自己」，页面可用但成员不全；抛错则会让整个 session 失败，项目列表跟着空掉。
   */
  public Map<String, List<ApiModels.MemberDto>> remoteMembers(String tenantId) {
    if (!(props.isWarehouseOnly() && props.isMulti())) return Map.of();
    try {
      OrgClient org = orgClient.getIfAvailable();
      if (org == null) return Map.of();
      String tenantCode = firstNonBlank(TenantContext.tenantCode(), tenantCodeOf(tenantId));
      if (tenantCode == null) return Map.of();
      return org.members(tenantCode).stream()
          .filter((m) -> m.projectId() != null)
          .collect(Collectors.groupingBy(ApiModels.MemberDto::projectId));
    } catch (Exception ignored) {
      return Map.of();
    }
  }

  /** 仓建设 multi 没有本地成员表，向组织问角色。拒绝则不算成员；组织不可用时只保住当前项目。 */
  public String remoteProjectRole(String projectId) {
    if (!(props.isWarehouseOnly() && props.isMulti())) return "modeler";
    if (projectId == null || projectId.isBlank()) return null;
    try {
      OrgClient org = orgClient.getIfAvailable();
      if (org == null) return fallbackCurrent(projectId);
      String tenantCode = firstNonBlank(TenantContext.tenantCode(), tenantCodeOf(TenantContext.tenantId()));
      String projectCode = firstNonBlank(projectCodeOf(projectId), TenantContext.projectCode());
      if (tenantCode == null || projectCode == null) return fallbackCurrent(projectId);
      Map<String, Object> r = org.check(TenantContext.user(), tenantCode, projectCode, product, "model:read");
      if (Boolean.TRUE.equals(r.get("allow")) && r.get("role") instanceof String role && !role.isBlank()) {
        return role;
      }
      return null;
    } catch (Exception ignored) {
      return fallbackCurrent(projectId);
    }
  }

  private String fallbackCurrent(String projectId) {
    String ctxPid = TenantContext.projectId();
    if (projectId.equals(ctxPid)) return "modeler";
    String code = projectCodeOf(projectId);
    String ctxCode = TenantContext.projectCode();
    if (code != null && code.equals(ctxCode)) return "modeler";
    return null;
  }

  private TenantEntity resolveTenant(String tenantCode, String legacyId) {
    if (notBlank(tenantCode)) {
      TenantEntity t = tenants.selectByCode(tenantCode.trim());
      if (t != null) return t;
    }
    if (notBlank(legacyId)) {
      TenantEntity t = tenants.selectById(legacyId.trim());
      if (t != null) return t;
      return tenants.selectByCode(legacyId.trim());
    }
    return null;
  }

  private ProjectEntity resolveProject(String tenantId, String projectCode, String legacyId) {
    if (notBlank(projectCode)) {
      ProjectEntity p = projects.selectOne(Wrappers.<ProjectEntity>lambdaQuery()
          .eq(ProjectEntity::getTenantId, tenantId)
          .eq(ProjectEntity::getCode, projectCode.trim()));
      if (p != null) return p;
    }
    if (notBlank(legacyId)) {
      ProjectEntity p = projects.selectById(legacyId.trim());
      if (p != null && (tenantId == null || tenantId.equals(p.getTenantId()))) return p;
    }
    return null;
  }

  private UserEntity resolveUser(String userId) {
    if (!notBlank(userId)) return null;
    UserEntity u = users.selectById(userId.trim());
    if (u != null) return u;
    return users.selectByUsername(userId.trim());
  }

  /**
   * 某人在某项目、<b>某产品</b>下的角色。
   *
   * <p>加了产品维之后「某人在某项目的角色」不再唯一：同一个人在仓建设是规范管理员，
   * 在数据地图可能只是只读。所以 product 是必填参数 —— 这里没有「默认产品」这种东西，
   * 猜错了就是拿 A 产品的角色去判 B 产品的权。
   *
   * <p>租户管理员短路返回 `admin`：他在每个已开通产品里都是该产品的管理角色
   * （PRD §3「项目管理员各已启用产品自动映射为该产品管理角色」）。
   */
  private String roleOf(String userId, String tenantId, String projectId, String product, UserEntity user) {
    UserTenantEntity ut = membership(userId, tenantId);
    if (ut != null && "admin".equalsIgnoreCase(ut.getTenantRole())) return "admin";
    if (projectId == null) {
      if (ut != null) return "viewer";
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未加入该组织");
    }
    ProjectMemberEntity m = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
        .eq(ProjectMemberEntity::getProjectId, projectId)
        .eq(ProjectMemberEntity::getUserId, userId)
        .eq(ProjectMemberEntity::getProduct, product));
    if (m != null) return m.getRole();
    if (grantCoversProject(userId, tenantId, projectId)) {
      return grantProjectRole(userId, tenantId, projectId);
    }
    if (Boolean.TRUE.equals(user.getPlatformAdmin()) && grantCoversProject(userId, tenantId, projectId)) {
      return "viewer";
    }
    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未加入该项目");
  }

  private List<String> licensedModules(String tenantId) {
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    return lic == null ? List.of("warehouse") : Jsons.strings(lic.getModules());
  }

  private String tenantCodeOf(String tenantId) {
    if (!notBlank(tenantId)) return null;
    TenantEntity t = tenants.selectById(tenantId);
    return t == null ? null : t.getCode();
  }

  private String projectCodeOf(String projectId) {
    if (!notBlank(projectId)) return null;
    ProjectEntity p = projects.selectById(projectId);
    return p == null ? null : p.getCode();
  }

  private static boolean notBlank(String v) {
    return v != null && !v.isBlank();
  }

  private static boolean blank(String v) {
    return !notBlank(v);
  }

  private static String firstNonBlank(String a, String b) {
    if (notBlank(a)) return a;
    return notBlank(b) ? b : null;
  }

  private static boolean isActive(ProjectEntity p) {
    return p.getStatus() == null || p.getStatus().isBlank() || "active".equalsIgnoreCase(p.getStatus());
  }

  private static String nz(String v, String d) {
    return v == null || v.isBlank() ? d : v;
  }
}
