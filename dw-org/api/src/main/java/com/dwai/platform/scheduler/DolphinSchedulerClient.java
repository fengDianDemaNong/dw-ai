package com.dwai.platform.scheduler;

import com.dwai.platform.DwaiProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class DolphinSchedulerClient {
  private final DwaiProperties props;
  private final RestClient http;

  public DolphinSchedulerClient(DwaiProperties props) {
    this.props = props;
    this.http = RestClient.builder().baseUrl(props.getDolphinscheduler().getBaseUrl()).build();
  }

  public Map<String, Object> createOrUpdateProcess(String name, String sql) {
    if (!props.getDolphinscheduler().isEnabled()) {
      return Map.of("skipped", true, "reason", "dolphinscheduler.disabled", "name", name);
    }
    long project = props.getDolphinscheduler().getProjectCode();
    return http.post()
        .uri("/projects/{code}/process-definition", project)
        .contentType(MediaType.APPLICATION_JSON)
        .header("token", props.getDolphinscheduler().getToken())
        .body(Map.of(
            "name", name,
            "description", "dw-ai published ETL",
            "tenantCode", "default",
            "locations", "[]",
            "taskDefinitionJson", "[{\"name\":\"" + name + "\",\"taskType\":\"SQL\",\"taskParams\":{\"sql\":" + quote(sql) + "}}]"
        ))
        .retrieve()
        .body(Map.class);
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
