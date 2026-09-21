package com.dwai.platform.internal;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.TenantEntity;
import com.dwai.platform.meta.mapper.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class ModuleSyncService {
  private static final Logger log = LoggerFactory.getLogger(ModuleSyncService.class);
  private final ServiceRegistry registry;
  private final DwaiProperties props;
  private final TenantMapper tenants;

  public ModuleSyncService(ServiceRegistry registry, DwaiProperties props, TenantMapper tenants) {
    this.registry = registry;
    this.props = props;
    this.tenants = tenants;
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
          .body(Map.of(
              "name", project.getName() == null ? code : project.getName(),
              "code", code,
              "tenantCode", tenantCode == null ? "" : tenantCode,
              "tenantName", tenantNameOf(project.getTenantId()),
              "id", project.getId() == null ? "" : project.getId()))
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
