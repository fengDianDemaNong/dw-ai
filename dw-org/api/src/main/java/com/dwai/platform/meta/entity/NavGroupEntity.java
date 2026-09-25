package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 一个侧栏分组（见 {@code V19__nav_groups.sql}）。
 *
 * <p><b>它与 {@link NavItemEntity#getGroupTitle()} 是软约束关系</b>：本表的
 * {@code title} 与菜单项里那个自由字符串做的是字符串相等匹配，没有外键。
 * 理由写在迁移文件的顶部注释里（动态生成的分组名必须仍能落库）。
 *
 * <p>因此这里<b>刻意没有</b>任何 URL 字段 —— 分组只影响侧栏的组织方式，
 * 不该成为「配一个能跳转的入口」的第二条路径。菜单路径的唯一来源仍是各服务
 * 自报的候选（{@code MenuCandidateService}）。
 *
 * <p>字段与列一一对应、没有 JSON 列，所以不需要 {@code autoResultMap}。
 */
@TableName("nav_groups")
public class NavGroupEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  /**
   * 挂哪个壳：{@code workbench} 或 {@code project}。
   *
   * <p>取值白名单复用 {@link com.dwai.platform.meta.NavItemService#SCOPES} ——
   * 不在这里另开一套，两处漂移会让「分组挂的壳」与「菜单挂的壳」对不上，
   * 表现为分组建好了却永远匹配不到任何菜单项。
   */
  private String scope;
  /** 产品码，见 {@link com.dwai.platform.meta.ProductCodes#KNOWN}。 */
  private String product;
  /** 分组标题，与 {@code nav_items.group_title} 逐字比较。 */
  private String title;
  /** 组【间】顺序（组内顺序仍由菜单项的 {@code sort_order} 决定）。 */
  private Integer sortOrder;
  /**
   * 空组策略：{@code always}（无可用菜单时整组保留、入口置灰说明）或
   * {@code hide}（整组隐藏）。
   *
   * <p>取值白名单在 {@link com.dwai.platform.meta.NavGroupService#EMPTY_POLICIES}。
   * 默认 {@code hide} 与迁移里的列默认值一致 —— 即现状行为。
   */
  private String emptyPolicy;
  private OffsetDateTime createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getScope() { return scope; }
  public void setScope(String scope) { this.scope = scope; }
  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public String getEmptyPolicy() { return emptyPolicy; }
  public void setEmptyPolicy(String emptyPolicy) { this.emptyPolicy = emptyPolicy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
