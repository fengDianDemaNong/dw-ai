package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("user_tenants")
public class UserTenantEntity {
  private String userId;
  private String tenantId;
  private String tenantRole;

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getTenantRole() { return tenantRole; }
  public void setTenantRole(String tenantRole) { this.tenantRole = tenantRole; }
}
