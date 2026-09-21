package com.dwai.platform.internal;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.ProjectService;
import com.dwai.platform.meta.dto.ApiModels;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/internal/v1")
public class WarehouseInternalController {
  private final DwaiProperties props;
  private final ProjectService projects;

  public WarehouseInternalController(DwaiProperties props, ProjectService projects) {
    this.props = props;
    this.projects = projects;
  }

  @PutMapping("/projects/{projectCode}")
  public ApiModels.ProjectDto upsert(
      HttpServletRequest req, @PathVariable String projectCode, @RequestBody(required = false) Body body) {
    assertModule(req);
    String tenantCode = body == null ? null : firstNonBlank(body.tenantCode, TenantContext.tenantCode());
    if (tenantCode == null) tenantCode = req.getHeader("X-Tenant-Code");
    return projects.upsertInternal(
        projectCode,
        body == null ? null : body.name,
        tenantCode,
        body == null ? null : body.id,
        body == null ? null : body.tenantName);
  }

  @DeleteMapping("/projects/{projectCode}")
  public void delete(HttpServletRequest req, @PathVariable String projectCode) {
    assertModule(req);
    String tenantCode = req.getHeader("X-Tenant-Code");
    projects.deleteByCode(projectCode, tenantCode);
  }

  private void assertModule(HttpServletRequest req) {
    if (props.isStandalone()) return;
    String expected = props.getSecurity().getModuleToken();
    if (expected != null && !expected.isBlank()) {
      String given = req.getHeader("X-Module-Token");
      if (!expected.equals(given)) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "模块令牌无效");
      }
      return;
    }
    // 未配模块令牌时，本机开发允许组织 fan-out（与 InternalController 一致）
    String user = TenantContext.user();
    if (user == null || user.isBlank() || "anonymous".equals(user)) {
      return;
    }
  }

  private static String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) return a;
    return b == null || b.isBlank() ? null : b;
  }

  public static class Body {
    public String name;
    public String code;
    public String tenantCode;
    public String tenantName;
    public String id;
  }
}
