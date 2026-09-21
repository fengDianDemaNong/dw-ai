package com.dwai.platform.web;

import com.dwai.platform.meta.ProjectService;
import com.dwai.platform.meta.dto.ApiModels;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 组织与仓建设都要的会话入口。 */
@RestController
@RequestMapping({"/api", "/api/v1"})
public class SessionController {
  private final ProjectService projects;

  public SessionController(ProjectService projects) {
    this.projects = projects;
  }

  @GetMapping("/health")
  public Map<String, Object> health() {
    return Map.of("ok", true, "service", "dw-ai-api");
  }

  @GetMapping("/session")
  public ApiModels.SessionDto session() {
    return projects.session();
  }

  @GetMapping("/tenants")
  public List<ApiModels.TenantDto> tenants() {
    return projects.listTenants();
  }

  @GetMapping("/projects")
  public List<ApiModels.ProjectDto> listProjects() {
    return projects.listProjects();
  }
}
