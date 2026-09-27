package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

/**
 * 租户自己的计算资源登记（工作台「计算资源」页）。
 *
 * <p>与 {@link TenantLlmEntity} 同一套路：连接信息按租户存一份，Token 加密落库
 * （{@code scheduler_token_enc}），对外只回 {@code hasToken} 布尔，明文永不外发。
 *
 * <p>这里登记的是<b>租户的基础设施</b>（DS 集群、数仓引擎），与服务注册表
 * （{@code service_registry}，登记平台自己的产品进程）是两回事，别混。
 *
 * <p>{@code engines} 只存 {@code [{kind, enabled}]} —— 引擎的连接信息本版不做，
 * 状态是<b>派生</b>的（{@code enabled ? 'ok' : 'unconfigured'}），不落库，
 * 免得同一件事有两个真相。
 */
@TableName(value = "tenant_compute", autoResultMap = true)
public class TenantComputeEntity {
  @TableId(type = IdType.INPUT)
  private String tenantId;
  private Boolean schedulerEnabled;
  private String schedulerBaseUrl;
  private byte[] schedulerTokenEnc;
  private String schedulerStatus;
  private OffsetDateTime schedulerTestedAt;
  /** 「测试连接」的结论原文（成功也写一句，含实际请求地址）。 */
  private String schedulerNote;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String engines;
  private OffsetDateTime updatedAt;

  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public Boolean getSchedulerEnabled() { return schedulerEnabled; }
  public void setSchedulerEnabled(Boolean schedulerEnabled) { this.schedulerEnabled = schedulerEnabled; }
  public String getSchedulerBaseUrl() { return schedulerBaseUrl; }
  public void setSchedulerBaseUrl(String schedulerBaseUrl) { this.schedulerBaseUrl = schedulerBaseUrl; }
  public byte[] getSchedulerTokenEnc() { return schedulerTokenEnc; }
  public void setSchedulerTokenEnc(byte[] schedulerTokenEnc) { this.schedulerTokenEnc = schedulerTokenEnc; }
  public String getSchedulerStatus() { return schedulerStatus; }
  public void setSchedulerStatus(String schedulerStatus) { this.schedulerStatus = schedulerStatus; }
  public OffsetDateTime getSchedulerTestedAt() { return schedulerTestedAt; }
  public void setSchedulerTestedAt(OffsetDateTime schedulerTestedAt) { this.schedulerTestedAt = schedulerTestedAt; }
  public String getSchedulerNote() { return schedulerNote; }
  public void setSchedulerNote(String schedulerNote) { this.schedulerNote = schedulerNote; }
  public String getEngines() { return engines; }
  public void setEngines(String engines) { this.engines = engines; }
  public OffsetDateTime getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(OffsetDateTime updatedAt) { this.updatedAt = updatedAt; }
}
