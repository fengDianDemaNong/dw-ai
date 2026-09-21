package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("data_grades")
public class DataGradeEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String projectId;
  private String code;
  private String name;
  private Integer level;
  private String color;
  private String queryPolicy;
  private String exportPolicy;
  private String note;
  private String examples;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public Integer getLevel() { return level; }
  public void setLevel(Integer level) { this.level = level; }
  public String getColor() { return color; }
  public void setColor(String color) { this.color = color; }
  public String getQueryPolicy() { return queryPolicy; }
  public void setQueryPolicy(String queryPolicy) { this.queryPolicy = queryPolicy; }
  public String getExportPolicy() { return exportPolicy; }
  public void setExportPolicy(String exportPolicy) { this.exportPolicy = exportPolicy; }
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
  public String getExamples() { return examples; }
  public void setExamples(String examples) { this.examples = examples; }
}
