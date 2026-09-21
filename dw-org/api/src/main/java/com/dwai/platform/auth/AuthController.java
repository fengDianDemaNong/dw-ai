package com.dwai.platform.auth;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.dto.ApiModels;
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

  public AuthController(AuthService auth, DwaiProperties props, JwtSessionEpoch epoch) {
    this.auth = auth;
    this.props = props;
    this.epoch = epoch;
  }

  @GetMapping("/config")
  public Map<String, Object> config() {
    DwaiProperties.Security sec = props.getSecurity();
    DwaiProperties.Casdoor cas = sec.getCasdoor();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("runMode", props.runMode());
    out.put("deployMode", props.runMode());
    out.put("product", props.product());
    out.put("mode", sec.isOidc() && cas.configured() ? "oidc" : "dev");
    out.put("allowLogin", props.needsAuth());
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
    if (props.isStandalone()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "独立模式没有登录");
    }
    return auth.login(req == null ? null : req.username(), req == null ? null : req.password());
  }

  @PostMapping("/refresh")
  public ApiModels.LoginRes refresh(@RequestBody ApiModels.RefreshReq req) {
    if (props.isStandalone()) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.FORBIDDEN, "独立模式没有登录");
    }
    return auth.refresh(req == null ? null : req.refreshToken());
  }

  @PostMapping("/logout")
  public void logout(@RequestBody(required = false) ApiModels.RefreshReq req) {
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
