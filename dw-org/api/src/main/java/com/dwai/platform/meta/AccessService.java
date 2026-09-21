package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.auth.TenantFilter;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AccessService {
  private final ProjectMapper projects;
  private final ProjectMemberMapper members;
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final TenantMapper tenants;
  private final PlatformAccessMapper access;
  private final TenantGrantMapper grants;
  private final TenantLicenseMapper licenses;
  private final DwaiProperties props;

  public AccessService(
      ProjectMapper projects,
      ProjectMemberMapper members,
      UserMapper users,
      UserTenantMapper userTenants,
      TenantMapper tenants,
      PlatformAccessMapper access,
      TenantGrantMapper grants,
      TenantLicenseMapper licenses,
      DwaiProperties props) {
    this.projects = projects;
    this.members = members;
    this.users = users;
    this.userTenants = userTenants;
    this.tenants = tenants;
    this.access = access;
    this.grants = grants;
    this.licenses = licenses;
    this.props = props;
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
      String role = roleOf(u.getId(), tenant.getId(), project == null ? null : project.getId(), u);
      Perms.require(role, (String) out.get("action"));
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
    ProjectEntity p = requireProject(projectId);
    if (!isRealTenantAdmin() && !isActive(p)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "项目已停用");
    }
    String user = TenantContext.user();
    ProjectMemberEntity m = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
        .eq(ProjectMemberEntity::getProjectId, projectId)
        .eq(ProjectMemberEntity::getUserId, user));
    if (m == null) {
      String tid = TenantContext.tenantId();
      if (tid != null && grantCoversProject(user, tid, projectId)) {
        Perms.require(grantProjectRole(user, tid, projectId), perm);
        return;
      }
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未加入该项目");
    }
    Perms.require(m.getRole(), perm);
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

  private String roleOf(String userId, String tenantId, String projectId, UserEntity user) {
    UserTenantEntity ut = membership(userId, tenantId);
    if (ut != null && "admin".equalsIgnoreCase(ut.getTenantRole())) return "admin";
    if (projectId == null) {
      if (ut != null) return "viewer";
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未加入该组织");
    }
    ProjectMemberEntity m = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
        .eq(ProjectMemberEntity::getProjectId, projectId)
        .eq(ProjectMemberEntity::getUserId, userId));
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
