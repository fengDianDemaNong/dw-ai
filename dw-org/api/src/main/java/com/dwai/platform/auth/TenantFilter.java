package com.dwai.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.entity.PlatformAccessEntity;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantGrantEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.entity.UserTenantEntity;
import com.dwai.platform.meta.mapper.PlatformAccessMapper;
import com.dwai.platform.meta.mapper.ProjectMapper;
import com.dwai.platform.meta.mapper.TenantGrantMapper;
import com.dwai.platform.meta.mapper.TenantMapper;
import com.dwai.platform.meta.mapper.UserMapper;
import com.dwai.platform.meta.mapper.UserTenantMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;

/** 从 JWT 认人，从 X-Tenant-Id 再验库。不注册为 Servlet Filter，以免跑两遍。 */
public class TenantFilter extends OncePerRequestFilter {
  private final DwaiProperties props;
  private final TenantMapper tenants;
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final PlatformAccessMapper access;
  private final TenantGrantMapper grants;
  private final ProjectMapper projects;

  public TenantFilter(
      DwaiProperties props,
      TenantMapper tenants,
      UserMapper users,
      UserTenantMapper userTenants,
      PlatformAccessMapper access,
      TenantGrantMapper grants,
      ProjectMapper projects) {
    this.props = props;
    this.tenants = tenants;
    this.users = users;
    this.userTenants = userTenants;
    this.access = access;
    this.grants = grants;
    this.projects = projects;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      String rawTenantCode = blankToNull(request.getHeader("X-Tenant-Code"));
      String rawProjectCode = blankToNull(request.getHeader("X-Project-Code"));
      String headerTenant = resolveTenantId(
          blankToNull(request.getHeader("X-Tenant-Id")),
          rawTenantCode);
      String project = resolveProjectId(
          blankToNull(request.getHeader("X-Project-Id")),
          rawProjectCode,
          headerTenant);
      if (props.isStandalone()) {
        String tenant = headerTenant != null ? headerTenant : props.implicitTenantId();
        String display = firstNonBlank(request.getHeader("X-User-Name"), "访客");
        TenantContext.set(tenant, project, "standalone", display, false, "admin");
        TenantContext.setCodes(rawTenantCode, rawProjectCode);
        chain.doFilter(request, response);
        return;
      }
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      String userId = "anonymous";
      String display = userId;
      boolean platformAdmin = false;
      String tenantRole = null;
      String tenant = null;
      if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
        userId = firstNonBlank(jwt.getSubject(), jwt.getClaimAsString("username"));
        UserEntity u = users.selectById(userId);
        if (u == null && jwt.getClaimAsString("username") != null) {
          u = users.selectByUsername(jwt.getClaimAsString("username"));
        }
        if (u != null) {
          userId = u.getId();
          display = firstNonBlank(u.getDisplayName(), u.getUsername(), userId);
          platformAdmin = Boolean.TRUE.equals(u.getPlatformAdmin());
        } else {
          display = firstNonBlank(jwt.getClaimAsString("name"), jwt.getClaimAsString("username"), userId);
          platformAdmin = Boolean.TRUE.equals(jwt.getClaim("platform_admin"));
        }
        if (headerTenant != null) {
          boolean warehouseMulti = props.isWarehouseOnly() && props.isMulti();
          if (!warehouseMulti && !canAccessTenant(userId, platformAdmin, headerTenant)) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"无权进入该组织\",\"status\":403}");
            return;
          }
          TenantEntity t = tenants.selectById(headerTenant);
          if (t == null) {
            if (!warehouseMulti) {
              response.setStatus(403);
              response.setContentType("application/json;charset=UTF-8");
              response.getWriter().write("{\"error\":\"租户已停用或不存在\",\"status\":403}");
              return;
            }
          } else if (!"active".equalsIgnoreCase(nz(t.getStatus(), "active")) && !isPlatformApi(request)) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"租户已停用或不存在\",\"status\":403}");
            return;
          }
          tenant = headerTenant;
          UserTenantEntity ut = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
              .eq(UserTenantEntity::getUserId, userId)
              .eq(UserTenantEntity::getTenantId, headerTenant));
          tenantRole = ut == null ? (warehouseMulti ? "member" : null) : ut.getTenantRole();
        } else if (props.isStandard()) {
          tenant = props.implicitTenantId();
          UserTenantEntity ut = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
              .eq(UserTenantEntity::getUserId, userId)
              .eq(UserTenantEntity::getTenantId, tenant));
          tenantRole = ut == null ? null : ut.getTenantRole();
        }
      }
      TenantContext.set(tenant, project, userId, display, platformAdmin, tenantRole);
      TenantContext.setCodes(
          firstNonBlank(rawTenantCode, tenantCodeOf(tenant)),
          firstNonBlank(rawProjectCode, projectCodeOf(project)));
      chain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
  }

  private String tenantCodeOf(String tenantId) {
    if (tenantId == null) return null;
    TenantEntity t = tenants.selectById(tenantId);
    return t == null ? null : t.getCode();
  }

  private String projectCodeOf(String projectId) {
    if (projectId == null) return null;
    ProjectEntity p = projects.selectById(projectId);
    return p == null ? null : p.getCode();
  }

  private String resolveTenantId(String headerTenant, String tenantCode) {
    if (headerTenant != null) {
      TenantEntity byId = tenants.selectById(headerTenant);
      if (byId != null) return byId.getId();
      TenantEntity byCode = tenants.selectByCode(headerTenant);
      if (byCode != null) return byCode.getId();
    }
    if (tenantCode != null) {
      TenantEntity byCode = tenants.selectByCode(tenantCode);
      if (byCode != null) return byCode.getId();
    }
    // 解析不到时一律把原始值往下带，让下面的存在性校验把它拒掉（403）。
    //
    // 这里刻意不 return null：只传了 X-Tenant-Code 且解析不到时，返回 null 会让
    // 请求「静默变成没有租户」，调用方拿到 200 —— 与只传 X-Tenant-Id 却对不上时的
    // 403 行为不一致，也正是技术方案 §3.3「解析不到不准静默回落」要禁止的形态。
    return headerTenant != null ? headerTenant : tenantCode;
  }

  private String resolveProjectId(String headerProject, String projectCode, String tenantId) {
    if (headerProject != null) {
      ProjectEntity byId = projects.selectById(headerProject);
      if (byId != null) return byId.getId();
    }
    if (projectCode != null && tenantId != null) {
      ProjectEntity byCode = projects.selectOne(Wrappers.<ProjectEntity>lambdaQuery()
          .eq(ProjectEntity::getTenantId, tenantId)
          .eq(ProjectEntity::getCode, projectCode));
      if (byCode != null) return byCode.getId();
    }
    return headerProject;
  }

  private boolean canAccessTenant(String userId, boolean platformAdmin, String tenantId) {
    UserTenantEntity ut = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
        .eq(UserTenantEntity::getUserId, userId)
        .eq(UserTenantEntity::getTenantId, tenantId));
    if (ut != null) return true;
    if (!platformAdmin || props.isStandard()) return false;
    PlatformAccessEntity pa = access.selectOne(Wrappers.<PlatformAccessEntity>lambdaQuery()
        .eq(PlatformAccessEntity::getUserId, userId)
        .eq(PlatformAccessEntity::getTenantId, tenantId));
    if (pa == null) return false;
    TenantGrantEntity g = grants.selectById(pa.getGrantId());
    return grantValid(g);
  }

  public static boolean grantValid(TenantGrantEntity g) {
    if (g == null || g.getRevokedAt() != null) return false;
    if ("permanent".equalsIgnoreCase(g.getKind())) return true;
    return g.getExpiresAt() != null && g.getExpiresAt().isAfter(OffsetDateTime.now());
  }

  private boolean isPlatformApi(HttpServletRequest request) {
    String p = request.getRequestURI();
    return p != null && (p.startsWith("/api/platform/") || p.startsWith("/api/v1/platform/"));
  }

  private String mapOrg(Jwt jwt) {
    String owner = firstNonBlank(jwt.getClaimAsString("owner"), jwt.getClaimAsString("organization"));
    if (owner == null) return null;
    Map<String, String> map = props.getSecurity().getCasdoor().orgToTenant();
    if (map.containsKey(owner)) return map.get(owner);
    TenantEntity byCode = tenants.selectByCode(owner);
    return byCode != null ? byCode.getId() : null;
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private static String nz(String v, String d) {
    return v == null || v.isBlank() ? d : v;
  }

  private static String firstNonBlank(String... vals) {
    if (vals == null) return null;
    for (String v : vals) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }
}
