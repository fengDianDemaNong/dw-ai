package com.dwai.platform.meta.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

public final class Perms {
  private Perms() {}

  /** 仓建设：规范中心与建模中心。 */
  private static final Map<String, Set<String>> WAREHOUSE = Map.of(
      "admin", Set.of("spec:read", "spec:write", "model:read", "model:write", "model:publish", "iam:member"),
      "modeler", Set.of("spec:read", "model:read", "model:write"),
      "viewer", Set.of("spec:read", "model:read")
  );

  /**
   * 数据地图：目录与血缘。
   *
   * <p>`catalog:admin` 只管「数据目录」「临时表规则」两个管理页，<b>不蕴含</b> `catalog:read` ——
   * 管理页与读目录页是两件事。三档角色都把读的两项配齐了，所以实际效果上没有缺口。
   */
  private static final Map<String, Set<String>> METADATA = Map.of(
      "admin", Set.of("catalog:read", "lineage:read", "lineage:write", "catalog:admin"),
      "modeler", Set.of("catalog:read", "lineage:read", "lineage:write"),
      "viewer", Set.of("catalog:read", "lineage:read")
  );

  private static final Map<String, Map<String, Set<String>>> ROLE = Map.of(
      "warehouse", WAREHOUSE,
      "metadata", METADATA
  );

  /** 某产品下某角色是否具备该权限。产品、角色、权限任一未知都判否。 */
  public static boolean has(String product, String role, String perm) {
    if (product == null || role == null || perm == null) return false;
    return ROLE.getOrDefault(product, Map.of())
        .getOrDefault(role, Set.of())
        .contains(perm);
  }

  public static void require(String product, String role, String perm) {
    if (!has(product, role, perm)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权执行此操作");
    }
  }
}
