package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("word_roots")
public class WordRootEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String projectId;
  private String kind;
  private String code;
  private String zh;
  private String en;
  private String domain;
  private String formula;
  private String dataType;
  private String format;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getKind() { return kind; }
  public void setKind(String kind) { this.kind = kind; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getZh() { return zh; }
  public void setZh(String zh) { this.zh = zh; }
  public String getEn() { return en; }
  public void setEn(String en) { this.en = en; }
  public String getDomain() { return domain; }
  public void setDomain(String domain) { this.domain = domain; }
  public String getFormula() { return formula; }
  public void setFormula(String formula) { this.formula = formula; }
  public String getDataType() { return dataType; }
  public void setDataType(String dataType) { this.dataType = dataType; }
  public String getFormat() { return format; }
  public void setFormat(String format) { this.format = format; }
}
