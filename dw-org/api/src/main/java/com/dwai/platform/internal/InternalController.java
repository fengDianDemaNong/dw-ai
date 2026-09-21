package com.dwai.platform.internal;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.AccessService;
import com.dwai.platform.meta.ProjectService;
import com.dwai.platform.meta.dto.ApiModels;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {
  private final DwaiProperties props;
  private final ServiceRegistry registry;
  private final AccessService access;
  private final ProjectService projects;
  private final ModuleSyncService moduleSync;

  public InternalController(
      DwaiProperties props,
      ServiceRegistry registry,
      AccessService access,
      ProjectService projects,
      ModuleSyncService moduleSync) {
    this.props = props;
    this.registry = registry;
    this.access = access;
    this.projects = projects;
    this.moduleSync = moduleSync;
  }

  @PostMapping("/registry/heartbeat")
  public ServiceRegistry.Entry heartbeat(HttpServletRequest req, @RequestBody Heartbeat body) {
    assertInternal(req);
    if (body == null || body.product == null || body.product.isBlank() || body.baseUrl == null || body.baseUrl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 product 与 baseUrl");
    }
    String product = body.product.trim();
    String baseUrl = body.baseUrl.trim();
    ServiceRegistry.Entry prev = registry.get(product);
    boolean catchUp = prev == null
        || !baseUrl.equals(prev.baseUrl())
        || Duration.between(prev.seenAt(), Instant.now()).toSeconds() >= 120;
    ServiceRegistry.Entry entry = registry.put(product, body.version, baseUrl);
    if (catchUp) {
      moduleSync.syncProjectsTo(entry, projects.listAllEntities());
    }
    return entry;
  }

  @GetMapping("/registry")
  public CollectionDto registry(HttpServletRequest req) {
    assertInternal(req);
    return new CollectionDto(registry.all());
  }

  @GetMapping("/authz/check")
  public Map<String, Object> authz(
      HttpServletRequest req,
      @RequestParam String userId,
      @RequestParam(required = false) String tenantCode,
      @RequestParam(required = false) String projectCode,
      @RequestParam(required = false) String tenantId,
      @RequestParam(required = false) String projectId,
      @RequestParam(required = false, defaultValue = "warehouse") String product,
      @RequestParam(required = false, defaultValue = "model:read") String action) {
    assertInternal(req);
    return access.checkAuthz(userId, tenantCode, projectCode, product, action, tenantId, projectId);
  }

  @GetMapping("/context")
  public Map<String, Object> context(HttpServletRequest req) {
    assertInternal(req);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("tenantId", TenantContext.tenantId());
    out.put("projectId", TenantContext.projectId());
    out.put("tenantCode", TenantContext.tenantCode());
    out.put("projectCode", TenantContext.projectCode());
    out.put("userId", TenantContext.user());
    out.put("displayName", TenantContext.displayName());
    out.put("platformAdmin", TenantContext.platformAdmin());
    out.put("tenantRole", TenantContext.tenantRole());
    out.put("runMode", props.runMode());
    return out;
  }

  @GetMapping("/projects/{projectId}/bindings")
  public Map<String, Object> bindings(HttpServletRequest req, @PathVariable String projectId) {
    assertInternal(req);
    return Map.of("projectId", projectId, "products", registry.all().stream().map(ServiceRegistry.Entry::product).toList());
  }

  @PutMapping("/projects/{projectCode}")
  public ApiModels.ProjectDto upsertProject(
      HttpServletRequest req, @PathVariable String projectCode, @RequestBody(required = false) UpsertProject body) {
    assertInternal(req);
    return projects.upsertInternal(
        projectCode,
        body == null ? null : body.name,
        body == null ? null : firstNonBlank(body.tenantCode, null),
        body == null ? null : body.id);
  }

  @DeleteMapping("/projects/{projectCode}")
  public void deleteProject(HttpServletRequest req, @PathVariable String projectCode) {
    assertInternal(req);
    projects.deleteByCode(projectCode, null);
  }

  private void assertInternal(HttpServletRequest req) {
    if (props.isStandalone()) return;
    String expected = props.getSecurity().getModuleToken();
    if (expected != null && !expected.isBlank()) {
      String given = firstNonBlank(req.getHeader("X-Module-Token"), bearer(req.getHeader("Authorization")));
      if (!expected.equals(given)) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "模块令牌无效");
      }
      return;
    }
    // 未配模块令牌时，本机开发允许服务间调用（心跳在启动时没有用户 JWT）
    String user = TenantContext.user();
    if (user == null || user.isBlank() || "anonymous".equals(user)) {
      return;
    }
  }

  private static String bearer(String header) {
    if (header == null) return null;
    if (header.regionMatches(true, 0, "Bearer ", 0, 7)) return header.substring(7).trim();
    return null;
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) return a;
    if (b != null && !b.isBlank()) return b;
    return null;
  }

  public static class Heartbeat {
    public String product;
    public String version;
    public String baseUrl;
  }

  public static class UpsertProject {
    public String name;
    public String code;
    public String tenantCode;
    public String id;
  }

  public record CollectionDto(java.util.Collection<ServiceRegistry.Entry> services) {}
}
