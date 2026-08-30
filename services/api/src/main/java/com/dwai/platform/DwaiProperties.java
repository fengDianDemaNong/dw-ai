package com.dwai.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@ConfigurationProperties(prefix = "dwai")
public class DwaiProperties {
  /** multi | standard */
  private String deployMode = "multi";
  private String llmSecret = "dw-ai-llm-dev-secret-change-me-32b";
  private final Security security = new Security();
  private final Rules rules = new Rules();
  private final StarRocks starrocks = new StarRocks();
  private final DolphinScheduler dolphinscheduler = new DolphinScheduler();
  private final Web web = new Web();

  public String getDeployMode() { return deployMode; }
  public void setDeployMode(String deployMode) { this.deployMode = deployMode; }
  public boolean isMulti() { return !"standard".equalsIgnoreCase(deployMode); }
  public boolean isStandard() { return "standard".equalsIgnoreCase(deployMode); }
  public String implicitTenantId() { return "t-xinghe"; }
  public String getLlmSecret() { return llmSecret; }
  public void setLlmSecret(String llmSecret) { this.llmSecret = llmSecret; }
  public Security getSecurity() { return security; }
  public Rules getRules() { return rules; }
  public StarRocks getStarrocks() { return starrocks; }
  public DolphinScheduler getDolphinscheduler() { return dolphinscheduler; }
  public Web getWeb() { return web; }

  public static class Web {
    /** 安装包把控制台静态文件放在 libs/web，由此目录托管 SPA */
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
    private long jwtTtlSeconds = 86400;
    private boolean allowDevLogin = true;
    private final Casdoor casdoor = new Casdoor();

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public boolean isOidc() { return "oidc".equalsIgnoreCase(mode); }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public long getJwtTtlSeconds() { return jwtTtlSeconds; }
    public void setJwtTtlSeconds(long jwtTtlSeconds) { this.jwtTtlSeconds = jwtTtlSeconds; }
    public boolean isAllowDevLogin() { return allowDevLogin; }
    public void setAllowDevLogin(boolean allowDevLogin) { this.allowDevLogin = allowDevLogin; }
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
