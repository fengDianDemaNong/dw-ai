package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("tenant_ai_prompts")
public class TenantAiPromptEntity {
  private String tenantId;
  private String slot;
  private String body;
  private OffsetDateTime updatedAt;

  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getSlot() { return slot; }
  public void setSlot(String slot) { this.slot = slot; }
  public String getBody() { return body; }
  public void setBody(String body) { this.body = body; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
