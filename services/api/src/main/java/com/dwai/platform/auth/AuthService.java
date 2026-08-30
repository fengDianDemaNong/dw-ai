package com.dwai.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.AccessService;
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
import com.dwai.platform.meta.support.Jsons;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

@Service
public class AuthService {
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final TenantMapper tenants;
  private final TenantLicenseMapper licenses;
  private final TenantGrantMapper grants;
  private final PlatformAccessMapper access;
  private final ProjectMapper projects;
  private final ProjectMemberMapper members;
  private final JwtIssuer issuer;
  private final PasswordEncoder passwords;
  private final DwaiProperties props;
  private final AccessService acl;

  public AuthService(
      UserMapper users,
      UserTenantMapper userTenants,
      TenantMapper tenants,
      TenantLicenseMapper licenses,
      TenantGrantMapper grants,
      PlatformAccessMapper access,
      ProjectMapper projects,
      ProjectMemberMapper members,
      JwtIssuer issuer,
      PasswordEncoder passwords,
      DwaiProperties props,
      AccessService acl) {
    this.users = users;
    this.userTenants = userTenants;
    this.tenants = tenants;
    this.licenses = licenses;
    this.grants = grants;
    this.access = access;
    this.projects = projects;
    this.members = members;
    this.issuer = issuer;
    this.passwords = passwords;
    this.props = props;
    this.acl = acl;
  }

