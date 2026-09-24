package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 项目成员的产品角色。
 *
 * <p>主键是 `(project_id, user_id, product)`：同一个人在同一个项目里，在仓建设与
 * 数据地图可以是不同角色 —— 那不是两个身份，是同一个身份在两个产品里的词不同
 * （见 {@link com.dwai.platform.meta.support.Perms}）。
 *
 * <p>`product` 存产品码（`warehouse` / `metadata`，见 docs/tech/06-0.1.5-routes.md §2），
 * `role` 存该产品下的角色码（`admin` / `modeler` / `viewer`）。
 */
@TableName("project_members")
public class ProjectMemberEntity {
  private String projectId;
  private String userId;
  private String product;
  private String role;

  public String getProjectId() { return projectId; }
  public void setProjectId(String projectId) { this.projectId = projectId; }
  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }
  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String getRole() { return role; }
  public void setRole(String role) { this.role = role; }
}
