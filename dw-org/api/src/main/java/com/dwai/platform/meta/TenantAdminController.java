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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/tenants/{id}", "/api/v1/tenants/{id}"})
public class TenantAdminController {
  private final TenantAdminService tenants;
  private final AiPromptService prompts;
  private final KnowledgeService knowledge;
  private final ModulePolicyService modules;
  private final ComputeService computes;

  public TenantAdminController(
      TenantAdminService tenants, AiPromptService prompts, KnowledgeService knowledge,
      ModulePolicyService modules, ComputeService computes) {
    this.tenants = tenants;
    this.prompts = prompts;
    this.knowledge = knowledge;
    this.modules = modules;
    this.computes = computes;
  }

  /** 工作台「模块管理」：本组织在平台开通范围内的启停与可见范围。 */
  @GetMapping("/modules")
  public List<ApiModels.ModuleRowDto> modules(@PathVariable String id) {
    return modules.list(id);
  }

  /** 全量覆盖保存（空数组 = 清空全部策略，回到「没配过」）。 */
  @PutMapping("/modules")
  public List<ApiModels.ModuleRowDto> putModules(
      @PathVariable String id, @RequestBody List<ApiModels.ModulePolicyDto> body) {
    return modules.put(id, body);
  }

  /** 工作台「计算资源」：本组织自己的调度集群与数仓引擎。 */
  @GetMapping("/compute")
  public ApiModels.ComputeDto compute(@PathVariable String id) {
    return computes.get(id);
  }

  @PutMapping("/compute")
  public ApiModels.ComputeDto putCompute(
      @PathVariable String id, @RequestBody ApiModels.ComputePutReq body) {
    return computes.put(id, body);
  }

  /** 用已保存的配置探一次活。失败也回 200（结论写在 {@code schedulerStatus} 里）。 */
  @PostMapping("/compute/test")
  public ApiModels.ComputeDto testCompute(@PathVariable String id) {
    return computes.test(id);
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

  /**
   * 外观。`shell` 选哪一套：`workbench`（工作台壳）/ `project`（项目壳）。
   * **不带 = 老口径**（`scope='tenant'`）—— dw-model 前端就是这个用法，别改默认值。
   */
  @GetMapping("/appearance")
  public ApiModels.AppearanceDto appearance(
      @PathVariable String id, @RequestParam(required = false) String shell) {
    return tenants.getAppearance(id, shell);
  }

  @PutMapping("/appearance")
  public ApiModels.AppearanceDto putAppearance(
      @PathVariable String id,
      @RequestParam(required = false) String shell,
      @RequestBody ApiModels.AppearanceDto body) {
    return tenants.putAppearance(id, shell, body);
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
