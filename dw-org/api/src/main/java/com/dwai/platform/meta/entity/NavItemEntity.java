package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 一条门户菜单项（见 {@code V17__nav_items.sql}）。
 *
 * <p>字段与列一一对应、没有 JSON 列，所以不需要 {@code autoResultMap}
 * （{@code TenantLicenseEntity} 要它是因为 {@code modules} 是 JSON）。
 */
@TableName("nav_items")
public class NavItemEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  /** 产品码，见 {@link com.dwai.platform.meta.ProductCodes#KNOWN}。 */
  private String product;
  /**
   * 挂哪个壳：{@code workbench}（工作台，进项目之前）或 {@code project}（进项目之后）。
   *
   * <p>取值白名单在 {@link com.dwai.platform.meta.NavItemService#SCOPES}。
   */
  private String scope;
  /** 侧栏分组标题（数据地图、规范中心…），空串 = 不分组。 */
  private String groupTitle;
  private String label;
  /** 图标名，取值是前端 {@code navIcons} 的 key；未命中时前端给通用图标。 */
  private String icon;
  /** 【子应用内】路径，如 {@code /lineage/tables}；完整路由由前端拼。 */
  private String path;
  /**
   * 权限词（如 {@code catalog:read}），空串 = 谁都能进。
   *
   * <p>壳里拿它做「留在原地但置灰」而不是抹掉 —— 一个空侧栏会让人以为服务坏了。
   */
  private String perm;
  private Integer sortOrder;
  private Boolean enabled;
  private OffsetDateTime createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String getScope() { return scope; }
  public void setScope(String scope) { this.scope = scope; }
  public String getGroupTitle() { return groupTitle; }
  public void setGroupTitle(String groupTitle) { this.groupTitle = groupTitle; }
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  public String getIcon() { return icon; }
  public void setIcon(String icon) { this.icon = icon; }
  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }
  public String getPerm() { return perm; }
  public void setPerm(String perm) { this.perm = perm; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
