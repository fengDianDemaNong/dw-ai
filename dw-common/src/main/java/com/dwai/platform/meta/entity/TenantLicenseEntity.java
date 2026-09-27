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
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String aiCaps;
  /**
   * 租户侧的第二层控制：本组织启用哪些模块、每个模块给谁看（V27）。
   *
   * <p>{@link #modules} 是<b>平台</b>给这个租户开通的白名单（上限，只有平台管理员能改），
   * 这一列是租户在上限之内的启停与可见范围。两者是<b>两个独立的开关</b>，不是同一份 ——
   * 平台把某模块重新开通后，租户之前关掉的状态保留，平台不该悄悄替租户做决定。
   *
   * <p>{@code null} = 这个租户没配过，读侧回落默认值（全开 + 默认可见范围）。这是向后
   * 兼容的命门：升级前就存在的租户没有这一行，行为必须逐字不变。
   */
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String modulePolicies;

  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getModules() { return modules; }
  public void setModules(String modules) { this.modules = modules; }
  public String getAiCaps() { return aiCaps; }
  public void setAiCaps(String aiCaps) { this.aiCaps = aiCaps; }
  public String getModulePolicies() { return modulePolicies; }
  public void setModulePolicies(String modulePolicies) { this.modulePolicies = modulePolicies; }
}
