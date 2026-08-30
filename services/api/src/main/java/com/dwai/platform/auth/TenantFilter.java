package com.dwai.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.entity.PlatformAccessEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantGrantEntity;
import com.dwai.platform.meta.entity.UserEntity;
import com.dwai.platform.meta.entity.UserTenantEntity;
import com.dwai.platform.meta.mapper.PlatformAccessMapper;
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

  public TenantFilter(
      DwaiProperties props,
      TenantMapper tenants,
      UserMapper users,
      UserTenantMapper userTenants,
      PlatformAccessMapper access,
      TenantGrantMapper grants) {
    this.props = props;
    this.tenants = tenants;
    this.users = users;
    this.userTenants = userTenants;
    this.access = access;
    this.grants = grants;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      Authentication auth = SecurityContextHolder.getContext().getAuthentication();
      String headerTenant = blankToNull(request.getHeader("X-Tenant-Id"));
      String project = blankToNull(request.getHeader("X-Project-Id"));
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
          if (!canAccessTenant(userId, platformAdmin, headerTenant)) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"无权进入该组织\",\"status\":403}");
            return;
          }
          TenantEntity t = tenants.selectById(headerTenant);
          if (t == null || (!"active".equalsIgnoreCase(nz(t.getStatus(), "active")) && !isPlatformApi(request))) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"租户已停用或不存在\",\"status\":403}");
            return;
          }
          tenant = headerTenant;
          UserTenantEntity ut = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
              .eq(UserTenantEntity::getUserId, userId)
              .eq(UserTenantEntity::getTenantId, headerTenant));
          tenantRole = ut == null ? null : ut.getTenantRole();
        } else if (props.isStandard()) {
          tenant = props.implicitTenantId();
          UserTenantEntity ut = userTenants.selectOne(Wrappers.<UserTenantEntity>lambdaQuery()
              .eq(UserTenantEntity::getUserId, userId)
              .eq(UserTenantEntity::getTenantId, tenant));
          tenantRole = ut == null ? null : ut.getTenantRole();
        }
      }
      TenantContext.set(tenant, project, userId, display, platformAdmin, tenantRole);
      chain.doFilter(request, response);
    } finally {
      TenantContext.clear();
    }
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
    return p != null && p.startsWith("/api/platform/");
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
