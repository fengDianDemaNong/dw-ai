package com.dwai.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.DwaiProperties;
import com.dwai.platform.internal.OrgProjectPuller;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;

/** 从 JWT 认人，从 X-Tenant-Id 再验库。不注册为 Servlet Filter，以免跑两遍。 */
public class TenantFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(TenantFilter.class);

  private final DwaiProperties props;
  private final TenantMapper tenants;
  private final UserMapper users;
  private final UserTenantMapper userTenants;
  private final PlatformAccessMapper access;
  private final TenantGrantMapper grants;
  private final ProjectMapper projects;
  private final OrgProjectPuller puller;

  public TenantFilter(
      DwaiProperties props,
      TenantMapper tenants,
      UserMapper users,
      UserTenantMapper userTenants,
      PlatformAccessMapper access,
      TenantGrantMapper grants,
      ProjectMapper projects,
      OrgProjectPuller puller) {
    this.props = props;
    this.tenants = tenants;
    this.users = users;
    this.userTenants = userTenants;
    this.access = access;
    this.grants = grants;
    this.projects = projects;
    this.puller = puller;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      String rawTenantCode = blankToNull(request.getHeader("X-Tenant-Code"));
      String rawProjectCode = blankToNull(request.getHeader("X-Project-Code"));
      String rawTenantId = blankToNull(request.getHeader("X-Tenant-Id"));
      String rawProjectId = blankToNull(request.getHeader("X-Project-Id"));
      // 组织不再推送项目镜像（见 OrgProjectPuller 的类注释），这里分两条路：
      // 本地缺东西 → 同步拉（required=true，拉不到就 503）；本地有、只是该对账了 →
      // 后台刷一次（required=false，本次请求不受影响）。
      // 必须放在解析**之前**、且两头都看：解析不到租户时 resolveTenantId 会把原始值往下带
      // （见 :170 的注释），此时按租户 id 查项目必然查不到 —— 只看项目就永远触发不了。
      if (props.isMulti() && (rawTenantId != null || rawTenantCode != null
          || rawProjectId != null || rawProjectCode != null)) {
        try {
          puller.ensure(
              firstNonBlank(rawTenantCode, rawTenantId),
              firstNonBlank(rawProjectCode, rawProjectId),
              needsPull(rawTenantId, rawTenantCode, rawProjectId, rawProjectCode));
        } catch (OrgProjectPuller.Unavailable e) {
          // 与下面「租户已停用或不存在」的 403 分开：那是「去找管理员加你」，
          // 这个是「组织自己挂了」。混成一个，运维会拿租户号去查权限配置。
          //
          // 必须记日志：响应体里的 detail 只有调用方看得见（而且是个 iframe 里的页面），
          // 服务端一行不留的话，「拉取为什么失败」就只剩一个 503 —— 真排查时得靠猜。
          // 与 dw-lineage 的 TenantInterceptor 同一形态。
          log.warn("组织不可达，无法解析租户上下文：{}", e.getMessage());
          response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
          response.setContentType("application/json;charset=UTF-8");
          response.getWriter().write("{\"error\":\"组织平台不可用\",\"detail\":\""
              + e.getMessage() + "\",\"status\":503}");
          return;
        }
      }
      String headerTenant = resolveTenantId(
          blankToNull(request.getHeader("X-Tenant-Id")),
          rawTenantCode);
      String project = resolveProjectId(
          blankToNull(request.getHeader("X-Project-Id")),
          rawProjectCode,
          headerTenant);
      if (props.isStandalone()) {
        String tenant = headerTenant != null ? headerTenant : props.implicitTenantId();
        // 默认显示名不是「访客」：独立模式是一个完整的本地部署，只是没有登录这一层，
        // 访问者拿到的是完整管理员能力（`WarehouseLocalSeedRunner` 会种下同 id 的本地用户）。
        // 叫「访客」会让人以为进了只读模式 —— 而前端确实按角色渲染，角色一旦给不到位
        // （见 `AuthService.currentMe`），页面就真的只剩查看。
        String display = firstNonBlank(request.getHeader("X-User-Name"), "独立模式");
        TenantContext.set(tenant, project, TenantContext.STANDALONE_USER_ID, display, false, "admin");
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

  /**
   * 这一段租户/项目在本地能不能解析出来；解析不出来才值得去问组织。
   *
   * <p><b>为什么不能无条件拉</b>：{@code X-Tenant-Id: 1} 这种<b>数字（或本地主键）</b> 是既有的
   * 合法用法，本地就有，每个请求都去打组织纯属浪费；更要紧的是，组织不可达时本地能解析的
   * 请求<b>必须照常服务</b> —— 连不上组织就整个模块 503，等于把模块的能力绑死在组织上。
   *
   * <p>判据与 {@link #resolveTenantId}/{@link #resolveProjectId} 逐字一致（先按本地 id、
   * 再按 code），只是失败时返回「要不要问」而不是把原始值往下带 —— 两处若要改口径必须一起改，
   * 否则会出现「这边说解析得出来、那边说解析不出来」的分叉。
   */
  private boolean needsPull(String rawTenantId, String rawTenantCode, String rawProjectId, String rawProjectCode) {
    if (rawTenantId == null && rawTenantCode == null) return false;  // 连租户都没给：下面会 403，问了也没用
    String tenantId = localTenantId(rawTenantId, rawTenantCode);
    if (tenantId == null) return true;                               // 租户没落地：值得问一次
    if (rawProjectId == null && rawProjectCode == null) return false; // 没带项目：没什么可补
    return localProjectId(tenantId, rawProjectId, rawProjectCode) == null;
  }

  /** 与 {@link #resolveTenantId} 同一套判据，解析不出来时返回 {@code null}。 */
  private String localTenantId(String headerTenant, String tenantCode) {
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
    return null;
  }

  /** 与 {@link #resolveProjectId} 同一套判据，解析不出来时返回 {@code null}。 */
  private String localProjectId(String tenantId, String headerProject, String projectCode) {
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
    return null;
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
    // 解析不到时把原始值往下带，让下游的存在性校验拒绝它（403）。
    // 不要 return null：只给了 X-Tenant-Code 且解析不到时会变成「没有租户」的请求，
    // 与只给 X-Tenant-Id 对不上时的行为不一致，也让后续的租户归属校验失去依据
    // （TenantContext.tenantId() 为空时 requireProject 会跳过租户比对）。
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
