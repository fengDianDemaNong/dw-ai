package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.auth.AuthService;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.AppearancePrefEntity;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.ProjectMemberEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.entity.UserTenantEntity;
import com.dwai.platform.meta.mapper.AppearancePrefMapper;
import com.dwai.platform.meta.mapper.ProjectMapper;
import com.dwai.platform.meta.mapper.ProjectMemberMapper;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.mapper.TenantMapper;
import com.dwai.platform.meta.mapper.UserMapper;
import com.dwai.platform.meta.mapper.UserTenantMapper;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
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
  private final ProjectMemberMapper members;
  private final AppearancePrefMapper prefs;
  private final PasswordEncoder passwords;

  public PlatformService(
      AccessService access,
      AuthService auth,
      TenantMapper tenants,
      TenantLicenseMapper licenses,
      UserMapper users,
      UserTenantMapper userTenants,
      ProjectMapper projects,
      ProjectMemberMapper members,
      AppearancePrefMapper prefs,
      PasswordEncoder passwords) {
    this.access = access;
    this.auth = auth;
    this.tenants = tenants;
    this.licenses = licenses;
    this.users = users;
    this.userTenants = userTenants;
    this.projects = projects;
    this.members = members;
    this.prefs = prefs;
    this.passwords = passwords;
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
        ? List.of("warehouse", "serve", "quality", "materialize", "dev")
        : req.modules();
    lic.setModules(Jsons.toJson(modules));
    licenses.insert(lic);
    upsertTenantRole(admin.getId(), t.getId(), "admin");
    ProjectEntity p = new ProjectEntity();
    p.setId("p-" + System.currentTimeMillis());
    p.setTenantId(t.getId());
    p.setCode("default");
    p.setName("默认项目");
    p.setDescription("");
    p.setOwner(admin.getId());
    p.setCreatedAt(LocalDate.now());
    projects.insert(p);
    upsertMember(p.getId(), admin.getId(), "admin");
    upsertPref("tenant", t.getId());
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
        if (lic == null) {
          lic = new TenantLicenseEntity();
          lic.setTenantId(id);
          lic.setModules(Jsons.toJson(req.modules()));
          licenses.insert(lic);
        } else {
          lic.setModules(Jsons.toJson(req.modules()));
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
    return auth.toTenant(t);
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
    if (e == null) return new ApiModels.AppearanceDto("cyan", "left");
    return new ApiModels.AppearanceDto(nz(e.getTheme(), "cyan"), nz(e.getMenuPos(), "left"));
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
      if (!blank(body.menuPos())) e.setMenuPos(body.menuPos());
      prefs.update(e, Wrappers.<AppearancePrefEntity>lambdaQuery()
          .eq(AppearancePrefEntity::getScope, "platform")
          .eq(AppearancePrefEntity::getTenantId, ""));
    }
    return new ApiModels.AppearanceDto(e.getTheme(), e.getMenuPos());
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
    e.setMenuPos("left");
    prefs.insert(e);
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }
  private static String nz(String v, String d) { return blank(v) ? d : v; }
}
