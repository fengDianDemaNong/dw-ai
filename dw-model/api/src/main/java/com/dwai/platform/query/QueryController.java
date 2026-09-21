package com.dwai.platform.query;

import com.dwai.platform.auth.TenantContext;
import com.dwai.platform.rules.RulesClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/query", "/api/v1/query"})
public class QueryController {
  private final RulesClient rules;
  private final StarRocksExecutor starRocks;

  public QueryController(RulesClient rules, StarRocksExecutor starRocks) {
    this.rules = rules;
    this.starRocks = starRocks;
  }

  @PostMapping("/preview")
  public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
    Map<String, Object> access = rules.post("/rules/access", body.getOrDefault("access", Map.of()));
    String decision = String.valueOf(access.getOrDefault("decision", "forbid"));
    Map<String, Object> routed = rules.post("/rules/sql", body.getOrDefault("sql", Map.of()));
    String sql = String.valueOf(routed.getOrDefault("sql", ""));
    List<Map<String, Object>> rows = List.of();
    if ("allow".equals(decision)) {
      rows = starRocks.query(sql);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("decision", decision);
    out.put("reasons", access.get("reasons"));
    out.put("sql", sql);
    out.put("hit", routed.get("hit"));
    out.put("rows", "forbid".equals(decision) ? List.of() : rows);
    out.put("user", TenantContext.user());
    out.put("starrocksEnabled", rows != null);
    return out;
  }
}