  public ApiModels.LoginRes login(String username, String password) {
    if (username == null || username.isBlank() || password == null) {
      throw badLogin();
    }
    UserEntity u = users.selectByUsername(username.trim());
    if (u == null || u.getPasswordHash() == null || !passwords.matches(password, u.getPasswordHash())) {
      throw badLogin();
    }
    if (!"active".equalsIgnoreCase(nz(u.getStatus(), "active"))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }
    if (props.isStandard() && Boolean.TRUE.equals(u.getPlatformAdmin())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "普通模式不提供平台账号");
    }
    String token = issuer.issue(u.getId(), u.getUsername(), u.getDisplayName(), Boolean.TRUE.equals(u.getPlatformAdmin()));
    List<ApiModels.TenantDto> list = listTenantsFor(u);
    boolean needSelect = props.isMulti() && !Boolean.TRUE.equals(u.getPlatformAdmin()) && list.size() != 1;
    return new ApiModels.LoginRes(token, needSelect, Boolean.TRUE.equals(u.getPlatformAdmin()), u.getDisplayName(), u.getId(), list);
  }

  public List<ApiModels.TenantDto> myTenants() {
    return listTenantsFor(acl.requireUser());
  }

  public ApiModels.Me selectTenant(String tenantId) {
    UserEntity u = acl.requireUser();
    if (tenantId == null || tenantId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择组织");
    }
    if (!acl.canAccessTenant(u.getId(), tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权进入该组织");
    }
    TenantEntity t = tenants.selectById(tenantId);
    if (t == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "组织不存在");
    if (!Boolean.TRUE.equals(u.getPlatformAdmin()) && !"active".equalsIgnoreCase(nz(t.getStatus(), "active"))) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织已停用");
    }
    UserTenantEntity ut = acl.membership(u.getId(), tenantId);
    return toMe(u, t, ut == null ? null : ut.getTenantRole());
  }

  @Transactional
  public ApiModels.Me enterTenant(String tenantId, String code) {
    acl.requireMulti();
    UserEntity u = acl.requireUser();
    if (!Boolean.TRUE.equals(u.getPlatformAdmin())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅平台用户可用授权码进入");
    }
    if (tenantId == null || code == null || tenantId.isBlank() || code.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写授权码");
    }
    TenantGrantEntity g = grants.selectOne(Wrappers.<TenantGrantEntity>lambdaQuery()
        .eq(TenantGrantEntity::getTenantId, tenantId)
        .eq(TenantGrantEntity::getCode, code.trim()));
    if (!TenantFilter.grantValid(g)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "授权码无效或已过期");
    }
    PlatformAccessEntity exist = access.selectOne(Wrappers.<PlatformAccessEntity>lambdaQuery()
        .eq(PlatformAccessEntity::getUserId, u.getId())
        .eq(PlatformAccessEntity::getTenantId, tenantId));
    if (exist == null) {
      PlatformAccessEntity pa = new PlatformAccessEntity();
      pa.setUserId(u.getId());
      pa.setTenantId(tenantId);
      pa.setGrantId(g.getId());
      access.insert(pa);
    } else {
      exist.setGrantId(g.getId());
      access.update(exist, Wrappers.<PlatformAccessEntity>lambdaQuery()
          .eq(PlatformAccessEntity::getUserId, u.getId())
          .eq(PlatformAccessEntity::getTenantId, tenantId));
    }
    return selectTenant(tenantId);
  }

  public ApiModels.Me currentMe() {
    UserEntity u = acl.requireUser();
    String tid = TenantContext.tenantId();
    if (tid == null || tid.isBlank()) {
      if (props.isStandard()) {
        tid = props.implicitTenantId();
      }
    }
    TenantEntity t = tid == null ? null : tenants.selectById(tid);
    UserTenantEntity ut = t == null ? null : acl.membership(u.getId(), t.getId());
    return toMe(u, t, ut == null ? null : ut.getTenantRole());
  }

  public List<ApiModels.TenantDto> listTenantsFor(UserEntity u) {
    if (props.isStandard()) {
      TenantEntity t = tenants.selectById(props.implicitTenantId());
      return t == null ? List.of() : List.of(toTenant(t));
    }
    if (Boolean.TRUE.equals(u.getPlatformAdmin())) {
      List<PlatformAccessEntity> rows = access.selectList(Wrappers.<PlatformAccessEntity>lambdaQuery()
          .eq(PlatformAccessEntity::getUserId, u.getId()));
      List<ApiModels.TenantDto> out = new ArrayList<>();
      for (PlatformAccessEntity pa : rows) {
        if (!acl.hasValidPlatformAccess(u.getId(), pa.getTenantId())) continue;
        TenantEntity t = tenants.selectById(pa.getTenantId());
        if (t != null) out.add(toTenant(t));
      }
      return out;
    }
    return userTenants.selectList(Wrappers.<UserTenantEntity>lambdaQuery()
            .eq(UserTenantEntity::getUserId, u.getId()))
        .stream()
        .map(ut -> tenants.selectById(ut.getTenantId()))
        .filter(t -> t != null && "active".equalsIgnoreCase(nz(t.getStatus(), "active")))
        .map(this::toTenant)
        .toList();
  }

  public ApiModels.TenantDto toTenant(TenantEntity t) {
    TenantLicenseEntity lic = licenses.selectById(t.getId());
    List<String> modules = lic == null ? List.of("warehouse") : Jsons.strings(lic.getModules());
    return new ApiModels.TenantDto(t.getId(), t.getCode(), t.getName(), t.getOwner(), nz(t.getStatus(), "active"), modules);
  }

  private ApiModels.Me toMe(UserEntity u, TenantEntity t, String tenantRole) {
    Landing land = landing(u, t, tenantRole);
    return new ApiModels.Me(
        u.getId(),
        u.getDisplayName(),
        t == null ? null : t.getId(),
        t == null ? null : t.getCode(),
        t == null ? null : t.getName(),
        u.getUsername(),
        props.getSecurity().isOidc() ? "oidc" : "dev",
        Boolean.TRUE.equals(u.getPlatformAdmin()),
        tenantRole,
        land.path,
        land.projectId,
        land.needSelect,
        props.isStandard() ? "standard" : "multi");
  }

  private Landing landing(UserEntity u, TenantEntity t, String tenantRole) {
    if (props.isMulti() && Boolean.TRUE.equals(u.getPlatformAdmin()) && t == null) {
      return new Landing("/admin", null, false);
    }
    if (props.isMulti() && t == null) {
      List<ApiModels.TenantDto> ts = listTenantsFor(u);
      if (ts.size() != 1) return new Landing("/select-tenant", null, true);
    }
    if ("admin".equalsIgnoreCase(tenantRole)) {
      return new Landing("/projects", null, false);
    }
    String projectId = firstProjectId(u.getId(), t == null ? null : t.getId());
    if (projectId != null) return new Landing("/w", projectId, false);
    return new Landing("/no-project", null, false);
  }

  private String firstProjectId(String userId, String tenantId) {
    if (tenantId == null) return null;
    List<ProjectEntity> list = projects.selectList(
        Wrappers.<ProjectEntity>lambdaQuery().eq(ProjectEntity::getTenantId, tenantId));
    for (ProjectEntity p : list) {
      ProjectMemberEntity m = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
          .eq(ProjectMemberEntity::getProjectId, p.getId())
          .eq(ProjectMemberEntity::getUserId, userId));
      if (m != null) return p.getId();
    }
    if (acl.platformGrantOf(userId, tenantId) != null) {
      List<String> scoped = acl.grantProjectIds(userId, tenantId);
      for (ProjectEntity p : list) {
        if (scoped.isEmpty() || scoped.contains(p.getId())) return p.getId();
      }
    }
    return null;
  }

  private static ResponseStatusException badLogin() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
  }

  private static String nz(String v, String d) {
    return v == null || v.isBlank() ? d : v;
  }

  private record Landing(String path, String projectId, boolean needSelect) {}
}
