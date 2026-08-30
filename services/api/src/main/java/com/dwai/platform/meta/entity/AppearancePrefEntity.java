package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("appearance_prefs")
public class AppearancePrefEntity {
  private String scope;
  private String tenantId;
  private String theme;
  private String menuPos;

  public String getScope() { return scope; }
  public void setScope(String scope) { this.scope = scope; }
  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getTheme() { return theme; }
  public void setTheme(String theme) { this.theme = theme; }
  public String getMenuPos() { return menuPos; }
  public void setMenuPos(String menuPos) { this.menuPos = menuPos; }
}
