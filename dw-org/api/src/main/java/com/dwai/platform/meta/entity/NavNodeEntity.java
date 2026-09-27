package com.dwai.platform.meta.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.OffsetDateTime;

/**
 * 侧栏菜单树上的一个节点（见 {@code V23__nav_nodes.sql}）。
 *
 * <p><b>分组与菜单在这里是同一个东西</b>：一个「分组」就是 {@code path} 为空的目录节点，
 * 层级由 {@link #parentId} 表达，不限制深度。V19~V22 时代的 {@code nav_groups} 表已随
 * V23 删除 —— 分组本来就是一层目录，摊平成两个字面量只会让层级、顺序、空目录策略
 * 分散在两处。
 *
 * <h2>三类节点，判据只看两列</h2>
 *
 * <table>
 *   <tr><th>类型</th><th>判据</th><th>渲染</th></tr>
 *   <tr><td>org 自有页面</td><td>{@code product = ''}</td>
 *       <td>{@link #path} 是 org 的完整路由，直接用</td></tr>
 *   <tr><td>手工复制的产品页</td><td>{@code product != ''} 且 {@code mounted = false}</td>
 *       <td>{@link #path} 是【子应用内】路径，前端拼 {@code /org/embed/{product}}</td></tr>
 *   <tr><td>挂载节点</td><td>{@code mounted = true}</td>
 *       <td>按 {@code (product, ref)} 在产品清单里找到那个节点，<b>渲染时实时展开</b></td></tr>
 * </table>
 *
 * <p><b>刻意没有 {@code source} 列</b>：它由上面两列完全决定，多一个字段就多一处
 * 可以自相矛盾的地方（{@code source='org'} 而 {@code product='metadata'} 该听谁的）。
 *
 * <p>字段与列一一对应、没有 JSON 列，所以不需要 {@code autoResultMap}。
 */
@TableName("nav_nodes")
public class NavNodeEntity {
  @TableId(type = IdType.INPUT)
  private String id;
  /**
   * 挂哪个壳：{@code workbench}（工作台，进项目之前）或 {@code project}（进项目之后）。
   *
   * <p>取值白名单在 {@link com.dwai.platform.meta.NavNodeService#SCOPES}。
   */
  private String scope;
  /**
   * 父节点 id；<b>空串 = 壳下的顶层节点</b>（用户说的「主菜单」）。
   *
   * <p>用空串而不是 {@code null}：唯一约束里 {@code NULL} 与任何值都不相等，
   * 顶层节点因此可以重名，{@code uk_nav_node} 对顶层就形同虚设。
   */
  private String parentId;
  private String title;
  /**
   * 空串 = 目录节点（不可点，只用来挂子节点）。
   *
   * <p>不是 {@code '/'}：那是一个**合法**的子应用首页路径（仓建设的菜单里就有
   * {@code /model}），拿它当「目录」的哨兵值会把一个真页面变成点不动的目录。
   */
  private String path;
  /** 图标名，取值是前端 {@code navIcons} 的 key；未命中时前端给通用图标。 */
  private String icon;
  /**
   * 权限词（如 {@code catalog:read}），空串 = 谁都能进。
   *
   * <p><b>不满足时的表现分两种</b>（与 V18 迁移的既有口径一致，见
   * {@link com.dwai.platform.meta.NavNodeService}）：org 自有节点<b>置灰并说明</b>
   * （隐藏了会让人以为壳坏了），产品节点<b>不返回</b>（那个产品的入口与他无关）。
   *
   * <p>org 自有节点的词按<b>本服务的产品</b>判角色 —— 与 {@code AccessService.requireMember}
   * 同一处口径，因为它们说的是同一件事：「这个人在本项目里有没有管成员的权限」。
   */
  private String perm;
  /** 【同一个父节点下】的先后。 */
  private Integer sortOrder;
  private Boolean enabled;
  /** 仅租户管理员可见（org 自有节点用）；产品节点的可见性由许可与角色决定。 */
  private Boolean adminOnly;
  /**
   * 产品码，见 {@link com.dwai.platform.meta.ProductCodes#KNOWN}；<b>空串 = org 自己的页面</b>。
   *
   * <p>它是「这一行指向产品」的唯一判据 —— 判断「路径要不要拼 embed 前缀」、
   * 「要不要按租户许可过滤」都看它。
   */
  private String product;
  /**
   * {@link #mounted} 为真时：产品清单里那个节点的 id。
   *
   * <p><b>匹配键是它，不是 {@link #title}</b> —— 所以挂载之后改名是安全的，
   * 只是改显示名。这是相对 V21「已挂载的分组不能改名」的一处改进：那时匹配键是
   * 分组名这个自由字符串，改一下就与产品清单再也对不上，表现为「改名成功、侧栏
   * 里这一组整个消失」。
   */
  private String ref;
  /**
   * 是否「挂载」了产品的某个节点 —— 挂载后这一支的内容由产品在**渲染时**提供，
   * 产品新增子菜单侧栏自动跟上（见 {@code V23__nav_nodes.sql}）。
   */
  private Boolean mounted;
  /**
   * 空目录策略：{@code always}（一个可用子项都没有时该节点保留、置灰说明）或
   * {@code hide}（整枝不出现）。
   *
   * <p>取值白名单在 {@link com.dwai.platform.meta.NavNodeService#EMPTY_POLICIES}。
   * 默认 {@code hide} 与迁移里的列默认值一致 = 现状行为。
   */
  private String emptyPolicy;
  private OffsetDateTime createdAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getScope() { return scope; }
  public void setScope(String scope) { this.scope = scope; }
  public String getParentId() { return parentId; }
  public void setParentId(String parentId) { this.parentId = parentId; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public String getPath() { return path; }
  public void setPath(String path) { this.path = path; }
  public String getIcon() { return icon; }
  public void setIcon(String icon) { this.icon = icon; }
  public String getPerm() { return perm; }
  public void setPerm(String perm) { this.perm = perm; }
  public Integer getSortOrder() { return sortOrder; }
  public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
  public Boolean getEnabled() { return enabled; }
  public void setEnabled(Boolean enabled) { this.enabled = enabled; }
  public Boolean getAdminOnly() { return adminOnly; }
  public void setAdminOnly(Boolean adminOnly) { this.adminOnly = adminOnly; }
  public String getProduct() { return product; }
  public void setProduct(String product) { this.product = product; }
  public String getRef() { return ref; }
  public void setRef(String ref) { this.ref = ref; }
  public Boolean getMounted() { return mounted; }
  public void setMounted(Boolean mounted) { this.mounted = mounted; }
  public String getEmptyPolicy() { return emptyPolicy; }
  public void setEmptyPolicy(String emptyPolicy) { this.emptyPolicy = emptyPolicy; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
