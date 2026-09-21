package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("project_members")
public class ProjectMemberEntity {
  private String projectId;
  private String userId;
  private String role;

  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
}
