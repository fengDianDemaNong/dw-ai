package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

@TableName(value = "tenant_grants", autoResultMap = true)
public class TenantGrantEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String tenantId;
  private String code;
  private String kind;
  private OffsetDateTime expiresAt;
  private OffsetDateTime revokedAt;
  private String createdBy;
  private OffsetDateTime createdAt;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String modules;
  @TableField(value = "project_ids", typeHandler = JsonbStringTypeHandler.class)
  private String projectIds;
  @TableField(value = "project_roles", typeHandler = JsonbStringTypeHandler.class)
  private String projectRoles;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }
  public OffsetDateTime getExpiresAt() { return expiresAt; }
  public void setExpiresAt(OffsetDateTime expiresAt) { this.expiresAt = expiresAt; }
  public OffsetDateTime getRevokedAt() { return revokedAt; }
  public void setRevokedAt(OffsetDateTime revokedAt) { this.revokedAt = revokedAt; }
  public String getCreatedBy() { return createdBy; }
  public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public String getModules() { return modules; }
  public void setModules(String modules) { this.modules = modules; }
  public String getProjectIds() { return projectIds; }
  public void setProjectIds(String projectIds) { this.projectIds = projectIds; }
  public String getProjectRoles() { return projectRoles; }
  public void setProjectRoles(String projectRoles) { this.projectRoles = projectRoles; }
}
