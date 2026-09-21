package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

@TableName(value = "warehouse_tables", autoResultMap = true)
public class WarehouseTableEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String projectId;
  private String layer;
  private String name;
  private String comment;
  private String domain;
  private String sourceSystem;
  private String grain;
  private String period;
  private String partitionCol;
  private String storedAs;
  private String status;
  private String createdFrom;
  private String grade;
  private Integer currentVersion;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String sources;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String joins;
  @TableField("filter_expr")
  private String filter;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getLayer() { return layer; }
  public void setLayer(String layer) { this.layer = layer; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getComment() { return comment; }
  public void setComment(String comment) { this.comment = comment; }
  public String getDomain() { return domain; }
  public void setDomain(String domain) { this.domain = domain; }
  public String getSourceSystem() { return sourceSystem; }
  public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
  public String getGrain() { return grain; }
  public void setGrain(String grain) { this.grain = grain; }
  public String getPeriod() { return period; }
  public void setPeriod(String period) { this.period = period; }
  public String getPartitionCol() { return partitionCol; }
  public void setPartitionCol(String partitionCol) { this.partitionCol = partitionCol; }
  public String getStoredAs() { return storedAs; }
  public void setStoredAs(String storedAs) { this.storedAs = storedAs; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getCreatedFrom() { return createdFrom; }
  public void setCreatedFrom(String createdFrom) { this.createdFrom = createdFrom; }
  public String getGrade() { return grade; }
  public void setGrade(String grade) { this.grade = grade; }
  public Integer getCurrentVersion() { return currentVersion; }
  public void setCurrentVersion(Integer currentVersion) { this.currentVersion = currentVersion; }
  public String getSources() { return sources; }
  public void setSources(String sources) { this.sources = sources; }
  public String getJoins() { return joins; }
  public void setJoins(String joins) { this.joins = joins; }
  public String getFilter() { return filter; }
  public void setFilter(String filter) { this.filter = filter; }
}
