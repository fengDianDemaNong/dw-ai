package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("tenant_llm")
public class TenantLlmEntity {
  @TableId(type = IdType.INPUT)
  private String tenantId;
  private Boolean enabled;
  private String provider;
  private String baseUrl;
  private String model;
  private byte[] apiKeyEnc;
  private OffsetDateTime updatedAt;

  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public String getProvider() { return provider; }
  public void setProvider(String provider) { this.provider = provider; }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
  public String getModel() { return model; }
  public void setModel(String model) { this.model = model; }
  public byte[] getApiKeyEnc() { return apiKeyEnc; }
  public void setApiKeyEnc(byte[] apiKeyEnc) { this.apiKeyEnc = apiKeyEnc; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
