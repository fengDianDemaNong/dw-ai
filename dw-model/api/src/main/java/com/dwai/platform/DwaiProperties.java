package com.dwai.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "dwai")
public class DwaiProperties {
  /** 兼容旧配置：multi | standard。新代码读 runMode()。 */
  private String deployMode = "multi";
  /** standalone | standard | multi。空则回落到 deployMode。 */
  private String runMode = "";
  /** suite | org | warehouse。suite 为过渡期单进程全开。 */
  private String product = "suite";
  /**
   * 本进程判权用的<b>产品码</b>（warehouse | metadata），权限表 {@code Perms} 的第一维。
   *
   * <p>与 {@link #product()} <b>不是一回事</b>，别互相顶替：那个是「进程角色」（这个进程
   * 开了哪些功能），这个是「这套权限词属于哪个产品」。两者会分叉 —— suite 角色下
   * {@code product()} 返回 {@code "suite"}，而 {@code "suite"} 在权限表里查不到任何东西，
   * 拿它去判权会让全部请求静默变 403。
   */
  private String productCode = "warehouse";
  /** 仓建设 / 血缘 multi 时组织平台基址。 */
  private String orgBaseUrl = "";
  /** 本进程给别人登记用的浏览器可访问根地址。 */
  private String publicBaseUrl = "";
  /** 本进程给其它服务调用的 API 根地址。空则回落 publicBaseUrl。 */
  private String serviceBaseUrl = "";
  private String llmSecret = "dw-ai-llm-dev-secret-change-me-32b";
  private final Security security = new Security();
  private final Rules rules = new Rules();
  private final StarRocks starrocks = new StarRocks();
  private final DolphinScheduler dolphinscheduler = new DolphinScheduler();
  private final Web web = new Web();
  private final Bootstrap bootstrap = new Bootstrap();

  public String getDeployMode() { return deployMode; }
  public void setDeployMode(String deployMode) { this.deployMode = deployMode; }
  public String getRunMode() { return runMode; }
  public void setRunMode(String runMode) { this.runMode = runMode; }

  public String runMode() {
    String raw = runMode != null && !runMode.isBlank() ? runMode : deployMode;
    if (raw == null) return "multi";
    String m = raw.trim().toLowerCase();
    if ("standalone".equals(m) || "standard".equals(m) || "multi".equals(m)) return m;
    return "multi";
  }

  public boolean isStandalone() { return "standalone".equals(runMode()); }
  public boolean isMulti() { return "multi".equals(runMode()); }
  public boolean isStandard() { return "standard".equals(runMode()); }
  public boolean needsAuth() { return !isStandalone(); }
  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String product() {
    if (product == null || product.isBlank()) return "suite";
    String p = product.trim().toLowerCase();
    if ("org".equals(p) || "warehouse".equals(p) || "suite".equals(p)) return p;
    return "suite";
  }
  public boolean isOrgProcess() { return "org".equals(product()) || "suite".equals(product()); }
  public boolean isWarehouseProcess() { return "warehouse".equals(product()) || "suite".equals(product()); }
  public boolean isOrgOnly() { return "org".equals(product()); }
  public boolean isWarehouseOnly() { return "warehouse".equals(product()); }
  public String getProductCode() { return productCode; }
  public void setProductCode(String productCode) { this.productCode = productCode; }

  /**
   * 归一化后的产品码。空值回落 {@code warehouse}。
   *
   * <p>不像 {@link #product()} 那样做白名单：配了个权限表里没有的值，后果是全部判权 403
   * （fail-closed，看得见），而不是被静默改写成另一个产品（fail-open，看不见）。
   */
  public String productCode() {
    if (productCode == null || productCode.isBlank()) return "warehouse";
    return productCode.trim().toLowerCase();
  }

  public String getOrgBaseUrl() { return orgBaseUrl; }
  public void setOrgBaseUrl(String orgBaseUrl) { this.orgBaseUrl = orgBaseUrl; }
  public String getPublicBaseUrl() { return publicBaseUrl; }
  public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
  public String getServiceBaseUrl() { return serviceBaseUrl; }
  public void setServiceBaseUrl(String serviceBaseUrl) { this.serviceBaseUrl = serviceBaseUrl; }
  public String serviceBaseUrl() {
    if (serviceBaseUrl != null && !serviceBaseUrl.isBlank()) return serviceBaseUrl.trim();
    return publicBaseUrl == null ? "" : publicBaseUrl.trim();
  }
  public String implicitTenantId() { return "t-xinghe"; }
  public String getLlmSecret() { return llmSecret; }
  public void setLlmSecret(String llmSecret) { this.llmSecret = llmSecret; }
  public Security getSecurity() { return security; }
  public Rules getRules() { return rules; }
  public StarRocks getStarrocks() { return starrocks; }
  public DolphinScheduler getDolphinscheduler() { return dolphinscheduler; }
  public Web getWeb() { return web; }
  public Bootstrap getBootstrap() { return bootstrap; }

