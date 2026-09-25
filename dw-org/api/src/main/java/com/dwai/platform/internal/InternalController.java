package com.dwai.platform.internal;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.auth.AuthService;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/v1")
public class InternalController {
  private final DwaiProperties props;
  private final AccessService access;
  private final ProjectService projects;
  private final AuthService auth;

  public InternalController(
      DwaiProperties props,
      AccessService access,
      ProjectService projects,
      AuthService auth) {
    this.props = props;
    this.access = access;
    this.projects = projects;
    this.auth = auth;
  }

  /**
   * 模块替用户续期：模块不持有组织的 refresh token 表，只能委托。
   *
   * <p>为什么放在 {@code /internal/v1} 而不是让模块直接打 {@code /api/auth/refresh}：
   * {@code /api/**} 是给浏览器用的接口，按用户身份鉴权；模块是服务间调用，
   * 走的是模块令牌那条线。混用等于让模块把组织的<b>业务响应格式</b>当成契约，
   * 组织改一个字段就要同步升级所有模块 —— 这正是技术方案 §2 禁止「直打兄弟 /api」的原因。
   *
   * <p>响应体与 {@code /api/auth/refresh} 完全一致，模块侧只换 URI 与加令牌。
   */
  @PostMapping("/auth/refresh")
  public ApiModels.LoginRes refresh(HttpServletRequest req, @RequestBody(required = false) ApiModels.RefreshReq body) {
    assertInternal(req);
    return auth.refresh(body == null ? null : body.refreshToken());
  }

  @PostMapping("/auth/logout")
  public void logout(HttpServletRequest req, @RequestBody(required = false) ApiModels.RefreshReq body) {
    assertInternal(req);
    auth.logout(body == null ? null : body.refreshToken());
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

  /**
   * 某租户全部项目的成员（含显示名），供模块侧拉取。
   *
   * <p>走 {@code /internal/v1} 的模块令牌门禁，<b>不带用户身份</b> —— 发起方是模块本身，
   * 不是某个用户。所以模块侧拿到之后只能用它在页面上<b>展示</b>成员；「谁能看哪个项目」
   * 仍然走 {@link #authz}（那里按 userId 逐个算），两者不能互相替代。
   */
  @GetMapping("/members")
  public List<ApiModels.MemberDto> members(
      HttpServletRequest req, @RequestParam(required = false) String tenantCode) {
    assertInternal(req);
    return projects.membersOfTenant(tenantCode);
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

  /**
   * 模块主动拉一个项目（项目同步由推送改为拉取后的主通道）。
   *
   * <p><b>路径里为什么带 {@code by-code/}</b>：紧挨着的 {@code DELETE /projects/{projectCode}}
   * 与它同形，而语义上「编码」和「主键」是两种东西 —— 组织侧项目的唯一约束是
   * {@code UNIQUE (tenant_id, code)}，{@code code} 单独并不唯一。路径里写死 {@code by-code}
   * 让看 URL 的人不必再去猜这个变量是什么，也避免以后有人按 id 调它。
   *
   * <p><b>{@code tenantCode} 必填</b>（缺了 400）：它不能回落到 {@code TenantContext} ——
   * 这条路径上没有 JWT，回落等于让「谁在问」决定「查哪个租户」，那就是跨租户读。
   *
   * <p>返回体里的 {@code modules}/{@code aiCaps} 在「组织没有这行许可」时会<b>缺席</b>：
   * 组织全局配了 {@code spring.jackson.default-property-inclusion: non_null}，
   * 所以 {@code LinkedHashMap} 里的 null 值序列化后是键不存在。收方用 {@code Map.get}
   * 读，缺席与显式 null 完全等价（都走「别动本地那份」分支），空数组才是
   * 「确实一项都没开通」—— 这个区分不能塌。
   *
   * <p>找不到项目回 <b>404</b>：调用方要据此区分「组织明确说没有」与「组织不可用」——
   * 前者该重试要等，后者该 503 让运维去看 org。
   */
  @GetMapping("/projects/by-code/{code}")
  public Map<String, Object> projectByCode(
      HttpServletRequest req, @PathVariable String code, @RequestParam(required = false) String tenantCode) {
    assertInternal(req);
    Map<String, Object> found = projects.projectByCode(tenantCode, code);
    if (found == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "项目不存在");
    }
    return found;
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

  /**
   * 服务间调用的唯一门禁：静态共享密钥。
   *
   * <p><b>未配置密钥时拒绝，而不是放行。</b>此前这里是「密钥为空就直接返回」，
   * 而 {@code /internal/v1/**} 在 SecurityConfig 里又是 {@code permitAll} ——
   * 两者叠加等于把「忘了配」变成「默认开放」。默认配置下 module-token 恰恰就是空的，
   * 所以最省事的部署方式换来的是最开放的接口。现在未配置的表现是 401，
   * 并且错误信息里带上要设的属性名。
   *
   * <p>{@code MessageDigest.isEqual} 做定长比对：{@code String.equals} 会在第一个
   * 不同的字节处提前返回，攻击者能按响应耗时逐字节把密钥试出来。
   *
   * <p>独立模式放行是刻意的：那种模式没有组织平面，不存在服务间调用。
   *
   * <p><b>改动需三处同步</b>：dw-model 的 {@code WarehouseInternalController.assertModule}
   * 与 dw-lineage 的 {@code InternalProjectController.assertModule} 是同一份逻辑的副本
   * （三个服务不共享这些类，各自的测试守着各自的行为）。
   */
  private void assertInternal(HttpServletRequest req) {
    if (props.isStandalone()) return;
    String expected = props.getSecurity().getModuleToken();
    if (expected == null || expected.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.UNAUTHORIZED,
          "未配置模块令牌，服务间接口已拒绝：请设置 dwai.security.module-token（环境变量 MODULE_TOKEN）");
    }
    String given = firstNonBlank(req.getHeader("X-Module-Token"), bearer(req.getHeader("Authorization")));
    if (!sameToken(expected, given)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "模块令牌无效");
    }
  }

  /** 定长比对，避免靠响应耗时逐字节猜密钥。 */
  private static boolean sameToken(String expected, String given) {
    if (given == null) return false;
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
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

  public static class UpsertProject {
    public String name;
    public String code;
    public String tenantCode;
    public String id;
  }
}
