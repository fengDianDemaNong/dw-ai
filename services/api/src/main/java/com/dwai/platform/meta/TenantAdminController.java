package com.dwai.platform.meta;

import com.dwai.platform.meta.dto.ApiModels;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/tenants/{id}")
public class TenantAdminController {
  private final TenantAdminService tenants;
  private final AiPromptService prompts;
  private final KnowledgeService knowledge;

  public TenantAdminController(TenantAdminService tenants, AiPromptService prompts, KnowledgeService knowledge) {
    this.tenants = tenants;
    this.prompts = prompts;
    this.knowledge = knowledge;
  }

  @GetMapping("/users")
  public List<ApiModels.OrgUserDto> users(@PathVariable String id) {
    return tenants.listUsers(id);
  }

  @PostMapping("/users")
  public ApiModels.OrgUserDto createUser(@PathVariable String id, @RequestBody ApiModels.CreateOrgUserReq req) {
    return tenants.createUser(id, req);
  }

  @PatchMapping("/users/{userId}")
  public ApiModels.OrgUserDto patchUser(
      @PathVariable String id, @PathVariable String userId, @RequestBody ApiModels.PatchOrgUserReq req) {
    return tenants.patchUser(id, userId, req);
  }

  @DeleteMapping("/users/{userId}")
  public void deleteUser(@PathVariable String id, @PathVariable String userId) {
    tenants.removeUser(id, userId);
  }

  @GetMapping("/projects")
  public List<ApiModels.ProjectDto> projects(@PathVariable String id) {
    return tenants.listProjects(id);
  }

  @PostMapping("/projects")
  public ApiModels.ProjectDto createProject(@PathVariable String id, @RequestBody ApiModels.CreateProjectReq req) {
    return tenants.createProject(id, req);
  }

  @PatchMapping("/projects/{projectId}")
  public ApiModels.ProjectDto patchProject(
      @PathVariable String id, @PathVariable String projectId, @RequestBody ApiModels.PatchProjectReq req) {
    return tenants.patchProject(id, projectId, req);
  }

  @DeleteMapping("/projects/{projectId}")
  public void deleteProject(@PathVariable String id, @PathVariable String projectId) {
    tenants.deleteProject(id, projectId);
  }

  @GetMapping("/appearance")
  public ApiModels.AppearanceDto appearance(@PathVariable String id) {
    return tenants.getAppearance(id);
  }

  @PutMapping("/appearance")
  public ApiModels.AppearanceDto putAppearance(@PathVariable String id, @RequestBody ApiModels.AppearanceDto body) {
    return tenants.putAppearance(id, body);
  }

  @GetMapping("/llm")
  public ApiModels.LlmDto llm(@PathVariable String id) {
    return tenants.getLlm(id);
  }

  @PutMapping("/llm")
  public ApiModels.LlmDto putLlm(@PathVariable String id, @RequestBody ApiModels.LlmDto body) {
    return tenants.putLlm(id, body);
  }

  @GetMapping("/grants")
  public List<ApiModels.GrantDto> grants(@PathVariable String id) {
    return tenants.listGrants(id);
  }

  @PostMapping("/grants")
  public ApiModels.GrantDto createGrant(@PathVariable String id, @RequestBody ApiModels.GrantReq req) {
    return tenants.createGrant(id, req);
  }

  @PatchMapping("/grants/{grantId}")
  public ApiModels.GrantDto patchGrant(
      @PathVariable String id, @PathVariable String grantId, @RequestBody ApiModels.GrantReq req) {
    return tenants.patchGrant(id, grantId, req);
  }

  @PostMapping("/grants/{grantId}/revoke")
  public void revoke(@PathVariable String id, @PathVariable String grantId) {
    tenants.revokeGrant(id, grantId);
  }

  @DeleteMapping("/grants/{grantId}")
  public void deleteGrant(@PathVariable String id, @PathVariable String grantId) {
    tenants.deleteGrant(id, grantId);
  }

  @PostMapping("/transfer-admin")
  public void transfer(@PathVariable String id, @RequestBody ApiModels.TransferAdminReq req) {
    tenants.transferAdmin(id, req == null ? null : req.userId());
  }

  @GetMapping("/ai-prompts")
  public ApiModels.AiPromptsDto aiPrompts(@PathVariable String id) {
    return prompts.get(id);
  }

  @PutMapping("/ai-prompts")
  public ApiModels.AiPromptsDto putAiPrompts(@PathVariable String id, @RequestBody ApiModels.AiPromptsPutReq body) {
    return prompts.put(id, body);
  }

  @GetMapping("/knowledge")
  public java.util.List<ApiModels.KnowledgeArticleDto> knowledge(@PathVariable String id) {
    return knowledge.listImported(id);
  }

  @PostMapping("/knowledge/import")
  public java.util.Map<String, Object> importKnowledge(
      @PathVariable String id, @RequestBody ApiModels.KnowledgeImportReq body) {
    return knowledge.importArticles(id, body);
  }

  @DeleteMapping("/knowledge/{engine}/{articleId}")
  public void deleteKnowledge(
      @PathVariable String id, @PathVariable String engine, @PathVariable String articleId) {
    knowledge.deleteImported(id, engine, articleId);
  }
}
