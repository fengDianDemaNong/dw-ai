package com.dwai.platform.auth;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.internal.OrgClient;
import com.dwai.platform.meta.dto.ApiModels;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/auth", "/api/v1/auth"})
public class AuthController {
  private final AuthService auth;
  private final DwaiProperties props;
  private final JwtSessionEpoch epoch;
  private final OrgClient orgClient;

  public AuthController(AuthService auth, DwaiProperties props, JwtSessionEpoch epoch, OrgClient orgClient) {
    this.auth = auth;
    this.props = props;
    this.epoch = epoch;
    this.orgClient = orgClient;
  }

  @GetMapping("/config")
  public Map<String, Object> config() {
    DwaiProperties.Security sec = props.getSecurity();
    DwaiProperties.Casdoor cas = sec.getCasdoor();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("runMode", props.runMode());
    out.put("deployMode", props.runMode());
    // 组织平台的**前端**地址。模块被直接打开（地址栏手打、没有 `#boot=` 自报宿主）时，
    // 前端靠它把「回门户登录」这类跳转指对地方 —— 以前这个地址来自构建期的
    // `VITE_ORG_ORIGIN`，改一次要重新构建前端。空 = 没配，前端用内置默认。
    //
    // 注意这里给的是 org-ui-url 而不是 org-base-url：后者是**后端**基址（5171 vs 18080
    // 在开发态就不一样），拿它拼 `/org/login` 会跳到接口服务上的 404。
    out.put("orgUiUrl", props.getOrgUiUrl() == null ? "" : props.getOrgUiUrl());
    out.put("product", props.product());
    out.put("mode", sec.isOidc() && cas.configured() ? "oidc" : "dev");
    out.put("allowLogin", props.needsAuth() && !(props.isWarehouseOnly() && props.isMulti()));
    out.put("allowDevLogin", sec.isAllowDevLogin() && !sec.isOidc());
    out.put("casdoorConfigured", cas.configured());
    out.put("sessionEpoch", epoch.id());
    out.put("accessTtlSeconds", sec.getJwtTtlSeconds());
    out.put("refreshTtlSeconds", sec.getRefreshTtlSeconds());
    out.put("idleTtlSeconds", sec.getIdleTtlSeconds());
    if (cas.configured()) {
      out.put("casdoor", Map.of(
          "issuer", cas.getIssuer(),
          "audience", cas.getAudience() == null ? "" : cas.getAudience()));
    }
    return out;
  }

  @PostMapping("/login")
  public ApiModels.LoginRes login(@RequestBody ApiModels.LoginReq req) {
    if (props.isStandalone() || (props.isWarehouseOnly() && props.isMulti())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前进程没有登录");
    }
    return auth.login(req == null ? null : req.username(), req == null ? null : req.password());
  }

  @PostMapping("/refresh")
  public ApiModels.LoginRes refresh(@RequestBody ApiModels.RefreshReq req) {
    if (props.isStandalone()) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "独立模式没有登录");
    }
    if (props.isWarehouseOnly() && props.isMulti()) {
      return orgClient.refresh(req == null ? null : req.refreshToken());
    }
    return auth.refresh(req == null ? null : req.refreshToken());
  }

  @PostMapping("/logout")
  public void logout(@RequestBody(required = false) ApiModels.RefreshReq req) {
    if (props.isWarehouseOnly() && props.isMulti()) {
      orgClient.revokeRefresh(req == null ? null : req.refreshToken());
      return;
    }
    auth.logout(req == null ? null : req.refreshToken());
  }

  @GetMapping("/tenants")
  public List<ApiModels.TenantDto> tenants() {
    return auth.myTenants();
  }

  @PostMapping("/select-tenant")
  public ApiModels.Me selectTenant(@RequestBody ApiModels.SelectTenantReq req) {
    return auth.selectTenant(req == null ? null : req.tenantId());
  }

  @PostMapping("/enter-tenant")
  public ApiModels.Me enterTenant(@RequestBody ApiModels.EnterTenantReq req) {
    return auth.enterTenant(req == null ? null : req.tenantId(), req == null ? null : req.code());
  }

  @GetMapping("/me")
  public ApiModels.Me me() {
    return auth.currentMe();
  }
}
