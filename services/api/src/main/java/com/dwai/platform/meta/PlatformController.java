package com.dwai.platform.meta;

import com.dwai.platform.meta.dto.ApiModels;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/platform")
public class PlatformController {
  private final PlatformService platform;

  public PlatformController(PlatformService platform) {
    this.platform = platform;
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
}
