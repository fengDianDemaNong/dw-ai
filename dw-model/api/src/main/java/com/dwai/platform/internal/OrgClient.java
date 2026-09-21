package com.dwai.platform.internal;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.dto.ApiModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OrgClient {
  private static final Logger log = LoggerFactory.getLogger(OrgClient.class);
  private final DwaiProperties props;

  public OrgClient(DwaiProperties props) {
    this.props = props;
  }

  public void heartbeat() {
    String org = blankToNull(props.getOrgBaseUrl());
    String self = blankToNull(props.serviceBaseUrl());
    if (org == null || self == null || !props.isMulti()) return;
    try {
      Map<String, String> body = new LinkedHashMap<>();
      body.put("product", "warehouse");
      body.put("version", "0.2.0");
      body.put("baseUrl", self);
      client(org).post()
          .uri("/internal/v1/registry/heartbeat")
          .contentType(MediaType.APPLICATION_JSON)
          .headers(h -> token(h))
          .body(body)
          .retrieve()
          .toBodilessEntity();
      log.info("已向组织平台登记 {}", self);
    } catch (Exception e) {
      log.warn("向组织平台登记失败: {}", e.getMessage());
    }
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> check(String userId, String tenantCode, String projectCode, String product, String action) {
    String org = blankToNull(props.getOrgBaseUrl());
    if (org == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置组织平台地址");
    }
    try {
      Map<String, Object> body = client(org).get()
          .uri(uri -> uri.path("/internal/v1/authz/check")
              .queryParam("userId", userId == null ? "" : userId)
              .queryParam("tenantCode", tenantCode == null ? "" : tenantCode)
              .queryParam("projectCode", projectCode == null ? "" : projectCode)
              .queryParam("product", product == null ? "warehouse" : product)
              .queryParam("action", action == null ? "model:read" : action)
              .build())
          .headers(this::token)
          .retrieve()
          .body(Map.class);
      return body == null ? Map.of("allow", false, "reason", "组织未返回鉴权结果") : body;
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "组织鉴权不可用: " + e.getMessage());
    }
  }

  public ApiModels.LoginRes refresh(String refreshToken) {
    String org = blankToNull(props.getOrgBaseUrl());
    if (org == null) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置组织平台地址");
    }
    if (refreshToken == null || refreshToken.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期");
    }
    try {
      ApiModels.LoginRes body = client(org).post()
          .uri("/api/auth/refresh")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("refreshToken", refreshToken))
          .retrieve()
          .onStatus(s -> s.value() == 401, (req, res) -> {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期");
          })
          .body(ApiModels.LoginRes.class);
      if (body == null || body.token() == null || body.token().isBlank()) {
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已过期");
      }
      return body;
    } catch (ResponseStatusException e) {
      throw e;
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "组织续期不可用: " + e.getMessage());
    }
  }

  public void revokeRefresh(String refreshToken) {
    String org = blankToNull(props.getOrgBaseUrl());
    if (org == null || refreshToken == null || refreshToken.isBlank()) return;
    try {
      client(org).post()
          .uri("/api/auth/logout")
          .contentType(MediaType.APPLICATION_JSON)
          .body(Map.of("refreshToken", refreshToken))
          .retrieve()
          .toBodilessEntity();
    } catch (Exception e) {
      log.warn("向组织平台注销 refresh 失败: {}", e.getMessage());
    }
  }

  private void token(org.springframework.http.HttpHeaders h) {
    String tok = props.getSecurity().getModuleToken();
    if (tok != null && !tok.isBlank()) h.set("X-Module-Token", tok);
  }

  private RestClient client(String base) {
    return RestClient.builder().baseUrl(base.replaceAll("/$", "")).build();
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }
}
