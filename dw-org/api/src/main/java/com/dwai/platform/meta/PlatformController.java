package com.dwai.platform.meta;

import com.dwai.platform.internal.ServiceRegistry;
import com.dwai.platform.meta.dto.ApiModels;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/platform", "/api/v1/platform"})
public class PlatformController {
  private final PlatformService platform;
  private final AccessService access;
  private final ServiceRegistry registry;

  public PlatformController(PlatformService platform, AccessService access, ServiceRegistry registry) {
    this.platform = platform;
    this.access = access;
    this.registry = registry;
  }

  @GetMapping("/tenants")
  public List<ApiModels.TenantDto> tenants() {
    return platform.listTenants();
  }

  @PostMapping("/tenants")
  public ApiModels.TenantDto createTenant(@RequestBody ApiModels.CreateAdminTenantReq req) {
    return platform.createTenant(req);
  }

  @PatchMapping("/tenants/{id}")
  public ApiModels.TenantDto patchTenant(@PathVariable String id, @RequestBody ApiModels.PatchAdminTenantReq req) {
    return platform.patchTenant(id, req);
  }

  /**
   * 重置该租户管理员的密码，并作废其已签发的 refresh token。
   *
   * <p>单独开一个端点而不是塞进 {@code PATCH /tenants/{id}}：那个是「改租户属性」，
   * 这个是「改某个账号的凭据」—— 两者的授权对象不同（前者动租户，后者动用户），
   * 混在一起以后要单独收紧或单独记审计时会很别扭。
   */
  @PostMapping("/tenants/{id}/reset-admin-password")
  public void resetAdminPassword(@PathVariable String id, @RequestBody(required = false) ApiModels.ResetAdminPasswordReq req) {
    platform.resetTenantAdminPassword(id, req == null ? null : req.password());
  }

  @GetMapping("/accounts")
  public List<ApiModels.OrgUserDto> accounts() {
    return platform.listAccounts();
  }

  @GetMapping("/users")
  public List<ApiModels.OrgUserDto> users() {
    return platform.listUsers();
  }

  @PostMapping("/users")
  public ApiModels.OrgUserDto createUser(@RequestBody ApiModels.CreatePlatformUserReq req) {
    return platform.createUser(req);
  }

  @PatchMapping("/users/{id}")
  public ApiModels.OrgUserDto patchUser(@PathVariable String id, @RequestBody ApiModels.PatchPlatformUserReq req) {
    return platform.patchUser(id, req);
  }

  @GetMapping("/appearance")
  public ApiModels.AppearanceDto appearance() {
    return platform.getAppearance();
  }

  @PutMapping("/appearance")
  public ApiModels.AppearanceDto putAppearance(@RequestBody ApiModels.AppearanceDto body) {
    return platform.putAppearance(body);
  }

  @GetMapping("/services")
  public List<Map<String, Object>> services() {
    access.requirePlatform();
    Instant now = Instant.now();
    return registry.all().stream()
        .sorted(Comparator.comparing(ServiceRegistry.Entry::product))
        .map((e) -> {
          boolean fresh = Duration.between(e.seenAt(), now).toSeconds() < 120;
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("product", e.product());
          row.put("version", e.version());
          row.put("baseUrl", e.baseUrl());
          row.put("seenAt", e.seenAt().toString());
          row.put("status", fresh ? "online" : "stale");
          return row;
        })
        .toList();
  }

  @PostMapping("/services")
  public Map<String, Object> registerService(@RequestBody(required = false) RegisterServiceReq req) {
    access.requirePlatform();
    if (req == null || req.product == null || req.product.isBlank() || req.baseUrl == null || req.baseUrl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 product 与 baseUrl");
    }
    ServiceRegistry.Entry e = registry.put(req.product.trim(), req.version, req.baseUrl.trim());
    return Map.of("product", e.product(), "version", e.version(), "baseUrl", e.baseUrl(), "seenAt", e.seenAt().toString(), "status", "online");
  }

  @DeleteMapping("/services/{product}")
  public void removeService(@PathVariable String product) {
    access.requirePlatform();
    registry.remove(product);
  }

  public static class RegisterServiceReq {
    public String product;
    public String version;
    public String baseUrl;
  }
}
