package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

@TableName("service_registry")
public class ServiceRegistryEntity {
  @TableId(type = IdType.INPUT)
  private String product;
  private String version;
  /** 已退役：模块后端地址，随心跳一起废弃（见 V16 迁移）。列保留，无写入源。 */
  private String baseUrl;
  /** 已退役：最后心跳时间。列保留，无写入源。 */
  private OffsetDateTime seenAt;
  /** 产品页面的前端地址，供门户 iframe 嵌入用。 */
  private String frontendUrl;

  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
  public OffsetDateTime getSeenAt() { return seenAt; }
  public void setSeenAt(OffsetDateTime seenAt) { this.seenAt = seenAt; }
  public String getFrontendUrl() { return frontendUrl; }
  public void setFrontendUrl(String frontendUrl) { this.frontendUrl = frontendUrl; }
}
