package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

@TableName(value = "modeling_drafts", autoResultMap = true)
public class ModelingDraftEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String projectId;
  private String sourceTableId;
  private String targetLayer;
  private String domainCode;
  private java.math.BigDecimal domainConfidence;
  private String grain;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String primaryKeys;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String fieldTags;
  private String ddl;
  private String etlSql;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String qualityRules;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String specIssues;
  private String status;
  private OffsetDateTime createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getSourceTableId() { return sourceTableId; }
  public void setSourceTableId(String sourceTableId) { this.sourceTableId = sourceTableId; }
  public String getTargetLayer() { return targetLayer; }
  public void setTargetLayer(String targetLayer) { this.targetLayer = targetLayer; }
  public String getDomainCode() { return domainCode; }
  public void setDomainCode(String domainCode) { this.domainCode = domainCode; }
  public java.math.BigDecimal getDomainConfidence() { return domainConfidence; }
  public void setDomainConfidence(java.math.BigDecimal domainConfidence) { this.domainConfidence = domainConfidence; }
  public String getGrain() { return grain; }
  public void setGrain(String grain) { this.grain = grain; }
  public String getPrimaryKeys() { return primaryKeys; }
  public void setPrimaryKeys(String primaryKeys) { this.primaryKeys = primaryKeys; }
  public String getFieldTags() { return fieldTags; }
  public void setFieldTags(String fieldTags) { this.fieldTags = fieldTags; }
  public String getDdl() { return ddl; }
  public void setDdl(String ddl) { this.ddl = ddl; }
  public String getEtlSql() { return etlSql; }
  public void setEtlSql(String etlSql) { this.etlSql = etlSql; }
  public String getQualityRules() { return qualityRules; }
  public void setQualityRules(String qualityRules) { this.qualityRules = qualityRules; }
  public String getSpecIssues() { return specIssues; }
  public void setSpecIssues(String specIssues) { this.specIssues = specIssues; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
