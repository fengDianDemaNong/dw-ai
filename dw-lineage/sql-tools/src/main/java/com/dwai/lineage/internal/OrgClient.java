package com.dwai.lineage.internal;

import com.dwai.lineage.conf.LineageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class OrgClient {
    private static final Logger log = LoggerFactory.getLogger(OrgClient.class);
    private final LineageProperties props;

    public OrgClient(LineageProperties props) {
        this.props = props;
    }

    public void heartbeat() {
        if (!props.isMulti()) return;
        String org = blankToNull(props.getOrgBaseUrl());
        String self = blankToNull(props.serviceBaseUrl());
        if (org == null || self == null) return;
        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("product", "metadata");
            body.put("version", "0.1.5");
            body.put("baseUrl", self);
            RestClient.builder().baseUrl(org.replaceAll("/$", "")).build()
                    .post()
                    .uri("/internal/v1/registry/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(h -> {
                        String tok = props.getModuleToken();
                        if (tok != null && !tok.isBlank()) h.set("X-Module-Token", tok);
                    })
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            log.info("已向组织平台登记 {}", self);
        } catch (Exception e) {
            log.warn("向组织平台登记失败: {}", e.getMessage());
        }
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
