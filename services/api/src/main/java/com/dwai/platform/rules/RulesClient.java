package com.dwai.platform.rules;

import com.dwai.platform.DwaiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RulesClient {
  private final RestClient http;

  public RulesClient(DwaiProperties props) {
    this.http = RestClient.builder().baseUrl(props.getRules().getBaseUrl()).build();
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> post(String path, Object body) {
    return http.post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(Map.class);
  }
}