  /** 空库首次启动时补一个平台用户，与演示 seed 无关。 */
  public static class Bootstrap {
    private String adminUsername = "admin";
    /**
     * standard（普通模式）默认管理员密码。
     *
     * <p>这里写的默认值只在 {@code application.yml} 的 {@code bootstrap.admin-password}
     * 缺失时才生效，而那一行是 {@code ${BOOTSTRAP_ADMIN_PASSWORD:123456}} ——
     * 所以<b>实际默认是 123456</b>。两处保持一致，别让读代码的人以为默认是 admin123；
     * 要换密码得显式设 {@code BOOTSTRAP_ADMIN_PASSWORD}。
     *
     * <p>取值与数据地图（dw-lineage）standard 模式的本地账号对齐 —— 那边也是
     * {@code admin / 123456}，两个模块的「普通模式默认管理员」用同一组，只记一套。
     *
     * <p>只对 standard 生效：{@code BootstrapAdminRunner} 在 standalone 与 multi 下
     * 直接跳过（前者免登录、后者账号由组织平台 fan-out）。
     */
    private String adminPassword = "123456";
    private String adminDisplayName = "平台管理员";

    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
    public String getAdminDisplayName() { return adminDisplayName; }
    public void setAdminDisplayName(String adminDisplayName) { this.adminDisplayName = adminDisplayName; }
  }

  public static class Web {
    /** 安装包把控制台静态文件放在 web/，由此目录托管 SPA */
    private String staticDir = "";
    private String corsOrigins = "http://127.0.0.1:5173,http://localhost:5173,http://localhost,http://127.0.0.1";

    public String getStaticDir() { return staticDir; }
    public void setStaticDir(String staticDir) { this.staticDir = staticDir; }
    public String getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(String corsOrigins) { this.corsOrigins = corsOrigins; }
  }

  public static class Security {
    /** dev | oidc */
    private String mode = "dev";
    private String jwtSecret = "dw-ai-dev-secret-change-me-please-32b";
    private long jwtTtlSeconds = 900;
    private long refreshTtlSeconds = 900;
    /** 无操作超过此时长必须重新登录。续期只在未空闲时发生。 */
    private long idleTtlSeconds = 900;
    private boolean allowDevLogin = true;
    /** 服务间令牌；空则内部接口只认已登录用户（同进程过渡）。 */
    private String moduleToken = "";
    private final Casdoor casdoor = new Casdoor();

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isOidc() { return "oidc".equalsIgnoreCase(mode); }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public long getJwtTtlSeconds() { return jwtTtlSeconds; }
    public void setJwtTtlSeconds(long jwtTtlSeconds) { this.jwtTtlSeconds = jwtTtlSeconds; }
    public long getRefreshTtlSeconds() { return refreshTtlSeconds; }
    public void setRefreshTtlSeconds(long refreshTtlSeconds) { this.refreshTtlSeconds = refreshTtlSeconds; }
    public long getIdleTtlSeconds() { return idleTtlSeconds; }
    public void setIdleTtlSeconds(long idleTtlSeconds) { this.idleTtlSeconds = idleTtlSeconds; }
    public boolean isAllowDevLogin() { return allowDevLogin; }
    public void setAllowDevLogin(boolean allowDevLogin) { this.allowDevLogin = allowDevLogin; }
    public String getModuleToken() { return moduleToken; }
    public void setModuleToken(String moduleToken) { this.moduleToken = moduleToken; }
    public Casdoor getCasdoor() { return casdoor; }
  }

  public static class Casdoor {
    private String issuer = "";
    private String jwkSetUri = "";
    private String audience = "";
    private String orgTenantMap = "";

    public String getIssuer() { return issuer; }
    public void setIssuer(String issuer) { this.issuer = issuer; }
    public String getJwkSetUri() { return jwkSetUri; }
    public void setJwkSetUri(String jwkSetUri) { this.jwkSetUri = jwkSetUri; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getOrgTenantMap() { return orgTenantMap; }
    public void setOrgTenantMap(String orgTenantMap) { this.orgTenantMap = orgTenantMap; }

    public boolean configured() {
      return issuer != null && !issuer.isBlank();
    }

    public String resolvedJwkSetUri() {
      if (jwkSetUri != null && !jwkSetUri.isBlank()) return jwkSetUri;
      if (!configured()) return "";
      String base = issuer.replaceAll("/$", "");
      return base + "/.well-known/jwks";
    }

    /** Casdoor 组织名（owner）→ 租户 id */
    public Map<String, String> orgToTenant() {
      Map<String, String> map = new LinkedHashMap<>();
      if (orgTenantMap == null || orgTenantMap.isBlank()) return map;
      for (String part : orgTenantMap.split(",")) {
        String[] kv = part.trim().split(":", 2);
        if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
          map.put(kv[0].trim(), kv[1].trim());
        }
      }
      return map;
    }
  }

  public static class Rules {
    private String baseUrl = "http://127.0.0.1:7080";
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
  }

  public static class StarRocks {
    private boolean enabled = false;
    private String url = "jdbc:mysql://127.0.0.1:9030/dwai";
    private String username = "root";
    private String password = "";
    private int maxRows = 100;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
  }

  public static class DolphinScheduler {
    private boolean enabled = false;
    private String baseUrl = "http://127.0.0.1:12345/dolphinscheduler";
    private String token = "";
    private long projectCode = 0;
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public long getProjectCode() { return projectCode; }
    public void setProjectCode(long projectCode) { this.projectCode = projectCode; }
  }
}
