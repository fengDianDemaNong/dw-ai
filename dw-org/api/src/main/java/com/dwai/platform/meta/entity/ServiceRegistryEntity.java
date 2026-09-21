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
  private String baseUrl;
  private OffsetDateTime seenAt;

  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String getVersion() { return version; }
  public void setVersion(String version) { this.version = version; }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
  public OffsetDateTime getSeenAt() { return seenAt; }
  public void setSeenAt(OffsetDateTime seenAt) { this.seenAt = seenAt; }
}
