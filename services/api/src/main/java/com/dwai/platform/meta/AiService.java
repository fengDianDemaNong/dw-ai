package com.dwai.platform.meta;

import com.dwai.platform.auth.LlmCrypto;
import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.TenantLlmEntity;
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
  private static final ObjectMapper M = new ObjectMapper();
  private final AccessService access;
  private final TenantAdminService tenants;
  private final TableService tables;
  private final LlmCrypto crypto;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  public AiService(AccessService access, TenantAdminService tenants, TableService tables, LlmCrypto crypto) {
    this.access = access;
    this.tenants = tenants;
    this.tables = tables;
    this.crypto = crypto;
  }

  public Map<String, Object> chat(String message) {
    access.requireTenant();
    String text = proxyOrNull(message);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("source", text == null ? "builtin" : "llm");
    out.put("text", text);
    return out;
  }

  public Map<String, Object> layerChat(String projectId, String layer, ApiModels.AiChatReq req) {
    access.requireMember(projectId, "model:read");
    String msg = req == null ? "" : req.message();
    String text = proxyOrNull(msg);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("source", text == null ? "builtin" : "llm");
    out.put("text", text);
    out.put("layer", layer);
    return out;
  }

  public List<ApiModels.TableDto> apply(String projectId, String layer, ApiModels.AiApplyReq req) {
    access.requireMember(projectId, "model:write");
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

  private String proxyOrNull(String message) {
    String tid = TenantContext.tenantId();
    if (tid == null || message == null || message.isBlank()) return null;
    TenantLlmEntity e = tenants.llmEntity(tid);
    if (e == null || !Boolean.TRUE.equals(e.getEnabled()) || e.getApiKeyEnc() == null) return null;
    String key = crypto.decrypt(e.getApiKeyEnc());
    if (key == null || key.isBlank() || e.getBaseUrl() == null || e.getBaseUrl().isBlank() || e.getModel() == null) {
      return null;
    }
    try {
      String url = e.getBaseUrl().replaceAll("/$", "") + "/chat/completions";
      Map<String, Object> payload = Map.of(
          "model", e.getModel(),
          "messages", List.of(
              Map.of("role", "system", "content", "你是数仓建模助手，用中文简洁回答。"),
              Map.of("role", "user", "content", message)));
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
}
