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
@RequestMapping("/api/auth")
public class AuthController {
  private final AuthService auth;
  private final DwaiProperties props;

  public AuthController(AuthService auth, DwaiProperties props) {
    this.auth = auth;
    this.props = props;
  }

  @GetMapping("/config")
  public Map<String, Object> config() {
    DwaiProperties.Security sec = props.getSecurity();
    DwaiProperties.Casdoor cas = sec.getCasdoor();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("deployMode", props.isStandard() ? "standard" : "multi");
    out.put("mode", sec.isOidc() && cas.configured() ? "oidc" : "dev");
    out.put("allowLogin", true);
    out.put("allowDevLogin", sec.isAllowDevLogin() && !sec.isOidc());
    out.put("casdoorConfigured", cas.configured());
    if (cas.configured()) {
      out.put("casdoor", Map.of(
          "issuer", cas.getIssuer(),
          "audience", cas.getAudience() == null ? "" : cas.getAudience()));
    }
    return out;
  }

  @PostMapping("/login")
  public ApiModels.LoginRes login(@RequestBody ApiModels.LoginReq req) {
    return auth.login(req == null ? null : req.username(), req == null ? null : req.password());
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
