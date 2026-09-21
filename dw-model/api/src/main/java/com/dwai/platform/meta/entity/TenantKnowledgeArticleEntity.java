package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

@TableName(value = "tenant_knowledge_articles", autoResultMap = true)
public class TenantKnowledgeArticleEntity {
  private String tenantId;
  private String engine;
  @TableField("article_id")
  private String articleId;
  private String title;
  private String summary;
  private String body;
  private String sourceUrl;
  private String sourceLabel;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String sections;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String notes;
  private OffsetDateTime importedAt;
  private String importedBy;

  public String getTenantId() { return tenantId; }
  public void setTenantId(String tenantId) { this.tenantId = tenantId; }
  public String getEngine() { return engine; }
  public void setEngine(String engine) { this.engine = engine; }
  public String getArticleId() { return articleId; }
  public void setArticleId(String articleId) { this.articleId = articleId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getSummary() { return summary; }
  public void setSummary(String summary) { this.summary = summary; }
  public String getBody() { return body; }
  public void setBody(String body) { this.body = body; }
  public String getSourceUrl() { return sourceUrl; }
  public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
  public String getSourceLabel() { return sourceLabel; }
  public void setSourceLabel(String sourceLabel) { this.sourceLabel = sourceLabel; }
  public String getSections() { return sections; }
  public void setSections(String sections) { this.sections = sections; }
  public String getNotes() { return notes; }
  public void setNotes(String notes) { this.notes = notes; }
  public OffsetDateTime getImportedAt() { return importedAt; }
  public void setImportedAt(OffsetDateTime importedAt) { this.importedAt = importedAt; }
  public String getImportedBy() { return importedBy; }
  public void setImportedBy(String importedBy) { this.importedBy = importedBy; }
}
