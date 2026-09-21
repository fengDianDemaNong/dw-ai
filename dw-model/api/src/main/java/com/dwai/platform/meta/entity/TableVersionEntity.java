package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

import java.time.OffsetDateTime;

@TableName(value = "table_versions", autoResultMap = true)
public class TableVersionEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  private String tableId;
  private Integer version;
  private String note;
  private String actorUserId;
  private OffsetDateTime createdAt;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String snapshot;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getTableId() { return tableId; }
  public void setTableId(String tableId) { this.tableId = tableId; }
  public Integer getVersion() { return version; }
  public void setVersion(Integer version) { this.version = version; }
  public String getNote() { return note; }
  public void setNote(String note) { this.note = note; }
  public String getActorUserId() { return actorUserId; }
  public void setActorUserId(String actorUserId) { this.actorUserId = actorUserId; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
  public String getSnapshot() { return snapshot; }
  public void setSnapshot(String snapshot) { this.snapshot = snapshot; }
}
