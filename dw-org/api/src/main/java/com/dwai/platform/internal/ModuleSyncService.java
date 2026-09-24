package com.dwai.platform.internal;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.mapper.TenantMapper;
import com.dwai.platform.meta.support.Jsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ModuleSyncService {
  private static final Logger log = LoggerFactory.getLogger(ModuleSyncService.class);
  private final ServiceRegistry registry;
  private final DwaiProperties props;
  private final TenantMapper tenants;
  private final TenantLicenseMapper licenses;

  public ModuleSyncService(
      ServiceRegistry registry, DwaiProperties props, TenantMapper tenants, TenantLicenseMapper licenses) {
    this.registry = registry;
    this.props = props;
    this.tenants = tenants;
    this.licenses = licenses;
  }

  public void syncProject(ProjectEntity project) {
    if (!props.isMulti()) return;
    for (ServiceRegistry.Entry e : registry.all()) {
      if ("org".equals(e.product())) continue;
      put(e, project);
    }
  }

  public void removeProject(ProjectEntity project) {
    if (!props.isMulti() || project == null) return;
    for (ServiceRegistry.Entry e : registry.all()) {
      if ("org".equals(e.product())) continue;
      delete(e, project);
    }
  }

  public void syncProjectsTo(ServiceRegistry.Entry target, List<ProjectEntity> projects) {
    if (!props.isMulti() || target == null || "org".equals(target.product())) return;
    for (ProjectEntity project : projects) {
      put(target, project);
    }
  }

  private void put(ServiceRegistry.Entry e, ProjectEntity project) {
    String code = project.getCode() == null || project.getCode().isBlank() ? project.getId() : project.getCode();
    String tenantCode = tenantCodeOf(project.getTenantId());
    try {
      RestClient.builder().baseUrl(e.baseUrl().replaceAll("/$", "")).build()
          .put()
          .uri("/internal/v1/projects/{code}", code)
          .contentType(MediaType.APPLICATION_JSON)
          .headers(h -> {
            String tok = props.getSecurity().getModuleToken();
            if (tok != null && !tok.isBlank()) h.set("X-Module-Token", tok);
            if (tenantCode != null) h.set("X-Tenant-Code", tenantCode);
            if (project.getTenantId() != null) h.set("X-Tenant-Id", project.getTenantId());
            if (project.getId() != null) h.set("X-Project-Id", project.getId());
            h.set("X-Project-Code", code);
          })
          .body(bodyOf(project, code, tenantCode))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception ex) {
      log.warn("同步项目 {} 到 {} 失败: {}", code, e.product(), ex.getMessage());
    }
  }

  private void delete(ServiceRegistry.Entry e, ProjectEntity project) {
    String code = project.getCode() == null || project.getCode().isBlank() ? project.getId() : project.getCode();
    String tenantCode = tenantCodeOf(project.getTenantId());
    try {
      RestClient.builder().baseUrl(e.baseUrl().replaceAll("/$", "")).build()
          .delete()
          .uri("/internal/v1/projects/{code}", code)
          .headers(h -> {
            String tok = props.getSecurity().getModuleToken();
            if (tok != null && !tok.isBlank()) h.set("X-Module-Token", tok);
            if (tenantCode != null) h.set("X-Tenant-Code", tenantCode);
          })
          .retrieve()
          .toBodilessEntity();
    } catch (Exception ex) {
      log.warn("删除项目 {} 从 {} 失败: {}", code, e.product(), ex.getMessage());
    }
  }

  /**
   * 项目镜像的请求体。
   *
   * <p><b>为什么必须带 {@code modules} / {@code aiCaps}</b>：模块侧此前只能自己硬编码一条
   * 许可（{@code ProjectService.ensureTenant} 里写死的 {@code [warehouse, metadata]}），
   * 于是「组织里只开通了仓建设」的租户，在仓建设里照样看得见数据地图入口，
   * 点进去必然报错；反过来只开通数据地图的租户会被凭空赋予仓建设。许可的**权威在组织**，
   * 这里带过去，模块侧才有得校准。
   *
   * <p>用 {@link LinkedHashMap} 而不是 {@code Map.of}：本地查不到许可行时要发 {@code null}，
   * 而 {@code Map.of} 不接受 null 值。收方把 null 读作「这项别动」，空数组则是
   * 「确实一项都没开通」——两者不能混。
   */
  private Map<String, Object> bodyOf(ProjectEntity project, String code, String tenantCode) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", project.getName() == null ? code : project.getName());
    body.put("code", code);
    body.put("tenantCode", tenantCode == null ? "" : tenantCode);
    body.put("tenantName", tenantNameOf(project.getTenantId()));
    body.put("id", project.getId() == null ? "" : project.getId());
    body.put("modules", licenseOf(project.getTenantId(), true));
    body.put("aiCaps", licenseOf(project.getTenantId(), false));
    return body;
  }

  /** @return 该租户的许可项；本地没有这行许可时返回 {@code null}（收方跳过校准） */
  private List<String> licenseOf(String tenantId, boolean modules) {
    if (tenantId == null || tenantId.isBlank()) return null;
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    if (lic == null) return null;
    return Jsons.strings(modules ? lic.getModules() : lic.getAiCaps());
  }

  private String tenantCodeOf(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) return null;
    TenantEntity t = tenants.selectById(tenantId);
    return t == null ? null : t.getCode();
  }

  private String tenantNameOf(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) return "";
    TenantEntity t = tenants.selectById(tenantId);
    if (t == null || t.getName() == null || t.getName().isBlank()) return "";
    return t.getName();
  }
}
