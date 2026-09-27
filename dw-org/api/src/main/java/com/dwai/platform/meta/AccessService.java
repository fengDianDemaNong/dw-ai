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
import java.util.Optional;
import java.util.Set;

@Service
public class AccessService {
  /**
   * 本进程判权时用的产品码，权限表 {@link Perms} 的第一维。
   *
   * <p>组织平台自己不做业务，默认取 {@code warehouse} 是因为 {@code snapshot} / {@code members}
   * 这几个接口是<b>给仓建设控制台用的</b>（仓建设前端带组织会话直接调组织），
   * 它们检查的 `spec:read` / `iam:member` 是仓建设权限词。
   *
   * <p>值来自 {@code dwai.product-code} 而不是写死：换产品部署时不该去几个类里各改一遍
   * 字面量。空值回落 warehouse（见 {@link DwaiProperties#productCode()}）。
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
  /** 产品角色表（V20）。判权的第一来源，见 {@link #requirePerm}。 */
  private final ProductRoleService productRoles;

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
      ProductRoleService productRoles) {
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
    this.productRoles = productRoles;
  }

  /**
   * 判权：<b>先查产品角色表，表里没有这个 (产品, 角色码) 才回落 {@link Perms} 的硬编码矩阵。</b>
   *
   * <p>这是规范 {@code 06-runtime-modes.md:115}「禁止再写死三套仓建设角色套所有模块」
   * 的落地点：管理员在平台上新建的角色、改过的权限词，从下一次判权起就生效。
   *
   * <h2>为什么是「回落」而不是「替换」</h2>
   *
   * <ul>
   *   <li><b>标准 / 独立模式</b>：{@code dw-model} 的本地判权直接调 {@code Perms}
   *       （它拿不到组织侧的角色定义），两张表在那里不存在。替换掉 {@code Perms}
   *       等于让那两种模式下所有角色判否。</li>
   *   <li><b>历史数据</b>：迁进来之前建的 {@code project_members} 行写着
   *       {@code admin/modeler/viewer}，而种子数据恰好把它们照原样灌进了表 ——
   *       回落给了「种子漏了某个产品」一个仍然可用的兜底，不至于把所有人的权限清零。</li>
   * </ul>
   *
   * <p>注意 {@code permsOf} 返回空 {@code Optional}（角色不在表里）与
   * {@code Optional.of(空集)}（角色在表里但一项权限都没配）是<b>两个意思</b>：
   * 前者回落，后者直接判否 —— 塌成一个会让刚建好、还没配权限的角色静默拿到硬编码矩阵的权限。
   */
  private void requirePerm(String product, String role, String perm) {
    Optional<Set<String>> words = productRoles.permsOf(product, role);
    if (words.isPresent()) {
      if (!words.get().contains(perm)) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权执行此操作");
      }
      return;
    }
    Perms.require(product, role, perm);
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

  /**
   * 本进程对外自报的产品码（{@code dwai.product-code}，空值回落 {@code warehouse}）。
   *
   * <p>public 是因为 {@link NavNodeService} 也要用：org <b>自有</b>菜单节点
   * （{@code nav_nodes.product = ''}，例如项目壳的「成员管理」）挂的权限词
   * （{@code iam:member}）同样要按本服务的产品去查角色 —— 那与
   * {@link #requireMember} 说的是同一件事「这个人在本项目里能不能管成员」，
   * 两处各读一次配置会让「换产品部署后侧栏按 A 判、接口按 B 判」。
   */
  public String productCode() {
    return product;
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
    List<String> modules = licensedProducts(tenant.getId());
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
      requirePerm((String) out.get("product"), role, (String) out.get("action"));
      out.put("allow", true);
      out.put("role", role);
    } catch (ResponseStatusException e) {
      out.put("allow", false);
      out.put("reason", e.getReason());
    }
    return out;
  }

  /**
   * 当前登录用户在某个产品里的角色码；这个人跟这里没关系时返回空。
   *
   * <p><b>给展示链路用</b>（按权限词过滤侧栏，见 {@code NavNodeService.treeFor}）。
   * 与 {@link #checkAuthz} 的区别只有一处：算不出结论时返回空集合，而不是让调用方收 403。
   * 侧栏是每个页面都要画的东西，让「他没加入这个项目」把整个壳打成 500 不划算 ——
   * 与 {@code menuFor} 里「拿不到租户就返回空列表」是同一条取舍。
   *
   * <p>租户管理员短路成 {@code admin}（与 {@link #roleOf} 同一条规则）；这里额外把
   * **平台管理员**也算进来：他们可能根本不是这个租户的成员（平台授权进来的），
   * {@code roleOf} 会判「未加入该组织」—— 对展示链路那等于把整个侧栏清空。
   */
  public Optional<String> currentRole(String projectId, String product) {
    if (props.isStandalone() || TenantContext.tenantAdmin()) return Optional.of("admin");
    try {
      UserEntity u = requireUser();
      return Optional.of(roleOf(u.getId(), TenantContext.tenantId(), projectId, product, u));
    } catch (ResponseStatusException e) {
      return Optional.empty();
    }
  }

  /**
   * 这个人是不是**这个项目的成员** —— 不论他在这个产品里有没有角色。
   *
   * <p>与 {@link #currentRole} 的区别就是「有没有角色」这一层：模块的可见范围里
   * {@code all_members}（全部项目成员）与 {@code role_holders}（持有该产品角色的人）
   * 两档的<b>唯一差别</b>就是它，所以必须分开判 —— 拿 {@code currentRole} 非空当
   * 「是成员」会让这两档变成同一档，而那正是最容易被配错、也最难被发现的一处。
   *
   * <p>{@code project_members} 是按 {@code (project, user, product)} 分行存的，所以
   * <b>故意不带 {@code product} 条件</b>：任意一行都说明这个人在这个项目里。
   *
   * <p>判据取宽：平台授权（{@code tenant_grants}）覆盖到该项目的也算成员 —— 他们
   * 未必有 {@code project_members} 行，但确实进得来这个项目。漏掉这一支的表现是
   * 「平台管理员配了 all_members，被授权的人仍看不见」，而配的人自己（管理员）看得见，
   * 于是没人报。
   *
   * <p>与 {@link #currentRole} 一样，standalone 下没有真实用户，一律算成员 ——
   * 那边的「角色」本来就是造出来的，这里跟着同一条取舍走。
   */
  public boolean isProjectMember(String projectId) {
    if (props.isStandalone()) return true;
    if (projectId == null || projectId.isBlank()) return false;
    String user = TenantContext.user();
    if (user == null || user.isBlank()) return false;
    try {
      String tid = TenantContext.tenantId();
      if (tid != null && grantCoversProject(user, tid, projectId)) return true;
      return members.selectCount(Wrappers.<ProjectMemberEntity>lambdaQuery()
          .eq(ProjectMemberEntity::getProjectId, projectId)
          .eq(ProjectMemberEntity::getUserId, user)) > 0;
    } catch (ResponseStatusException e) {
      return false;
    }
  }

  /**
   * 这个人是不是**这个项目里该产品的管理员** —— 模块可见范围 {@code project_admin} 档的判据。
   *
   * <p>「项目管理员」在产品语义里不是一个独立身份，而是「持有了那个 {@code is_admin}
   * 角色的人」（见 {@code ProductRoleService.isAdminRole}）。
   *
   * <p>租户管理员不必在这里再 OR 一次：{@link #currentRole} 对他们短路返回 {@code admin}，
   * 而每个产品的管理角色码就是 {@code admin} —— 短路本身已经覆盖。
   */
  public boolean isProductAdmin(String projectId, String product) {
    String role = currentRole(projectId, product).orElse(null);
    return role != null && productRoles.isAdminRole(product, role);
  }

  /**
   * 这个角色在这个产品里认不认这个权限词 —— 就是 {@link #requirePerm} 的布尔版，
   * 以产品角色表为准、表里没有该角色则回落硬编码矩阵，<b>口径与判权完全同一处</b>。
   *
   * <p>为什么不各写一份：侧栏「看得见」与接口「调得动」必须是同一条规则。分开写迟早漂成
   * 「菜单看得见、点进去 403」，或者反过来「有权但入口被藏起来」—— 两种都只在真机上才发现。
   */
  public boolean roleHas(String product, String role, String perm) {
    if (role == null || perm == null || perm.isBlank()) return false;
    try {
      requirePerm(product, role, perm);
      return true;
    } catch (ResponseStatusException e) {
      return false;
    }
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
        .eq(ProjectMemberEntity::getUserId, user)
        .eq(ProjectMemberEntity::getProduct, product));
    if (m == null) {
      String tid = TenantContext.tenantId();
      if (tid != null && grantCoversProject(user, tid, projectId)) {
        requirePerm(product, grantProjectRole(user, tid, projectId), perm);
        return;
      }
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未加入该项目");
    }
    requirePerm(product, m.getRole(), perm);
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

  /**
   * 某租户已开通的产品码。
   *
   * <p>public 是因为 {@link ProjectService} 也要用：建项目 / 换 owner 时得知道
   * 该给这个人在几个产品下建管理角色行（见 {@code grantProjectAdmin}）。
   * 两处各写一份的话，口径迟早会漂。
   */
  public List<String> licensedProducts(String tenantId) {
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
