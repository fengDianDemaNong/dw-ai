package com.dwai.platform.auth;

public final class TenantContext {
  /**
   * standalone 下代表「这个本地部署」的固定主体 id。
   *
   * <p>独立模式没有登录，但**必须有一个真实存在的主体**：{@code TenantFilter} 把它写进
   * 上下文，`ProjectService` 建项目 / 加成员时会把它落进 {@code project_members.user_id}，
   * 而那一列有外键指向 {@code users(id)}。所以 {@code WarehouseLocalSeedRunner} 会在
   * standalone 下往 {@code users} 种一行同 id 的用户 —— 两处必须用同一个字面量，
   * 散着写就会出现「上下文里叫 A、库里叫 B」的外键违约 500。
   */
  public static final String STANDALONE_USER_ID = "standalone";

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
