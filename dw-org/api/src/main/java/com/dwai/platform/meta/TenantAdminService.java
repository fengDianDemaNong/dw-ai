package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.auth.AuthService;
import com.dwai.platform.auth.LlmCrypto;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.auth.TenantFilter;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.AppearancePrefEntity;
import com.dwai.platform.meta.entity.PlatformAccessEntity;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.ProjectMemberEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantGrantEntity;
import com.dwai.platform.meta.entity.TenantLlmEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.entity.UserTenantEntity;
import com.dwai.platform.meta.mapper.AppearancePrefMapper;
import com.dwai.platform.meta.mapper.PlatformAccessMapper;
import com.dwai.platform.meta.mapper.ProjectMapper;
import com.dwai.platform.meta.mapper.ProjectMemberMapper;
import com.dwai.platform.meta.mapper.TenantGrantMapper;
import com.dwai.platform.meta.mapper.TenantLlmMapper;
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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
public class TenantAdminService {
  private final AccessService access;
  private final AuthService auth;
  private final DwaiProperties props;
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final TenantMapper tenants;
  private final TenantGrantMapper grants;
  private final PlatformAccessMapper platformAccess;
  private final ProjectMapper projects;
  private final ProjectMemberMapper members;
  private final AppearancePrefMapper prefs;
  private final TenantLlmMapper llms;
  private final PasswordEncoder passwords;
  private final LlmCrypto crypto;
  private final ProjectService projectService;

  public TenantAdminService(
      AccessService access,
      AuthService auth,
      DwaiProperties props,
      UserMapper users,
      UserTenantMapper userTenants,
      TenantMapper tenants,
      TenantGrantMapper grants,
      PlatformAccessMapper platformAccess,
      ProjectMapper projects,
      ProjectMemberMapper members,
      AppearancePrefMapper prefs,
      TenantLlmMapper llms,
      PasswordEncoder passwords,
      LlmCrypto crypto,
      ProjectService projectService) {
    this.access = access;
    this.auth = auth;
    this.props = props;
    this.users = users;
    this.userTenants = userTenants;
    this.tenants = tenants;
    this.grants = grants;
    this.platformAccess = platformAccess;
    this.projects = projects;
    this.members = members;
    this.prefs = prefs;
    this.llms = llms;
    this.passwords = passwords;
    this.crypto = crypto;
    this.projectService = projectService;
  }

