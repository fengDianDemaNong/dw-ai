package com.dwai.lineage.internal;

import com.dwai.lineage.conf.LineageProperties;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Map;

@Component
public class OrgClient {
    /**
     * 超时必须设。没有它，组织平台「连得上但不回」时（不是拒绝，是卡住）：
     * {@link #check} 的调用方是写接口，Tomcat 线程会一直挂着等，组织一慢就变成
     * 数据地图写不进去。
     *
     * <p>取值与 dw-model 的 {@code OrgClient} 保持一致：连接 2 秒、读取 5 秒。
     * 注意这是给 {@code authz/check} 这类**门禁**调用用的紧超时，
     * 项目懒加载（pull）走的是另一组更短的超时。
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final LineageProperties props;

    public OrgClient(LineageProperties props) {
        this.props = props;
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

    /**
     * 向组织平台问一次「这个人能不能在这个项目里做这个动作」。
     *
     * <p>失败语义是<b>门禁</b>：拿不到答案必须拒绝，绝不能「组织不可达就放行」。
     * 所以 org 地址没配、组织连不上、返回体为空，一律抛 503 / 记为不放行，
     * 而不是吞掉异常继续。
     *
     * <p>形态与 dw-model 的 {@code OrgClient.check} 一致：GET + query 参数 +
     * {@code X-Module-Token} 头。org 侧实现在
     * {@code InternalController#authz}（{@code /internal/v1/authz/check}）。
     *
     * @param userId     组织侧的用户标识（multi 下就是令牌的 {@code sub}）
     * @param tenantCode 租户编码（不是 id —— 组织按 code 认）
     * @param projectCode 项目编码（同上）
     * @param product    产品码，数据地图是 {@code metadata}
     * @param action     权限词，如 {@code catalog:admin}
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> check(
            String userId, String tenantCode, String projectCode, String product, String action) {
        String org = blankToNull(props.getOrgBaseUrl());
        if (org == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "未配置组织平台地址");
        }
        try {
            Map<String, Object> body = client(org)
                    .get()
                    .uri(uri -> uri.path("/internal/v1/authz/check")
                            .queryParam("userId", blankToEmpty(userId))
                            .queryParam("tenantCode", blankToEmpty(tenantCode))
                            .queryParam("projectCode", blankToEmpty(projectCode))
                            .queryParam("product", blankToEmpty(product))
                            .queryParam("action", blankToEmpty(action))
                            .build())
                    .headers(h -> {
                        String tok = props.getModuleToken();
                        if (tok != null && !tok.isBlank()) h.set("X-Module-Token", tok);
                    })
                    .retrieve()
                    .body(Map.class);
            return body == null ? Map.of("allow", false, "reason", "组织未返回鉴权结果") : body;
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "组织鉴权不可用: " + e.getMessage());
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String blankToEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
