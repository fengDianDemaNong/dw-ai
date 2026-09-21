package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("users")
public class UserEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String tenantId;
  private String username;
  private String displayName;
  private String passwordHash;
  private String status;
  private String casdoorId;
  private Boolean platformAdmin;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getUsername() { return username; }
  public void setUsername(String username) { this.username = username; }
  public String getDisplayName() { return displayName; }
  public void setDisplayName(String displayName) { this.displayName = displayName; }
  public String getPasswordHash() { return passwordHash; }
  public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getCasdoorId() { return casdoorId; }
  public void setCasdoorId(String casdoorId) { this.casdoorId = casdoorId; }
  public Boolean getPlatformAdmin() { return platformAdmin; }
  public void setPlatformAdmin(Boolean platformAdmin) { this.platformAdmin = platformAdmin; }
}
