package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

@TableName(value = "domains", autoResultMap = true)
public class DomainEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String projectId;
  private String code;
  private String name;
  private String definition;
  private String bizOwner;
  private String techOwner;
  private String dataOwner;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String related;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String coreEntities;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getCode() { return code; }
  public void setCode(String code) { this.code = code; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getDefinition() { return definition; }
  public void setDefinition(String definition) { this.definition = definition; }
  public String getBizOwner() { return bizOwner; }
  public void setBizOwner(String bizOwner) { this.bizOwner = bizOwner; }
  public String getTechOwner() { return techOwner; }
  public void setTechOwner(String techOwner) { this.techOwner = techOwner; }
  public String getDataOwner() { return dataOwner; }
  public void setDataOwner(String dataOwner) { this.dataOwner = dataOwner; }
  public String getRelated() { return related; }
  public void setRelated(String related) { this.related = related; }
  public String getCoreEntities() { return coreEntities; }
  public void setCoreEntities(String coreEntities) { this.coreEntities = coreEntities; }
}
