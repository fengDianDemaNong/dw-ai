package com.dwai.platform.meta;

import com.dwai.platform.auth.LlmCrypto;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.ProjectEntity;
import com.dwai.platform.meta.entity.TenantLlmEntity;
import com.dwai.platform.meta.support.AiCaps;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiService {
  private static final int SUMMARY_LIMIT = 2400;
  private static final ObjectMapper M = new ObjectMapper();
  private final AccessService access;
  private final TenantAdminService tenants;
  private final TableService tables;
  private final SpecService spec;
  private final AiPromptService prompts;
  private final LlmCrypto crypto;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public AiService(
      AccessService access,
      TenantAdminService tenants,
      TableService tables,
      SpecService spec,
      AiPromptService prompts,
      LlmCrypto crypto) {
    this.access = access;
    this.tenants = tenants;
    this.tables = tables;
    this.spec = spec;
    this.prompts = prompts;
    this.crypto = crypto;
  }

  public Map<String, Object> chat(ApiModels.AiChatReq req) {
    access.requireTenant();
    String slot = req == null || blank(req.slot()) ? AiCaps.SLOT_SPEC : req.slot();
    String cap = AiCaps.capForSlot(slot);
    if (cap == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "未知提示词槽位");
    if (AiCaps.SLOT_MODEL.equals(slot)) {
      access.requireMember(req == null ? null : req.projectId(), "model:read");
    } else {
      if (req == null || blank(req.projectId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "规范对话须带 projectId");
      }
      access.requireMember(req.projectId(), "spec:read");
    }
    access.requireAiCap(cap);
    return complete(slot, req == null ? null : req.projectId(), req, null, null);
  }

  public Map<String, Object> layerChat(String projectId, String layer, ApiModels.AiChatReq req) {
    access.requireMember(projectId, "model:read");
    access.requireAiCap(AiCaps.MODEL_DESIGN);
    String slot = req != null && !blank(req.slot()) ? req.slot() : AiCaps.SLOT_MODEL;
    if (!AiCaps.SLOT_MODEL.equals(slot)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "建模对话须使用 model.system");
    }
    return complete(slot, projectId, req, layer, req == null ? null : req.tableId());
  }

  public List<ApiModels.TableDto> apply(String projectId, String layer, ApiModels.AiApplyReq req) {
    access.requireMember(projectId, "model:write");
    access.requireAiCap(AiCaps.MODEL_DESIGN);
    if (req == null || req.tables() == null || req.tables().isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请勾选要写入的表");
    }
    List<ApiModels.TableDto> out = new ArrayList<>();
    for (ApiModels.TableDraft d : req.tables()) {
      boolean update = "update".equalsIgnoreCase(d.mode()) && d.tableId() != null && !d.tableId().isBlank();
      ApiModels.TableDto body = new ApiModels.TableDto(
          update ? d.tableId() : null,
          projectId,
          d.layer() == null ? layer : d.layer(),
          d.name(),
          d.comment(),
          d.domain(),
          null,
          d.grain(),
          d.period(),
          d.columns(),
          d.partition(),
          null,
          update ? null : "draft",
          null,
          d.grade());
      if (update) {
        ApiModels.TableDto cur = tables.getTable(projectId, d.tableId());
        body = new ApiModels.TableDto(
            d.tableId(), projectId, cur.layer(), d.name(), d.comment(), d.domain(),
            cur.sourceSystem(), d.grain(), d.period(), d.columns(), d.partition(),
            cur.storedAs(), cur.status(), cur.createdFrom(), d.grade());
        out.add(tables.saveTable(projectId, body, "AI 采纳"));
      } else {
        out.add(tables.saveTable(projectId, body, "AI 新建"));
      }
    }
    return out;
  }

  private Map<String, Object> complete(
      String slot, String projectId, ApiModels.AiChatReq req, String layer, String tableId) {
    String message = req == null ? "" : nz(req.message());
    TenantLlmEntity llm = tenants.llmEntity(TenantContext.tenantId());
    boolean ready = llm != null
        && Boolean.TRUE.equals(llm.getEnabled())
        && llm.getApiKeyEnc() != null
        && !blank(llm.getBaseUrl())
        && !blank(llm.getModel());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("slot", slot);
    if (projectId != null) out.put("projectId", projectId);
    if (!ready) {
      out.put("source", "builtin");
      out.put("text", null);
      return out;
    }
    String system = replacePlaceholders(prompts.effective(TenantContext.tenantId(), slot), projectId, layer, tableId);
    try {
      String text = proxy(system, message, req == null ? null : req.history(), llm);
      out.put("source", text == null ? "builtin" : "llm");
      out.put("text", text);
      return out;
    } catch (Exception ex) {
      out.put("source", "builtin");
      out.put("fallback", true);
      out.put("error", ex instanceof ResponseStatusException rse && rse.getReason() != null
          ? rse.getReason()
          : "大模型调用失败");
      out.put("text", null);
      return out;
    }
  }

  private String replacePlaceholders(String body, String projectId, String layer, String tableId) {
    String text = body == null ? "" : body;
    ProjectSummary s = projectSummary(projectId, layer, tableId);
    return text
        .replace("{{project}}", s.project)
        .replace("{{domains}}", s.domains)
        .replace("{{layers}}", s.layers)
        .replace("{{roots}}", s.roots)
        .replace("{{grades}}", s.grades)
        .replace("{{layer}}", s.layer)
        .replace("{{focus_table}}", s.focusTable);
  }

  private ProjectSummary projectSummary(String projectId, String layer, String tableId) {
    if (blank(projectId)) {
      return new ProjectSummary("未选项目", "无", "无", "无", "无", nz(layer), "无");
    }
    ProjectEntity p = access.requireProject(projectId);
    String project = (p.getName() == null ? "" : p.getName()) + (blank(p.getCode()) ? "" : " / " + p.getCode());
    String domains = join(spec.listDomains(projectId).stream()
        .map(d -> d.code() + " " + nz(d.name())).toList());
    String layers = join(spec.listLayers(projectId).stream()
        .map(l -> l.layer() + " " + nz(l.naming())).toList());
    String roots = join(spec.listRoots(projectId).stream()
        .map(r -> r.code() + " " + nz(r.zh())).toList());
    String grades = join(spec.listGrades(projectId).stream()
        .map(g -> g.code() + " L" + g.level() + " " + nz(g.name())).toList());
    String focus = "无";
    if (!blank(tableId)) {
      try {
        ApiModels.TableDto t = tables.getTable(projectId, tableId);
        String cols = t.columns() == null ? "" : join(t.columns().stream()
            .map(c -> c.name() + " " + nz(c.type())).toList());
        focus = t.name() + (blank(t.comment()) ? "" : " " + t.comment()) + (cols.isBlank() ? "" : " 字段：" + cols);
      } catch (Exception ignored) {
        focus = tableId;
      }
    }
    return new ProjectSummary(clip(project), clip(domains), clip(layers), clip(roots), clip(grades), nz(layer), clip(focus));
  }

  private String proxy(String system, String message, List<Map<String, Object>> history, TenantLlmEntity e) {
    String key = crypto.decrypt(e.getApiKeyEnc());
    if (key == null || key.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "大模型密钥无效");
    }
    try {
      String url = e.getBaseUrl().replaceAll("/$", "") + "/chat/completions";
      List<Map<String, String>> messages = new ArrayList<>();
      messages.add(Map.of("role", "system", "content", system));
      if (history != null) {
        for (Map<String, Object> h : history) {
          if (h == null) continue;
          String role = String.valueOf(h.getOrDefault("role", "user"));
          String content = String.valueOf(h.getOrDefault("content", h.getOrDefault("text", "")));
          if (!content.isBlank() && ("user".equals(role) || "assistant".equals(role))) {
            messages.add(Map.of("role", role, "content", content));
          }
        }
      }
      if (!blank(message)) messages.add(Map.of("role", "user", "content", message));
      Map<String, Object> payload = Map.of("model", e.getModel(), "messages", messages);
      HttpRequest req = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(40))
          .header("Content-Type", "application/json")
          .header("Authorization", "Bearer " + key)
          .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(payload), StandardCharsets.UTF_8))
          .build();
      HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (res.statusCode() >= 400) {
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "大模型调用失败");
      }
      JsonNode root = M.readTree(res.body());
      JsonNode content = root.path("choices").path(0).path("message").path("content");
      return content.isMissingNode() ? null : content.asText();
    } catch (ResponseStatusException e2) {
      throw e2;
    } catch (Exception ex) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "大模型调用失败");
    }
  }

  private static String join(List<String> parts) {
    if (parts == null || parts.isEmpty()) return "无";
    return String.join("；", parts);
  }

  private static String clip(String s) {
    if (s == null || s.isBlank()) return "无";
    return s.length() > SUMMARY_LIMIT ? s.substring(0, SUMMARY_LIMIT) + "…" : s;
  }

  private static boolean blank(String s) { return s == null || s.isBlank(); }
  private static String nz(String s) { return s == null ? "" : s; }

  private record ProjectSummary(
      String project, String domains, String layers, String roots, String grades, String layer, String focusTable) {}
}
