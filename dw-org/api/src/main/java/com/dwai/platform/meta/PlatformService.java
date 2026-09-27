package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.auth.AuthService;
import com.dwai.platform.auth.RefreshTokenService;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.AppearancePrefEntity;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.entity.UserTenantEntity;
import com.dwai.platform.meta.mapper.AppearancePrefMapper;
import com.dwai.platform.meta.mapper.ProjectMapper;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.mapper.TenantMapper;
import com.dwai.platform.meta.mapper.UserMapper;
import com.dwai.platform.meta.mapper.UserTenantMapper;
import com.dwai.platform.meta.support.AiCaps;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class PlatformService {
  private final AccessService access;
  private final AuthService auth;
  private final TenantMapper tenants;
  private final TenantLicenseMapper licenses;
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final ProjectMapper projects;
  private final AppearancePrefMapper prefs;
  private final PasswordEncoder passwords;
  private final RefreshTokenService refreshTokens;

  public PlatformService(
      AccessService access,
      AuthService auth,
      TenantMapper tenants,
      TenantLicenseMapper licenses,
      UserMapper users,
      UserTenantMapper userTenants,
      ProjectMapper projects,
      AppearancePrefMapper prefs,
      PasswordEncoder passwords,
      RefreshTokenService refreshTokens) {
    this.access = access;
    this.auth = auth;
    this.tenants = tenants;
    this.licenses = licenses;
    this.users = users;
    this.userTenants = userTenants;
    this.projects = projects;
    this.prefs = prefs;
    this.passwords = passwords;
    this.refreshTokens = refreshTokens;
  }

  public List<ApiModels.TenantDto> listTenants() {
    access.requirePlatform();
    return tenants.selectList(null).stream().map(auth::toTenant).toList();
  }

  @Transactional
  public ApiModels.TenantDto createTenant(ApiModels.CreateAdminTenantReq req) {
    access.requirePlatform();
    if (req == null || blank(req.code()) || blank(req.name())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写编码和名称");
    }
    String code = req.code().trim();
    if (!code.matches("[a-z][a-z0-9_]{1,31}")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "编码用小写字母开头，仅字母数字下划线");
    }
    if (tenants.selectCount(Wrappers.<TenantEntity>lambdaQuery().eq(TenantEntity::getCode, code)) > 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "租户编码已存在");
    }
    UserEntity admin = resolveAdmin(req.adminUserId(), req.adminUsername(), req.adminDisplayName(), req.adminPassword());
    TenantEntity t = new TenantEntity();
    t.setId("t-" + code);
    t.setCode(code);
    t.setName(req.name().trim());
    t.setOwner(admin.getDisplayName());
    t.setStatus("active");
    tenants.insert(t);
    TenantLicenseEntity lic = new TenantLicenseEntity();
    lic.setTenantId(t.getId());
    List<String> modules = req.modules() == null || req.modules().isEmpty()
        ? List.of("warehouse", "metadata", "serve", "quality", "materialize", "dev")
        : req.modules();
    lic.setModules(Jsons.toJson(modules));
    lic.setAiCaps(Jsons.toJson(AiCaps.licensed(modules.contains("warehouse"), null)));
    licenses.insert(lic);
    upsertTenantRole(admin.getId(), t.getId(), "admin");
    // 不预置项目：新租户从「一个项目都没有」开始，由租户管理员进去后自建。
    // 之前这里自动插一个「默认项目」（id 还是建租户那一刻的时间戳），结果是每个租户
    // 都凭空多出一个没人建过的项目，管理员还得先删掉它才能开始干活。
    //
    // 外观种三行，与 V26 迁移之后的存量租户形状一致：
    //   tenant    —— 老口径，dw-model 前端独立打开时读的那份（它不带 shell 参数）；
    //   workbench —— 工作台壳（org 的「设置 → 外观」）；
    //   project   —— 项目壳（项目「设置 → 外观」，一个租户下所有项目共用一份）。
    // 缺哪一行功能也不坏（getAppearance 有一样的 fallback），但库里留空档会让
    // 「这个租户的外观到底存在哪」得翻代码才知道。
    upsertPref("tenant", t.getId());
    upsertPref("workbench", t.getId());
    upsertPref("project", t.getId());
    return auth.toTenant(t);
  }

  @Transactional
  public ApiModels.TenantDto patchTenant(String id, ApiModels.PatchAdminTenantReq req) {
    access.requirePlatform();
    TenantEntity t = tenants.selectById(id);
    if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
    if (req != null) {
      if (!blank(req.status())) t.setStatus(req.status());
      if (!blank(req.name())) t.setName(req.name().trim());
      if (req.modules() != null) {
        TenantLicenseEntity lic = licenses.selectById(id);
        List<String> modules = req.modules();
        if (lic == null) {
          lic = new TenantLicenseEntity();
          lic.setTenantId(id);
          lic.setModules(Jsons.toJson(modules));
          lic.setAiCaps(Jsons.toJson(AiCaps.licensed(modules.contains("warehouse"), null)));
          licenses.insert(lic);
        } else {
          lic.setModules(Jsons.toJson(modules));
          lic.setAiCaps(Jsons.toJson(AiCaps.licensed(modules.contains("warehouse"), null)));
          licenses.updateById(lic);
        }
      }
      if (!blank(req.owner())) {
        UserEntity admin = users.selectById(req.owner());
        if (admin == null) admin = users.selectByUsername(req.owner());
        if (admin == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理员账号不存在");
        t.setOwner(admin.getDisplayName());
        upsertTenantRole(admin.getId(), id, "admin");
      }
      tenants.updateById(t);
    }
    // 许可变了<b>不</b>需要主动通知模块 —— 这里就是权威，模块自己会来取：其
    // TenantFilter / TenantInterceptor 每次拉项目镜像时都用这里返回的 modules/aiCaps
    // 无条件校准本地许可（见 ProjectService.projectByCode），正缓存 60s 就是
    // 「组织里开了数据地图，模块多久能进去」的上界。
    // 改前是在这里 resyncTenant（把该租户每个项目重推一遍），那是「模块只在收到项目镜像时
    // 才校准许可」时代的补丁，pull 之后连同那个前提一起作废。
    return auth.toTenant(t);
  }

  /**
   * 重置某租户管理员的密码。
   *
   * <p>为什么必须由平台侧单独开一个入口：{@code POST /api/tenants/{id}/users/{userId}}
   * 要求调用者<b>正处在该租户里</b>（{@code requireTenantAdmin} 先要 TenantContext 有租户），
   * 而平台管理员在租户管理页并没有进入租户，拿不到租户上下文；{@code /api/platform/users/{id}}
   * 又只认 {@code platformAdmin=true} 的账号，租户管理员通常不是。两条路都覆盖不到这个场景。
   *
   * <p>顺带作废该账号已签发的 refresh token：重置密码的用意就是「原来的密码不算数了」，
   * 不作废的话旧会话还能继续换新 access token，等于没重置。
   */
  @Transactional
  public void resetTenantAdminPassword(String tenantId, String password) {
    access.requirePlatform();
    if (blank(password)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写新密码");
    }
    TenantEntity t = tenants.selectById(tenantId);
    if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "租户不存在");
    UserEntity admin = requireTenantAdmin(t);
    admin.setPasswordHash(passwords.encode(password));
    users.updateById(admin);
    refreshTokens.revokeAll(admin.getId());
  }

  /**
   * 找出该租户的管理员账号。
   *
   * <p>权威来源是 {@code user_tenants}（建租户与「指定管理员」都会往这里写 role=admin），
   * <b>不是</b> {@code tenants.owner} —— 后者存的是显示名（见 {@link #createTenant}），
   * 既可能重复也会被改名，只适合展示。
   *
   * <p>一个租户允许有多名管理员，此时拿 owner 显示名消歧；仍不唯一就报错让人去「编辑」里
   * 指定，好过随便挑一个重置掉 —— 重置错了账号是没法撤销的。
   */
  private UserEntity requireTenantAdmin(TenantEntity t) {
    List<UserTenantEntity> admins = userTenants.selectList(Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getTenantId, t.getId())
        .eq(UserTenantEntity::getTenantRole, "admin"));
    if (admins.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该租户没有管理员账号");
    }
    if (admins.size() == 1) {
      UserEntity u = users.selectById(admins.get(0).getUserId());
      if (u == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理员账号不存在");
      return u;
    }
    List<UserEntity> matched = admins.stream()
        .map(a -> users.selectById(a.getUserId()))
        .filter(u -> u != null && u.getDisplayName() != null && u.getDisplayName().equals(t.getOwner()))
        .toList();
    if (matched.size() != 1) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "该租户有多名管理员，无法确定重置哪一个，请先在「编辑」里指定管理员");
    }
    return matched.get(0);
  }

  public List<ApiModels.OrgUserDto> listAccounts() {
    access.requirePlatform();
    return users.selectList(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPlatformAdmin, false))
        .stream()
        .map(u -> new ApiModels.OrgUserDto(
            u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(), null, false))
        .toList();
  }

  public List<ApiModels.OrgUserDto> listUsers() {
    access.requirePlatform();
    return users.selectList(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPlatformAdmin, true))
        .stream()
        .map(u -> new ApiModels.OrgUserDto(
            u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(), null, true))
        .toList();
  }

  @Transactional
  public ApiModels.OrgUserDto createUser(ApiModels.CreatePlatformUserReq req) {
    access.requirePlatform();
    if (req == null || blank(req.username()) || blank(req.password())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写用户名和初始密码");
    }
    if (users.selectByUsername(req.username().trim()) != null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
    }
    UserEntity u = new UserEntity();
    u.setId("u-" + System.currentTimeMillis());
    u.setUsername(req.username().trim());
    u.setDisplayName(blank(req.displayName()) ? u.getUsername() : req.displayName().trim());
    u.setPasswordHash(passwords.encode(req.password()));
    u.setStatus("active");
    u.setPlatformAdmin(true);
    users.insert(u);
    return new ApiModels.OrgUserDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(), null, true);
  }

  @Transactional
  public ApiModels.OrgUserDto patchUser(String id, ApiModels.PatchPlatformUserReq req) {
    access.requirePlatform();
    UserEntity u = users.selectById(id);
    if (u == null || !Boolean.TRUE.equals(u.getPlatformAdmin())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "平台用户不存在");
    }
    if (req != null && "disabled".equalsIgnoreCase(req.status())) {
      if (u.getId().equals(TenantContext.user())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能停用自己");
      }
      long active = users.selectList(Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getPlatformAdmin, true))
          .stream()
          .filter(x -> "active".equalsIgnoreCase(nz(x.getStatus(), "active")) && !x.getId().equals(id))
          .count();
      if (active == 0) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能停用最后一个可用平台用户");
      }
      u.setStatus("disabled");
    }
    if (req != null && "active".equalsIgnoreCase(req.status())) u.setStatus("active");
    if (req != null && !blank(req.password())) u.setPasswordHash(passwords.encode(req.password()));
    users.updateById(u);
    return new ApiModels.OrgUserDto(u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(), null, true);
  }

  public ApiModels.AppearanceDto getAppearance() {
    access.requirePlatform();
    AppearancePrefEntity e = prefs.selectOne(Wrappers.<AppearancePrefEntity>lambdaQuery()
        .eq(AppearancePrefEntity::getScope, "platform")
        .eq(AppearancePrefEntity::getTenantId, ""));
    if (e == null) return new ApiModels.AppearanceDto("cyan", "left", "ink");
    return toAppearance(e, "left");
  }

  @Transactional
  public ApiModels.AppearanceDto putAppearance(ApiModels.AppearanceDto body) {
    access.requirePlatform();
    upsertPref("platform", "");
    AppearancePrefEntity e = prefs.selectOne(Wrappers.<AppearancePrefEntity>lambdaQuery()
        .eq(AppearancePrefEntity::getScope, "platform")
        .eq(AppearancePrefEntity::getTenantId, ""));
    if (body != null) {
      if (!blank(body.theme())) e.setTheme(body.theme());
      if (!blank(body.menuPos()) && !"drawer".equals(body.menuPos())) e.setMenuPos(body.menuPos());
      if (!blank(body.menuColor())) e.setMenuColor(body.menuColor());
      prefs.update(e, Wrappers.<AppearancePrefEntity>lambdaQuery()
          .eq(AppearancePrefEntity::getScope, "platform")
          .eq(AppearancePrefEntity::getTenantId, ""));
    }
    return toAppearance(e, "left");
  }

  private UserEntity resolveAdmin(String userId, String username, String display, String password) {
    if (!blank(userId)) {
      UserEntity u = users.selectById(userId);
      if (u == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "管理员账号不存在");
      return u;
    }
    if (blank(username) || blank(password)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定已有账号或新建管理员");
    }
    UserEntity exist = users.selectByUsername(username.trim());
    if (exist != null) return exist;
    UserEntity u = new UserEntity();
    u.setId("u-" + System.currentTimeMillis());
    u.setUsername(username.trim());
    u.setDisplayName(blank(display) ? u.getUsername() : display.trim());
    u.setPasswordHash(passwords.encode(password));
    u.setStatus("active");
    u.setPlatformAdmin(false);
    users.insert(u);
    return u;
  }

  private void upsertTenantRole(String userId, String tenantId, String role) {
    UserTenantEntity exist = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getUserId, userId)
        .eq(UserTenantEntity::getTenantId, tenantId));
    if (exist == null) {
      UserTenantEntity ut = new UserTenantEntity();
      ut.setUserId(userId);
      ut.setTenantId(tenantId);
      ut.setTenantRole(role);
      userTenants.insert(ut);
    } else {
      exist.setTenantRole(role);
      userTenants.update(exist, Wrappers.<UserTenantEntity>lambdaQuery()
          .eq(UserTenantEntity::getUserId, userId)
          .eq(UserTenantEntity::getTenantId, tenantId));
    }
  }

  private void upsertPref(String scope, String tenantId) {
    AppearancePrefEntity exist = prefs.selectOne(Wrappers.<AppearancePrefEntity>lambdaQuery()
        .eq(AppearancePrefEntity::getScope, scope)
        .eq(AppearancePrefEntity::getTenantId, tenantId));
    if (exist != null) return;
    AppearancePrefEntity e = new AppearancePrefEntity();
    e.setScope(scope);
    e.setTenantId(tenantId);
    e.setTheme("cyan");
    e.setMenuPos(defaultMenuPos(scope));
    e.setMenuColor("ink");
    prefs.insert(e);
  }

  /**
   * 各 scope 的出厂菜单位置。`project` 与老口径的 `tenant` 用 `drawer`（项目壳默认就是
   * 从顶栏挂下来的抽屉）；`workbench` 与 `platform` 用 `left` —— 它们没有项目壳那条顶栏，
   * 抽屉是 absolute + top:100% 挂在顶栏下面的，没有锚点会落到视口外面。
   */
  private static String defaultMenuPos(String scope) {
    return "workbench".equals(scope) || "platform".equals(scope) ? "left" : "drawer";
  }

  static ApiModels.AppearanceDto toAppearance(AppearancePrefEntity e, String defaultPos) {
    return new ApiModels.AppearanceDto(
        nz(e.getTheme(), "cyan"),
        nz(e.getMenuPos(), defaultPos),
        nz(e.getMenuColor(), "ink"));
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }
  private static String nz(String v, String d) { return blank(v) ? d : v; }
}
