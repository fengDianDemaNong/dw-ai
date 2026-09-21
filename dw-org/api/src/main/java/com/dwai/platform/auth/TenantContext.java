package com.dwai.platform.auth;

public final class TenantContext {
  private static final ThreadLocal<String> TENANT = new ThreadLocal<>();
  private static final ThreadLocal<String> PROJECT = new ThreadLocal<>();
  private static final ThreadLocal<String> USER = new ThreadLocal<>();
  private static final ThreadLocal<String> DISPLAY = new ThreadLocal<>();
  private static final ThreadLocal<Boolean> PLATFORM = new ThreadLocal<>();
  private static final ThreadLocal<String> TENANT_ROLE = new ThreadLocal<>();
  private static final ThreadLocal<String> TENANT_CODE = new ThreadLocal<>();
  private static final ThreadLocal<String> PROJECT_CODE = new ThreadLocal<>();

  private TenantContext() {}

  public static void set(
      String tenantId,
      String projectId,
      String user,
      String displayName,
      boolean platformAdmin,
      String tenantRole) {
    TENANT.set(tenantId);
    PROJECT.set(projectId);
    USER.set(user);
    DISPLAY.set(displayName);
    PLATFORM.set(platformAdmin);
    TENANT_ROLE.set(tenantRole);
  }

  public static String tenantId() { return TENANT.get(); }
  public static String projectId() { return PROJECT.get(); }
  public static String user() { return USER.get(); }
  public static String displayName() { return DISPLAY.get(); }
  public static boolean platformAdmin() { return Boolean.TRUE.equals(PLATFORM.get()); }
  public static String tenantRole() { return TENANT_ROLE.get(); }
  public static String tenantCode() { return TENANT_CODE.get(); }
  public static String projectCode() { return PROJECT_CODE.get(); }
  public static boolean tenantAdmin() {
    return platformAdmin() || "admin".equalsIgnoreCase(tenantRole());
  }

  public static void setCodes(String tenantCode, String projectCode) {
    TENANT_CODE.set(tenantCode);
    PROJECT_CODE.set(projectCode);
  }

  public static void clear() {
    TENANT.remove();
    PROJECT.remove();
    USER.remove();
    DISPLAY.remove();
    PLATFORM.remove();
    TENANT_ROLE.remove();
    TENANT_CODE.remove();
    PROJECT_CODE.remove();
  }
}
