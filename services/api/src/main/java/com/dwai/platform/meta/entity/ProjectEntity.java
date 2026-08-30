package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDate;

@TableName("projects")
public class ProjectEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String tenantId;
  private String code;
  private String name;
  private String description;
  private String owner;
  private LocalDate createdAt;
  private String status;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getDescription() { return description; }
  public void setDescription(String description) { this.description = description; }
  public String getOwner() { return owner; }
  public void setOwner(String owner) { this.owner = owner; }
  public LocalDate getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDate createdAt) { this.createdAt = createdAt; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
}
