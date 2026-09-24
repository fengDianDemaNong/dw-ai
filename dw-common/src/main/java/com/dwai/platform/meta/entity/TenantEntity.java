package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("tenants")
public class TenantEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String code;
  private String name;
  private String owner;
  private String status;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getOwner() { return owner; }
  public void setOwner(String owner) { this.owner = owner; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
}
