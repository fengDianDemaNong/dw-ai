package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 入口页挂进来的**产品清单节点**（见 {@code V30__nav_entry_product_links.sql}）。
 *
 * <p>与 {@link NavEntryLinkEntity} 是同一件事的两半：那一张装的是 {@code nav_nodes.id}
 * （壳里真实存在的菜单），这一张装的是产品清单里的 id（`{frontendUrl}/menu.json`）——
 * 后者在 {@code nav_nodes} 里**没有行**，所以引用不了。
 *
 * <p><b>为什么不合成一张表</b>：清单 id 长度实测已到 58 字符，而 {@code target_id} 是
 * {@code VARCHAR(64)}；加宽它没有三方言通用写法。详见 V30 的注释。
 *
 * <p><b>顺序自己存</b>（{@link #sortOrder}）：{@code nav_entry_links} 刻意不带排序列，
 * 因为它那类引用的顺序跟随 target 自己在 {@code nav_nodes} 里的顺序；清单节点没有那份
 * 「自己的顺序」可跟，只能按勾选顺序存。
 *
 * <p>主键是复合的 {@code (entry_id, product, ref)}，处置同 {@link NavEntryLinkEntity}：
 * 不标 {@code @TableId}，只走 wrapper。
 */
@TableName("nav_entry_product_links")
public class NavEntryProductLinkEntity {
  /** 容器节点 id（{@code nav_nodes.id}，必须是 {@code entry_page = TRUE} 的行）。 */
  private String entryId;
  /** 产品码，见 {@link com.dwai.platform.meta.ProductCodes#KNOWN}。 */
  private String product;
  /** 产品清单里那个节点的 id。 */
  private String ref;
  /**
   * Tab 顺序（同一个入口页内）。V31 起与 {@link NavEntryLinkEntity#getSortOrder()}
   * <b>共用一个序号空间</b>（前端按行号统一编号），读侧把两表合并后按它排。
   */
  private Integer sortOrder;
  /**
   * 这一条 Tab 自己显示的名字（V31）。{@code null} / 空串 = <b>没改过名</b>，
   * 渲染时用清单节点自己的标题（{@code menu.json} 里的 {@code label}）。
   */
  private String label;

  public NavEntryProductLinkEntity() {}

  public NavEntryProductLinkEntity(
      String entryId, String product, String ref, Integer sortOrder, String label) {
    this.entryId = entryId;
    this.product = product;
    this.ref = ref;
    this.sortOrder = sortOrder;
    this.label = label;
  }

  public String getEntryId() { return entryId; }
  public void setEntryId(String entryId) { this.entryId = entryId; }
  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String getRef() { return ref; }
  public void setRef(String ref) { this.ref = ref; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
}
