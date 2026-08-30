package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("layer_rules")
public class LayerRuleEntity {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String projectId;
  private String layer;
  private String naming;
  private String retention;
  private String serve;
  private String note;
  private String fieldFormat;
  private String timeFormat;
  private String masking;
  private String maskingNote;
  private String nullHandling;
  private String nullFill;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getLayer() { return layer; }
  public void setLayer(String layer) { this.layer = layer; }
  public String getNaming() { return naming; }
  public void setNaming(String naming) { this.naming = naming; }
  public String getRetention() { return retention; }
  public void setRetention(String retention) { this.retention = retention; }
  public String getServe() { return serve; }
  public void setServe(String serve) { this.serve = serve; }
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
  public String getFieldFormat() { return fieldFormat; }
  public void setFieldFormat(String fieldFormat) { this.fieldFormat = fieldFormat; }
  public String getTimeFormat() { return timeFormat; }
  public void setTimeFormat(String timeFormat) { this.timeFormat = timeFormat; }
  public String getMasking() { return masking; }
  public void setMasking(String masking) { this.masking = masking; }
  public String getMaskingNote() { return maskingNote; }
  public void setMaskingNote(String maskingNote) { this.maskingNote = maskingNote; }
  public String getNullHandling() { return nullHandling; }
  public void setNullHandling(String nullHandling) { this.nullHandling = nullHandling; }
  public String getNullFill() { return nullFill; }
  public void setNullFill(String nullFill) { this.nullFill = nullFill; }
}
