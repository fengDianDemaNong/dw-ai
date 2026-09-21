package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("platform_access")
public class PlatformAccessEntity {
  private String userId;
  private String tenantId;
  private String grantId;

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getGrantId() { return grantId; }
  public void setGrantId(String grantId) { this.grantId = grantId; }
}
