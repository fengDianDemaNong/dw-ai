package com.dwai.platform.web;

import com.dwai.platform.DwaiProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class RuntimeController {
  private final DwaiProperties props;

  public RuntimeController(DwaiProperties props) {
    this.props = props;
  }

  @GetMapping({"/api/runtime", "/api/v1/runtime"})
  public Map<String, Object> runtime() {
    return Map.of(
        "product", props.product(),
        "version", "0.1.5",
        "runMode", props.runMode());
  }

  @GetMapping({"/api/v1/manifest", "/api/manifest"})
  public Map<String, Object> manifest() {
    return Map.of(
        "product", props.product(),
        "version", "0.1.5",
        "runMode", props.runMode(),
        "roles", List.of(
            Map.of("code", "admin", "label", "项目管理员"),
            Map.of("code", "modeler", "label", "建模工程师"),
            Map.of("code", "viewer", "label", "只读")),
        "menus", List.of(
            Map.of("id", "warehouse.app.home", "path", "/app", "perm", ""),
            Map.of("id", "warehouse.spec.domains", "path", "/app/spec/domains", "perm", "spec:read"),
            Map.of("id", "warehouse.model.overview", "path", "/app/model/:layer", "perm", "model:read")));
  }
}
