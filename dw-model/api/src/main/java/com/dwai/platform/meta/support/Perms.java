package com.dwai.platform.meta.support;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Set;

public final class Perms {
  private Perms() {}

  private static final Map<String, Set<String>> ROLE = Map.of(
      "admin", Set.of("spec:read", "spec:write", "model:read", "model:write", "model:publish", "iam:member"),
      "modeler", Set.of("spec:read", "model:read", "model:write"),
      "viewer", Set.of("spec:read", "model:read")
  );

  public static void require(String role, String perm) {
    if (role == null || !ROLE.getOrDefault(role, Set.of()).contains(perm)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色无权执行此操作");
    }
  }
}
