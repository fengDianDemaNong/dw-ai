package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 入口页挂了哪些菜单（见 {@code V29__nav_entry_and_external.sql} 的 {@code nav_entry_links}）。
 *
 * <p><b>这是「引用」不是「父子」</b>：被挂的菜单在它<b>原来的位置也还在</b>（用户裁定），
 * 同一条菜单可以同时出现在多个入口页里。这与 {@link NavNodeEntity#getParentId()} 那套
 * 是两回事 —— 父子是「唯一的归属」，这里是多对多。
 *
 * <p><b>表的主键是复合的 {@code (entry_id, target_id)}</b>，而 MyBatis-Plus 的
 * {@code BaseMapper} 只认单列主键。这里的处理照仓库里已有的同类实体
 * {@link ProductRolePermEntity}（主键 {@code (role_id, perm)}）：<b>不标 {@code @TableId}</b>，
 * 于是 MP 不生成任何 {@code xxById} 方法 —— 启动日志里那几行
 * 「Can not find table primary key ... Cannot use Mybatis-Plus 'xxById' Method」
 * 就是这个取舍的表现，是刻意的。标一个 {@code @TableId(entryId)} 更糟：
 * {@code selectById} 会变成「按 entry_id 查任意一条」，看着能用、实际拿到的是随便一项。
 *
 * <p>本表只走 wrapper（{@code selectList} / {@code delete(Wrapper)} / {@code insert}）。
 *
 * <p><b>V31 起这张表自己带 {@code label} 与 {@code sort_order}</b> —— V29 那句
 * 「不设 sort_order，顺序跟随 target 自己」被用户裁定推翻了（行顺序 = Tab 顺序），
 * 理由见 {@code V31__entry_link_label_and_order.sql}。
 */
@TableName("nav_entry_links")
public class NavEntryLinkEntity {
  /** 容器节点 id（{@code nav_nodes.id}，必须是 {@code entry_page = TRUE} 的行）。 */
  private String entryId;
  /** 被挂的菜单 id（{@code nav_nodes.id}）。 */
  private String targetId;
  /**
   * 这一条 Tab 自己显示的名字（V31）。{@code null} / 空串 = <b>没改过名</b>，
   * 渲染时用被挂菜单自己的标题 —— 不是「名字为空」。
   */
  private String label;
  /**
   * Tab 顺序（V31）。同一个入口页内，{@link NavEntryProductLinkEntity#getSortOrder()}
   * 与本列<b>共用一个序号空间</b>（前端按行号统一编号），读侧把两表合并后按它排。
   *
   * <p>{@code 0} 在旧行上表示「V31 之前存的、没排过」；读侧并列时的兜底会复现旧顺序
   * （见 {@code NavNodeService.entryPage}），所以老入口页的 Tab 顺序不会因为这次迁移而变。
   */
  private Integer sortOrder;

  public NavEntryLinkEntity() {}

  public NavEntryLinkEntity(String entryId, String targetId, String label, Integer sortOrder) {
    this.entryId = entryId;
    this.targetId = targetId;
    this.label = label;
    this.sortOrder = sortOrder;
  }

  public String getEntryId() { return entryId; }
  public void setEntryId(String entryId) { this.entryId = entryId; }
  public String getTargetId() { return targetId; }
  public void setTargetId(String targetId) { this.targetId = targetId; }
  public String getLabel() { return label; }
  public void setLabel(String label) { this.label = label; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
