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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

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
        body == null ? null : body.tenantName,
        body == null ? null : body.modules,
        body == null ? null : body.aiCaps);
  }

  @DeleteMapping("/projects/{projectCode}")
  public void delete(HttpServletRequest req, @PathVariable String projectCode) {
    assertModule(req);
    String tenantCode = req.getHeader("X-Tenant-Code");
    projects.deleteByCode(projectCode, tenantCode);
  }

  /**
   * 服务间调用的唯一门禁：静态共享密钥（与 dw-org / dw-lineage 同一形态）。
   *
   * <p><b>未配置密钥时拒绝，而不是放行。</b>此前这里是「密钥为空就往下走」，
   * 而 {@code /internal/v1/**} 在 SecurityConfig 里是 {@code permitAll} ——
   * 叠加的效果是：默认部署（module-token 为空）下这两个入口（PUT / DELETE 项目镜像）
   * 任何人都能调。现在未配置的表现是 401，错误信息里带要设的属性名。
   *
   * <p>{@code MessageDigest.isEqual} 做定长比对，避免按响应耗时逐字节猜密钥。
   *
   * <p>独立模式放行是刻意的：没有组织平面，不存在服务间调用。
   *
   * <p><b>改动需三处同步</b>：dw-org 的 {@code InternalController.assertInternal}、
   * dw-lineage 的 {@code InternalProjectController.assertModule}。
   */
  private void assertModule(HttpServletRequest req) {
    if (props.isStandalone()) return;
    String expected = props.getSecurity().getModuleToken();
    if (expected == null || expected.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED,
          "未配置模块令牌，服务间接口已拒绝：请设置 dwai.security.module-token（环境变量 MODULE_TOKEN）");
    }
    String given = req.getHeader("X-Module-Token");
    if (given == null
        || !MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8))) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "模块令牌无效");
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

    /**
     * 该租户在组织侧开通的模块。
     *
     * <p><b>null 与空数组不是一回事</b>：null = 组织没带这项（老版本组织，或本地没有
     * 那行许可），此时不校准本地许可；空数组 = 组织那边确实一项都没开通。
     */
    public List<String> modules;

    /** 同上，组织侧算出的 AI 能力开关（随 modules 一起变）。 */
    public List<String> aiCaps;
  }
}
