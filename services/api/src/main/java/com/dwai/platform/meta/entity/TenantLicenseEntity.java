package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

@TableName(value = "tenant_licenses", autoResultMap = true)
public class TenantLicenseEntity {
  @TableId(type = IdType.INPUT)
  private String tenantId;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String modules;

  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getModules() { return modules; }
  public void setModules(String modules) { this.modules = modules; }
}