  public List<ApiModels.OrgUserDto> listUsers(String tenantId) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    return userTenants.selectList(Wrappers.<UserTenantEntity>lambdaQuery()
            .eq(UserTenantEntity::getTenantId, tenantId))
        .stream()
        .map(ut -> {
          UserEntity u = users.selectById(ut.getUserId());
          if (u == null) return null;
          return new ApiModels.OrgUserDto(
              u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(), ut.getTenantRole(), Boolean.TRUE.equals(u.getPlatformAdmin()));
        })
        .filter(x -> x != null)
        .toList();
  }

  @Transactional
  public ApiModels.OrgUserDto createUser(String tenantId, ApiModels.CreateOrgUserReq req) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写用户信息");
    String role = blank(req.tenantRole()) ? "member" : req.tenantRole();
    if (!List.of("admin", "member").contains(role)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法角色");
    }
    UserEntity u;
    if (!blank(req.existingUserId())) {
      u = users.selectById(req.existingUserId());
      if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
    } else if (!blank(req.username()) && users.selectByUsername(req.username().trim()) != null && blank(req.password())) {
      u = users.selectByUsername(req.username().trim());
    } else {
      if (blank(req.username()) || blank(req.password())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写用户名和密码");
      }
      if (users.selectByUsername(req.username().trim()) != null) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
      }
      u = new UserEntity();
      u.setId("u-" + System.currentTimeMillis());
      u.setUsername(req.username().trim());
      u.setDisplayName(blank(req.displayName()) ? u.getUsername() : req.displayName().trim());
      u.setPasswordHash(passwords.encode(req.password()));
      u.setStatus("active");
      u.setPlatformAdmin(false);
      users.insert(u);
    }
    UserTenantEntity exist = access.membership(u.getId(), tenantId);
    if (exist == null) {
      UserTenantEntity ut = new UserTenantEntity();
      ut.setUserId(u.getId());
      ut.setTenantId(tenantId);
      ut.setTenantRole(role);
      userTenants.insert(ut);
    }
    UserTenantEntity ut = access.membership(u.getId(), tenantId);
    return new ApiModels.OrgUserDto(
        u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(), ut.getTenantRole(), Boolean.TRUE.equals(u.getPlatformAdmin()));
  }

  @Transactional
  public ApiModels.OrgUserDto patchUser(String tenantId, String userId, ApiModels.PatchOrgUserReq req) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    UserEntity u = users.selectById(userId);
    if (u == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在");
    UserTenantEntity ut = access.membership(userId, tenantId);
    if (ut == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不在本组织");
    if (req != null) {
      if (!blank(req.displayName())) {
        u.setDisplayName(req.displayName().trim());
        users.updateById(u);
      }
      if (req.username() != null) {
        String next = req.username().trim();
        if (next.isBlank()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写用户名");
        }
        if (!next.equals(u.getUsername())) {
          UserEntity clash = users.selectByUsername(next);
          if (clash != null && !clash.getId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "用户名已存在");
          }
          u.setUsername(next);
          users.updateById(u);
        }
      }
      if (!blank(req.status())) {
        if (userId.equals(TenantContext.user()) && "disabled".equalsIgnoreCase(req.status())) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能停用自己");
        }
        u.setStatus(req.status());
        users.updateById(u);
      }
      if (!blank(req.password())) {
        u.setPasswordHash(passwords.encode(req.password()));
        users.updateById(u);
      }
      if (!blank(req.tenantRole()) && List.of("admin", "member").contains(req.tenantRole())) {
        if ("admin".equalsIgnoreCase(ut.getTenantRole()) && "member".equalsIgnoreCase(req.tenantRole())) {
          long others = userTenants.selectList(Wrappers.<UserTenantEntity>lambdaQuery()
                  .eq(UserTenantEntity::getTenantId, tenantId)
                  .eq(UserTenantEntity::getTenantRole, "admin"))
              .stream()
              .filter(x -> !x.getUserId().equals(userId))
              .count();
          if (others < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能取消最后一名管理员，请先转让");
          }
        }
        ut.setTenantRole(req.tenantRole());
        userTenants.update(ut, Wrappers.<UserTenantEntity>lambdaQuery()
            .eq(UserTenantEntity::getUserId, userId)
            .eq(UserTenantEntity::getTenantId, tenantId));
      }
      if (req.memberships() != null) {
        syncMemberships(tenantId, userId, req.memberships());
      }
      if (!blank(req.projectId())) {
        access.requireProject(req.projectId());
        ProjectMemberEntity exist = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getProjectId, req.projectId())
            .eq(ProjectMemberEntity::getUserId, userId));
        if (exist == null) {
          ProjectMemberEntity m = new ProjectMemberEntity();
          m.setProjectId(req.projectId());
          m.setUserId(userId);
          m.setRole("admin");
          members.insert(m);
        } else {
          exist.setRole("admin");
          members.update(exist, Wrappers.<ProjectMemberEntity>lambdaQuery()
              .eq(ProjectMemberEntity::getProjectId, req.projectId())
              .eq(ProjectMemberEntity::getUserId, userId));
        }
      }
    }
    ut = access.membership(userId, tenantId);
    return new ApiModels.OrgUserDto(
        u.getId(), u.getUsername(), u.getDisplayName(), u.getStatus(), ut.getTenantRole(), Boolean.TRUE.equals(u.getPlatformAdmin()));
  }

  @Transactional
  public void removeUser(String tenantId, String userId) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    if (userId.equals(TenantContext.user())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除自己");
    }
    UserTenantEntity ut = access.membership(userId, tenantId);
    if (ut == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不在本组织");
    if ("admin".equalsIgnoreCase(ut.getTenantRole())) {
      long admins = userTenants.selectList(Wrappers.<UserTenantEntity>lambdaQuery()
              .eq(UserTenantEntity::getTenantId, tenantId)
              .eq(UserTenantEntity::getTenantRole, "admin"))
          .stream()
          .filter(x -> !x.getUserId().equals(userId))
          .count();
      if (admins < 1) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除最后一名管理员，请先转让");
      }
    }
    List<String> pids = projects.selectList(Wrappers.<ProjectEntity>lambdaQuery().eq(ProjectEntity::getTenantId, tenantId))
        .stream().map(ProjectEntity::getId).toList();
    if (!pids.isEmpty()) {
      members.delete(Wrappers.<ProjectMemberEntity>lambdaQuery()
          .in(ProjectMemberEntity::getProjectId, pids)
          .eq(ProjectMemberEntity::getUserId, userId));
    }
    userTenants.delete(Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getUserId, userId)
        .eq(UserTenantEntity::getTenantId, tenantId));
  }

  public List<ApiModels.ProjectDto> listProjects(String tenantId) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    return projects.selectList(Wrappers.<ProjectEntity>lambdaQuery().eq(ProjectEntity::getTenantId, tenantId))
        .stream()
        .map(this::toProject)
        .toList();
  }

  public ApiModels.ProjectDto createProject(String tenantId, ApiModels.CreateProjectReq req) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    String owner = req == null ? null : (blank(req.adminUserId()) ? req.owner() : req.adminUserId());
    if (!blank(owner)) access.requireTenantUser(owner);
    return projectService.createProject(new ApiModels.CreateProjectReq(
        req == null ? null : req.code(),
        req == null ? null : req.name(),
        req == null ? null : req.description(),
        owner,
        owner,
        req == null ? null : req.bootstrapSpec(),
        req == null ? null : req.engines()));
  }

  @Transactional
  public ApiModels.ProjectDto patchProject(String tenantId, String projectId, ApiModels.PatchProjectReq req) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    return projectService.patchProject(projectId, req);
  }

  @Transactional
  public void deleteProject(String tenantId, String projectId) {
    requireTenant(tenantId);
    projectService.deleteProject(projectId);
  }

  public ApiModels.AppearanceDto getAppearance(String tenantId) {
    requireTenant(tenantId);
    AppearancePrefEntity e = prefs.selectOne(Wrappers.<AppearancePrefEntity>lambdaQuery()
        .eq(AppearancePrefEntity::getScope, "tenant")
        .eq(AppearancePrefEntity::getTenantId, tenantId));
    if (e == null) return new ApiModels.AppearanceDto("cyan", "drawer", "ink");
    return PlatformService.toAppearance(e, "drawer");
  }

  @Transactional
  public ApiModels.AppearanceDto putAppearance(String tenantId, ApiModels.AppearanceDto body) {
    requireTenant(tenantId);
    access.requireUser();
    if (!access.canAccessTenant(TenantContext.user(), tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权修改外观");
    }
    AppearancePrefEntity e = prefs.selectOne(Wrappers.<AppearancePrefEntity>lambdaQuery()
        .eq(AppearancePrefEntity::getScope, "tenant")
        .eq(AppearancePrefEntity::getTenantId, tenantId));
    if (e == null) {
      e = new AppearancePrefEntity();
      e.setScope("tenant");
      e.setTenantId(tenantId);
      e.setTheme("cyan");
      e.setMenuPos("drawer");
      e.setMenuColor("ink");
      prefs.insert(e);
    }
    if (body != null) {
      if (!blank(body.theme())) e.setTheme(body.theme());
      if (!blank(body.menuPos())) e.setMenuPos(body.menuPos());
      if (!blank(body.menuColor())) e.setMenuColor(body.menuColor());
      prefs.update(e, Wrappers.<AppearancePrefEntity>lambdaQuery()
          .eq(AppearancePrefEntity::getScope, "tenant")
          .eq(AppearancePrefEntity::getTenantId, tenantId));
    }
    return PlatformService.toAppearance(e, "drawer");
  }

  public ApiModels.AppearanceDto publicAppearance(String tenantId) {
    access.requireUser();
    if (!access.canAccessTenant(TenantContext.user(), tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权查看");
    }
    AppearancePrefEntity e = prefs.selectOne(Wrappers.<AppearancePrefEntity>lambdaQuery()
        .eq(AppearancePrefEntity::getScope, "tenant")
        .eq(AppearancePrefEntity::getTenantId, tenantId));
    if (e == null) return new ApiModels.AppearanceDto("cyan", "drawer", "ink");
    return PlatformService.toAppearance(e, "drawer");
  }

  public ApiModels.LlmDto getLlm(String tenantId) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    TenantLlmEntity e = llms.selectById(tenantId);
    if (e == null) return new ApiModels.LlmDto(false, "openai", "", "", false, null);
    return new ApiModels.LlmDto(
        Boolean.TRUE.equals(e.getEnabled()),
        e.getProvider(),
        e.getBaseUrl(),
        e.getModel(),
        e.getApiKeyEnc() != null && e.getApiKeyEnc().length > 0,
        null);
  }

  @Transactional
  public ApiModels.LlmDto putLlm(String tenantId, ApiModels.LlmDto body) {
    requireTenant(tenantId);
    access.requireTenantAdmin();
    TenantLlmEntity e = llms.selectById(tenantId);
    boolean insert = e == null;
    if (insert) {
      e = new TenantLlmEntity();
      e.setTenantId(tenantId);
    }
    if (body != null) {
      e.setEnabled(body.enabled());
      if (!blank(body.provider())) e.setProvider(body.provider());
      if (body.baseUrl() != null) e.setBaseUrl(body.baseUrl());
      if (body.model() != null) e.setModel(body.model());
      if (!blank(body.apiKey())) e.setApiKeyEnc(crypto.encrypt(body.apiKey()));
    }
    e.setUpdatedAt(OffsetDateTime.now());
    if (insert) llms.insert(e);
    else llms.updateById(e);
    return getLlm(tenantId);
  }

  public List<ApiModels.GrantDto> listGrants(String tenantId) {
    requireTenant(tenantId);
    access.requireMulti();
    access.requireRealTenantAdmin();
    return grants.selectList(Wrappers.<TenantGrantEntity>lambdaQuery()
            .eq(TenantGrantEntity::getTenantId, tenantId)
            .orderByDesc(TenantGrantEntity::getCreatedAt))
        .stream()
        .map(this::toGrant)
        .toList();
  }

  @Transactional
  public ApiModels.GrantDto createGrant(String tenantId, ApiModels.GrantReq req) {
    requireTenant(tenantId);
    access.requireMulti();
    access.requireRealTenantAdmin();
    TenantGrantEntity g = new TenantGrantEntity();
    g.setId("g-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
    g.setTenantId(tenantId);
    g.setCode(randomCode());
    g.setCreatedBy(TenantContext.user());
    g.setCreatedAt(OffsetDateTime.now());
    applyGrantExpiry(g, req);
    g.setModules(Jsons.toJson(normalizeGrantModules(req == null ? null : req.modules())));
    g.setAiCaps(Jsons.toJson(AiCaps.normalize(req == null ? null : req.aiCaps())));
    applyGrantProjects(g, tenantId, req);
    grants.insert(g);
    return toGrant(g);
  }

  @Transactional
  public ApiModels.GrantDto patchGrant(String tenantId, String grantId, ApiModels.GrantReq req) {
    requireTenant(tenantId);
    access.requireMulti();
    access.requireRealTenantAdmin();
    TenantGrantEntity g = grants.selectById(grantId);
    if (g == null || !tenantId.equals(g.getTenantId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权码不存在");
    }
    boolean touchExpiry = req != null && (
        Boolean.TRUE.equals(req.permanent())
            || !blank(req.expiresAt())
            || !blank(req.kind()));
    if (touchExpiry) {
      applyGrantExpiry(g, req);
      if ("permanent".equalsIgnoreCase(g.getKind())) {
        g.setExpiresAt(null);
      }
    }
    if (req != null && req.modules() != null) {
      g.setModules(Jsons.toJson(normalizeGrantModules(req.modules())));
    }
    if (req != null && req.aiCaps() != null) {
      g.setAiCaps(Jsons.toJson(AiCaps.normalize(req.aiCaps())));
    }
    if (req != null && (req.projectIds() != null || req.projectScopes() != null || req.defaultRole() != null)) {
      applyGrantProjects(g, tenantId, req);
    }
    grants.updateById(g);
    if (touchExpiry && "permanent".equalsIgnoreCase(g.getKind())) {
      grants.update(null, Wrappers.<TenantGrantEntity>lambdaUpdate()
          .eq(TenantGrantEntity::getId, grantId)
          .set(TenantGrantEntity::getKind, "permanent")
          .set(TenantGrantEntity::getExpiresAt, null));
    }
    return toGrant(grants.selectById(grantId));
  }

  @Transactional
  public void revokeGrant(String tenantId, String grantId) {
    requireTenant(tenantId);
    access.requireMulti();
    access.requireRealTenantAdmin();
    TenantGrantEntity g = grants.selectById(grantId);
    if (g == null || !tenantId.equals(g.getTenantId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权码不存在");
    }
    g.setRevokedAt(OffsetDateTime.now());
    grants.updateById(g);
    platformAccess.delete(Wrappers.<PlatformAccessEntity>lambdaQuery().eq(PlatformAccessEntity::getGrantId, grantId));
  }

  @Transactional
  public void deleteGrant(String tenantId, String grantId) {
    requireTenant(tenantId);
    access.requireMulti();
    access.requireRealTenantAdmin();
    TenantGrantEntity g = grants.selectById(grantId);
    if (g == null || !tenantId.equals(g.getTenantId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权码不存在");
    }
    platformAccess.delete(Wrappers.<PlatformAccessEntity>lambdaQuery().eq(PlatformAccessEntity::getGrantId, grantId));
    grants.deleteById(grantId);
  }

  @Transactional
  public void transferAdmin(String tenantId, String userId) {
    requireTenant(tenantId);
    access.requireMulti();
    access.requireRealTenantAdmin();
    if (blank(userId)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择用户");
    if (userId.equals(TenantContext.user())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能转让给自己");
    }
    UserEntity target = users.selectById(userId);
    if (target == null || !"active".equalsIgnoreCase(nz(target.getStatus(), "active"))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对方须为本组织未停用用户");
    }
    UserTenantEntity ut = access.membership(userId, tenantId);
    if (ut == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "对方须为本组织用户");
    UserTenantEntity self = access.membership(TenantContext.user(), tenantId);
    if (self == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "你不是本组织管理员");
    ut.setTenantRole("admin");
    userTenants.update(ut, Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getUserId, userId)
        .eq(UserTenantEntity::getTenantId, tenantId));
    self.setTenantRole("member");
    userTenants.update(self, Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getUserId, TenantContext.user())
        .eq(UserTenantEntity::getTenantId, tenantId));
    TenantEntity t = tenants.selectById(tenantId);
    t.setOwner(target.getDisplayName());
    tenants.updateById(t);
  }

  public TenantLlmEntity llmEntity(String tenantId) {
    return llms.selectById(tenantId);
  }

  private void requireTenant(String tenantId) {
    TenantEntity t = access.requireTenant();
    if (!t.getId().equals(tenantId)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "组织不匹配");
    }
  }

  private ApiModels.GrantDto toGrant(TenantGrantEntity g) {
    List<String> pids = Jsons.strings(g.getProjectIds());
    java.util.Map<String, String> roles = Jsons.stringMap(g.getProjectRoles());
    List<ApiModels.GrantProjectScope> scopes = new ArrayList<>();
    for (String id : pids) {
      scopes.add(new ApiModels.GrantProjectScope(id, grantRoleOf(roles, id)));
    }
    return new ApiModels.GrantDto(
        g.getId(),
        g.getTenantId(),
        g.getCode(),
        g.getKind(),
        g.getExpiresAt() == null ? null : g.getExpiresAt().toString(),
        TenantFilter.grantValid(g),
        g.getCreatedAt() == null ? null : g.getCreatedAt().toString(),
        Jsons.strings(g.getModules()),
        pids,
        scopes,
        grantRoleOf(roles, "*"),
        Jsons.strings(g.getAiCaps()));
  }

  private static String grantRoleOf(java.util.Map<String, String> roles, String key) {
    String r = roles.get(key);
    if (r == null || r.isBlank()) r = roles.get("*");
    if ("admin".equals(r) || "modeler".equals(r) || "viewer".equals(r)) return r;
    return "viewer";
  }

  private void applyGrantProjects(TenantGrantEntity g, String tenantId, ApiModels.GrantReq req) {
    List<String> ids = req == null ? List.of() : req.projectIds();
    if ((ids == null || ids.isEmpty()) && req != null && req.projectScopes() != null) {
      ids = req.projectScopes().stream()
          .map(ApiModels.GrantProjectScope::projectId)
          .filter((id) -> id != null && !id.isBlank())
          .toList();
    }
    List<String> projectIds = normalizeGrantProjects(tenantId, ids);
    java.util.Map<String, String> fromScope = new java.util.LinkedHashMap<>();
    if (req != null && req.projectScopes() != null) {
      for (ApiModels.GrantProjectScope s : req.projectScopes()) {
        if (s != null && s.projectId() != null) fromScope.put(s.projectId(), s.role());
      }
    }
    java.util.Map<String, String> roles = new java.util.LinkedHashMap<>();
    if (projectIds.isEmpty()) {
      roles.put("*", normalizeProjectRole(req == null ? null : req.defaultRole()));
    } else {
      for (String id : projectIds) {
        roles.put(id, normalizeProjectRole(fromScope.getOrDefault(id, req == null ? null : req.defaultRole())));
      }
    }
    g.setProjectIds(Jsons.toJson(projectIds));
    g.setProjectRoles(Jsons.toJson(roles));
  }

  private static String normalizeProjectRole(String role) {
    if ("admin".equals(role) || "modeler".equals(role) || "viewer".equals(role)) return role;
    return "viewer";
  }

  private static final Set<String> GRANT_MODULES = Set.of("warehouse", "serve", "quality", "materialize", "dev");

  private void applyGrantExpiry(TenantGrantEntity g, ApiModels.GrantReq req) {
    boolean permanent = req != null && Boolean.TRUE.equals(req.permanent());
    String kind = req == null || blank(req.kind()) ? "" : req.kind().trim().toLowerCase(Locale.ROOT);
    if (permanent || "permanent".equals(kind) && blank(req == null ? null : req.expiresAt())) {
      g.setKind("permanent");
      return;
    }
    if (req != null && !blank(req.expiresAt())) {
      OffsetDateTime at = parseExpiry(req.expiresAt());
      if (!at.isAfter(OffsetDateTime.now())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "到期时间须晚于现在");
      }
      g.setKind("until");
      g.setExpiresAt(at);
      return;
    }
    if ("1h".equals(kind) || "1hour".equals(kind)) {
      g.setKind("1h");
      g.setExpiresAt(OffsetDateTime.now().plusHours(1));
    } else if ("1d".equals(kind) || "1day".equals(kind)) {
      g.setKind("1d");
      g.setExpiresAt(OffsetDateTime.now().plusDays(1));
    } else if ("7d".equals(kind) || "7day".equals(kind)) {
      g.setKind("7d");
      g.setExpiresAt(OffsetDateTime.now().plusDays(7));
    } else {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择永久或指定到期时间");
    }
  }

  private static OffsetDateTime parseExpiry(String raw) {
    try {
      return OffsetDateTime.parse(raw);
    } catch (DateTimeParseException ignored) {
      try {
        return Instant.parse(raw).atZone(ZoneId.systemDefault()).toOffsetDateTime();
      } catch (DateTimeParseException e) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "到期时间格式无效");
      }
    }
  }

  private static List<String> normalizeGrantModules(List<String> raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    List<String> out = new ArrayList<>();
    for (String m : raw) {
      if (m != null && GRANT_MODULES.contains(m) && !out.contains(m)) out.add(m);
    }
    return out;
  }

  private List<String> normalizeGrantProjects(String tenantId, List<String> raw) {
    if (raw == null || raw.isEmpty()) return List.of();
    List<String> known = projects.selectList(Wrappers.<ProjectEntity>lambdaQuery().eq(ProjectEntity::getTenantId, tenantId))
        .stream().map(ProjectEntity::getId).toList();
    List<String> out = new ArrayList<>();
    for (String id : raw) {
      if (id != null && known.contains(id) && !out.contains(id)) out.add(id);
    }
    return out;
  }

  private void syncMemberships(String tenantId, String userId, List<ApiModels.MemberDto> memberships) {
    List<String> tenantPids = projects.selectList(Wrappers.<ProjectEntity>lambdaQuery()
            .eq(ProjectEntity::getTenantId, tenantId))
        .stream().map(ProjectEntity::getId).toList();
    java.util.Set<String> known = new java.util.HashSet<>(tenantPids);
    java.util.Set<String> keep = new java.util.HashSet<>();
    for (ApiModels.MemberDto item : memberships) {
      if (item == null || blank(item.projectId()) || !known.contains(item.projectId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "项目不存在或不属于本组织");
      }
      String role = blank(item.role()) ? "viewer" : item.role();
      if (!List.of("admin", "modeler", "viewer").contains(role)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "非法项目角色");
      }
      ProjectMemberEntity exist = members.selectOne(Wrappers.<ProjectMemberEntity>lambdaQuery()
          .eq(ProjectMemberEntity::getProjectId, item.projectId())
          .eq(ProjectMemberEntity::getUserId, userId));
      if (exist == null) {
        ProjectMemberEntity m = new ProjectMemberEntity();
        m.setProjectId(item.projectId());
        m.setUserId(userId);
        m.setRole(role);
        members.insert(m);
      } else if (!role.equals(exist.getRole())) {
        exist.setRole(role);
        members.update(exist, Wrappers.<ProjectMemberEntity>lambdaQuery()
            .eq(ProjectMemberEntity::getProjectId, item.projectId())
            .eq(ProjectMemberEntity::getUserId, userId));
      }
      keep.add(item.projectId());
    }
    if (!tenantPids.isEmpty()) {
      List<ProjectMemberEntity> existing = members.selectList(Wrappers.<ProjectMemberEntity>lambdaQuery()
          .in(ProjectMemberEntity::getProjectId, tenantPids)
          .eq(ProjectMemberEntity::getUserId, userId));
      for (ProjectMemberEntity e : existing) {
        if (!keep.contains(e.getProjectId())) {
          members.delete(Wrappers.<ProjectMemberEntity>lambdaQuery()
              .eq(ProjectMemberEntity::getProjectId, e.getProjectId())
              .eq(ProjectMemberEntity::getUserId, userId));
        }
      }
    }
  }

  private ApiModels.ProjectDto toProject(ProjectEntity p) {
    return new ApiModels.ProjectDto(
        p.getId(), p.getTenantId(), p.getCode(), p.getName(), p.getDescription(), p.getOwner(),
        p.getCreatedAt() == null ? null : p.getCreatedAt().toString(),
        nz(p.getStatus(), "active"),
        Jsons.strings(p.getEngines()));
  }

  private static String randomCode() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }
  private static String nz(String v, String d) { return blank(v) ? d : v; }
}
