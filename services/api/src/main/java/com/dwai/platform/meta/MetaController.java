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
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MetaController {
  private final ProjectService projects;
  private final SpecService spec;
  private final TableService tables;
  private final TableVersionService versions;
  private final AiService ai;
  private final KnowledgeService knowledge;

  public MetaController(
      ProjectService projects, SpecService spec, TableService tables,
      TableVersionService versions, AiService ai, KnowledgeService knowledge) {
    this.projects = projects;
    this.spec = spec;
    this.tables = tables;
    this.versions = versions;
    this.ai = ai;
    this.knowledge = knowledge;
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

  @PostMapping("/projects")
  public ApiModels.ProjectDto createProject(@RequestBody ApiModels.CreateProjectReq req) {
    return projects.createProject(req);
  }

  @GetMapping("/projects/{projectId}")
  public ApiModels.ProjectDto getProject(@PathVariable String projectId) {
    return projects.getProject(projectId);
  }

  @PatchMapping("/projects/{projectId}")
  public ApiModels.ProjectDto patchProject(
      @PathVariable String projectId, @RequestBody ApiModels.PatchProjectReq req) {
    return projects.patchProject(projectId, req);
  }

  @DeleteMapping("/projects/{projectId}")
  public void deleteProject(@PathVariable String projectId) {
    projects.deleteProject(projectId);
  }

  @GetMapping("/projects/{projectId}/snapshot")
  public ApiModels.SnapshotDto snapshot(@PathVariable String projectId) {
    return projects.snapshot(projectId);
  }

  @GetMapping("/projects/{projectId}/members")
  public List<ApiModels.MemberDto> members(@PathVariable String projectId) {
    return projects.listMembers(projectId);
  }

  @PutMapping("/projects/{projectId}/members/{userId}")
  public ApiModels.MemberDto putMember(
      @PathVariable String projectId, @PathVariable String userId, @RequestBody ApiModels.MemberReq req) {
    return projects.putMember(projectId, new ApiModels.MemberReq(userId, req.role()));
  }

  @DeleteMapping("/projects/{projectId}/members/{userId}")
  public void deleteMember(@PathVariable String projectId, @PathVariable String userId) {
    projects.deleteMember(projectId, userId);
  }

  @GetMapping("/projects/{projectId}/domains")
  public List<ApiModels.DomainDto> domains(@PathVariable String projectId) {
    return spec.listDomains(projectId);
  }

  @PostMapping("/projects/{projectId}/domains")
  public ApiModels.DomainDto createDomain(@PathVariable String projectId, @RequestBody ApiModels.DomainDto body) {
    return spec.saveDomain(projectId, body);
  }

  @PutMapping("/projects/{projectId}/domains/{id}")
  public ApiModels.DomainDto updateDomain(
      @PathVariable String projectId, @PathVariable String id, @RequestBody ApiModels.DomainDto body) {
    return spec.saveDomain(projectId, new ApiModels.DomainDto(
        id, projectId, body.code(), body.name(), body.definition(),
        body.bizOwner(), body.techOwner(), body.dataOwner(), body.related(), body.coreEntities()));
  }

  @DeleteMapping("/projects/{projectId}/domains/{id}")
  public void deleteDomain(@PathVariable String projectId, @PathVariable String id) {
    spec.deleteDomain(projectId, id);
  }

  @GetMapping("/projects/{projectId}/layers")
  public List<ApiModels.LayerDto> layers(@PathVariable String projectId) {
    return spec.listLayers(projectId);
  }

  @PostMapping("/projects/{projectId}/layers")
  public ApiModels.LayerDto createLayer(@PathVariable String projectId, @RequestBody ApiModels.LayerDto body) {
    return spec.saveLayer(projectId, null, body);
  }

  @PutMapping("/projects/{projectId}/layers/{layer}")
  public ApiModels.LayerDto updateLayer(
      @PathVariable String projectId, @PathVariable String layer, @RequestBody ApiModels.LayerDto body) {
    return spec.saveLayer(projectId, layer, body);
  }

  @DeleteMapping("/projects/{projectId}/layers/{layer}")
  public void deleteLayer(@PathVariable String projectId, @PathVariable String layer) {
    spec.deleteLayer(projectId, layer);
  }

  @GetMapping("/projects/{projectId}/grades")
  public List<ApiModels.GradeDto> grades(@PathVariable String projectId) {
    return spec.listGrades(projectId);
  }

  @PostMapping("/projects/{projectId}/grades")
  public ApiModels.GradeDto createGrade(@PathVariable String projectId, @RequestBody ApiModels.GradeDto body) {
    return spec.saveGrade(projectId, body);
  }

  @PutMapping("/projects/{projectId}/grades/{id}")
  public ApiModels.GradeDto updateGrade(
      @PathVariable String projectId, @PathVariable String id, @RequestBody ApiModels.GradeDto body) {
    return spec.saveGrade(projectId, new ApiModels.GradeDto(
        id, projectId, body.code(), body.name(), body.level(), body.color(),
        body.query(), body.export(), body.note(), body.examples()));
  }

  @DeleteMapping("/projects/{projectId}/grades/{id}")
  public void deleteGrade(@PathVariable String projectId, @PathVariable String id) {
    spec.deleteGrade(projectId, id);
  }

  @GetMapping("/projects/{projectId}/roots")
  public List<ApiModels.RootDto> roots(@PathVariable String projectId) {
    return spec.listRoots(projectId);
  }

  @PostMapping("/projects/{projectId}/roots")
  public ApiModels.RootDto createRoot(@PathVariable String projectId, @RequestBody ApiModels.RootDto body) {
    return spec.saveRoot(projectId, body);
  }

  @PutMapping("/projects/{projectId}/roots/{id}")
  public ApiModels.RootDto updateRoot(
      @PathVariable String projectId, @PathVariable String id, @RequestBody ApiModels.RootDto body) {
    return spec.saveRoot(projectId, new ApiModels.RootDto(
        id, projectId, body.kind(), body.code(), body.zh(), body.en(),
        body.domain(), body.formula(), body.dataType(), body.format()));
  }

  @DeleteMapping("/projects/{projectId}/roots/{id}")
  public void deleteRoot(@PathVariable String projectId, @PathVariable String id) {
    spec.deleteRoot(projectId, id);
  }

  @PutMapping("/projects/{projectId}/spec")
  public void syncSpec(@PathVariable String projectId, @RequestBody ApiModels.SpecSync body) {
    spec.syncSpec(projectId, body);
  }

  @PostMapping("/projects/{projectId}/spec/bootstrap")
  public void bootstrap(@PathVariable String projectId) {
    spec.bootstrap(projectId);
  }

  @GetMapping("/projects/{projectId}/tables")
  public List<ApiModels.TableDto> listTables(@PathVariable String projectId) {
    return tables.listTables(projectId);
  }

  @PostMapping("/projects/{projectId}/tables")
  public ApiModels.TableDto createTable(@PathVariable String projectId, @RequestBody ApiModels.TableDto body) {
    return tables.saveTable(projectId, body);
  }

  @GetMapping("/projects/{projectId}/tables/{tableId}")
  public ApiModels.TableDto getTable(@PathVariable String projectId, @PathVariable String tableId) {
    return tables.getTable(projectId, tableId);
  }

  @PutMapping("/projects/{projectId}/tables/{tableId}")
  public ApiModels.TableDto updateTable(
      @PathVariable String projectId, @PathVariable String tableId, @RequestBody ApiModels.TableDto body) {
    return tables.saveTable(projectId, new ApiModels.TableDto(
        tableId, projectId, body.layer(), body.name(), body.comment(), body.domain(),
        body.sourceSystem(), body.grain(), body.period(), body.columns(), body.partition(),
        body.storedAs(), body.status(), body.createdFrom(), body.grade()));
  }

  @DeleteMapping("/projects/{projectId}/tables/{tableId}")
  public void deleteTable(@PathVariable String projectId, @PathVariable String tableId) {
    tables.deleteTable(projectId, tableId);
  }

  @PutMapping("/projects/{projectId}/tables-bundle")
  public void syncTables(@PathVariable String projectId, @RequestBody List<ApiModels.TableDto> body) {
    tables.syncTables(projectId, body);
  }

  @GetMapping("/projects/{projectId}/drafts")
  public List<ApiModels.DraftDto> listDrafts(@PathVariable String projectId) {
    return tables.listDrafts(projectId);
  }

  @PostMapping("/projects/{projectId}/drafts")
  public ApiModels.DraftDto createDraft(@PathVariable String projectId, @RequestBody ApiModels.DraftDto body) {
    return tables.saveDraft(projectId, body);
  }

  @PutMapping("/projects/{projectId}/drafts/{draftId}")
  public ApiModels.DraftDto updateDraft(
      @PathVariable String projectId, @PathVariable String draftId, @RequestBody ApiModels.DraftDto body) {
    return tables.saveDraft(projectId, new ApiModels.DraftDto(
        draftId, projectId, body.sourceTableId(), body.targetLayer(), body.domainCode(),
        body.domainConfidence(), body.grain(), body.primaryKeys(), body.fieldTags(),
        body.ddl(), body.etlSql(), body.qualityRules(), body.specIssues(), body.status(), body.createdAt()));
  }

  @PutMapping("/projects/{projectId}/drafts-bundle")
  public void syncDrafts(@PathVariable String projectId, @RequestBody List<ApiModels.DraftDto> body) {
    tables.syncDrafts(projectId, body);
  }

  @GetMapping("/projects/{projectId}/tables/{tableId}/versions")
  public List<ApiModels.TableVersionDto> versions(@PathVariable String projectId, @PathVariable String tableId) {
    return versions.list(projectId, tableId);
  }

  @GetMapping("/projects/{projectId}/tables/{tableId}/versions/{versionId}")
  public ApiModels.TableVersionDto version(
      @PathVariable String projectId, @PathVariable String tableId, @PathVariable String versionId) {
    return versions.get(projectId, tableId, versionId);
  }

  @GetMapping("/projects/{projectId}/tables/{tableId}/versions/diff")
  public java.util.Map<String, Object> diff(
      @PathVariable String projectId,
      @PathVariable String tableId,
      @org.springframework.web.bind.annotation.RequestParam String from,
      @org.springframework.web.bind.annotation.RequestParam String to) {
    return versions.diff(projectId, tableId, from, to);
  }

  @PostMapping("/projects/{projectId}/tables/{tableId}/versions/{versionId}/restore")
  public ApiModels.TableDto restore(
      @PathVariable String projectId, @PathVariable String tableId, @PathVariable String versionId) {
    return versions.restore(projectId, tableId, versionId);
  }

  @PostMapping("/projects/{projectId}/layers/{layer}/ai/chat")
  public java.util.Map<String, Object> layerChat(
      @PathVariable String projectId, @PathVariable String layer, @RequestBody ApiModels.AiChatReq body) {
    return ai.layerChat(projectId, layer, body);
  }

  @PostMapping("/projects/{projectId}/layers/{layer}/ai/apply")
  public List<ApiModels.TableDto> layerApply(
      @PathVariable String projectId, @PathVariable String layer, @RequestBody ApiModels.AiApplyReq body) {
    return ai.apply(projectId, layer, body);
  }

  @PostMapping("/ai/chat")
  public java.util.Map<String, Object> aiChat(@RequestBody ApiModels.AiChatReq body) {
    return ai.chat(body);
  }

  @GetMapping("/knowledge/manuals")
  public java.util.List<java.util.Map<String, Object>> manuals(
      @org.springframework.web.bind.annotation.RequestParam(required = false) String engine) {
    return knowledge.manuals(engine);
  }
}
