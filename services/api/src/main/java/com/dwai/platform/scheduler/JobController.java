package com.dwai.platform.scheduler;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
public class JobController {
  private final DolphinSchedulerClient ds;

  public JobController(DolphinSchedulerClient ds) {
    this.ds = ds;
  }

  public record PublishReq(String name, String etlSql, String table, String type) {}

  @PostMapping("/publish")
  public Map<String, Object> publish(@RequestBody PublishReq req) {
    String name = req.name() == null || req.name().isBlank()
        ? (req.table() == null ? "dwai_job" : req.table() + "_etl")
        : req.name();
    String sql = req.etlSql() == null ? "" : req.etlSql();
    Map<String, Object> dsResult = ds.createOrUpdateProcess(name, sql);
    return Map.of(
        "name", name,
        "type", req.type() == null ? "etl" : req.type(),
        "table", req.table() == null ? "" : req.table(),
        "dolphinScheduler", dsResult,
        "note", "质量规则执行与跨系统血缘为第二期，本接口只登记/下发 ETL 作业"
    );
  }
}
