package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.dwai.platform.meta.support.JsonbStringTypeHandler;

@TableName(value = "table_columns", autoResultMap = true)
public class TableColumnEntity {
  private String tableId;
  private String name;
  private String type;
  private String comment;
  private Boolean nullable;
  private String defaultValue;
  /** sensitive 是 MySQL 8 保留字，反引号是必须的：MyBatis-Plus 会把它原样拼进 SQL。 */
  @TableField("`sensitive`")
  private Boolean sensitive;
  private String grade;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String enumValues;
  @TableField(typeHandler = JsonbStringTypeHandler.class)
  private String logic;
  private Integer pos;

  public String getTableId() { return tableId; }
  public void setTableId(String tableId) { this.tableId = tableId; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getComment() { return comment; }
  public void setComment(String comment) { this.comment = comment; }
  public Boolean getNullable() { return nullable; }
  public void setNullable(Boolean nullable) { this.nullable = nullable; }
  public String getDefaultValue() { return defaultValue; }
  public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
  public Boolean getSensitive() { return sensitive; }
  public void setSensitive(Boolean sensitive) { this.sensitive = sensitive; }
  public String getGrade() { return grade; }
  public void setGrade(String grade) { this.grade = grade; }
  public String getEnumValues() { return enumValues; }
  public void setEnumValues(String enumValues) { this.enumValues = enumValues; }
  public String getLogic() { return logic; }
  public void setLogic(String logic) { this.logic = logic; }
  public Integer getPos() { return pos; }
  public void setPos(Integer pos) { this.pos = pos; }
}
