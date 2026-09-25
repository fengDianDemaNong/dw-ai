package com.dwai.platform.internal;

import com.dwai.platform.DwaiProperties;
import com.dwai.platform.meta.dto.ApiModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
public class OrgClient {
  private static final Logger log = LoggerFactory.getLogger(OrgClient.class);

  /**
   * 连接/读取超时。
   *
   * <p>不设超时是危险的：{@code RestClient.builder()} 的默认 connect/read timeout 都是
   * 「无限等待」。组织进程如果「僵住」（长 GC、网络黑洞、端口仍 accept 但不响应），
   * 模块的每个写请求都会永久占用一个 Tomcat 线程，故障从 org 扩散成模块整体不可用 ——
   * 而模块本该是能独立部署的。
   *
   * <p>5 秒的读超时是刻意偏紧的：authz/check 是一次本地网络往返 + 一次数据库查询，
   * 超过这个量级说明组织侧已经异常，此时 fail-closed（503）比拖住调用方更安全。
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  private final DwaiProperties props;

  public OrgClient(DwaiProperties props) {
    this.props = props;
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

  /**
   * 拉某租户全部项目的成员（含显示名）。
   *
   * <p>失败时返回空列表而<b>不</b>抛错，与 {@link #check} 的 fail-closed 相反：那个决定
   * 「能不能进某个项目」，拿不到答案就必须拒绝；这个只决定「成员列表显示什么」，
   * 拉不到时调用方回落到「只显示自己」，页面照常可用 —— 让展示数据拖垮整个 session
   * 不划算。
   */
  public List<ApiModels.MemberDto> members(String tenantCode) {
    String org = blankToNull(props.getOrgBaseUrl());
    if (org == null || tenantCode == null || tenantCode.isBlank()) return List.of();
    try {
      ApiModels.MemberDto[] body = client(org).get()
          .uri(uri -> uri.path("/internal/v1/members")
              .queryParam("tenantCode", tenantCode)
              .build())
          .headers(this::token)
          .retrieve()
          .body(ApiModels.MemberDto[].class);
      return body == null ? List.of() : List.of(body);
    } catch (Exception e) {
      log.warn("拉取组织成员失败: {}", e.getMessage());
      return List.of();
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
          .uri("/internal/v1/auth/refresh")
          .contentType(MediaType.APPLICATION_JSON)
          .headers(this::token)
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
          .uri("/internal/v1/auth/logout")
          .contentType(MediaType.APPLICATION_JSON)
          .headers(this::token)
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
    return RestClient.builder()
        .requestFactory(ClientHttpRequestFactories.get(
            ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT)))
        .baseUrl(base.replaceAll("/$", ""))
        .build();
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }
}
