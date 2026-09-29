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
 * <h2>五类节点，判据只看几列（V29 起由三类扩到五类）</h2>
 *
 * <table>
 *   <tr><th>类型</th><th>判据</th><th>渲染</th></tr>
 *   <tr><td>org 自有页面</td><td>{@code product = ''}</td>
 *       <td>{@link #path} 是 org 的完整路由，直接用</td></tr>
 *   <tr><td>手工复制的产品页</td><td>{@code product != ''} 且 {@code mounted = false}</td>
 *       <td>{@link #path} 是【子应用内】路径，前端拼 {@code /org/embed/{product}}</td></tr>
 *   <tr><td>挂载节点</td><td>{@code mounted = true}</td>
 *       <td>按 {@code (product, ref)} 在产品清单里找到那个节点，<b>渲染时实时展开</b></td></tr>
 *   <tr><td>入口页（V29）</td><td>{@link #entryPage} = {@code true}</td>
 *       <td>自己是一排 Tab，列出 {@code nav_entry_links} 挂的菜单；
 *           {@link #path} <b>由服务端在渲染时注入</b>（id 是服务端生成的，管理员填不了）</td></tr>
 *   <tr><td>外链菜单（V29）</td><td>{@link #externalUrl} 非空</td>
 *       <td>指向平台外的一个地址，可内嵌（{@code embed}）可新标签页打开（{@code jump}）</td></tr>
 * </table>
 *
 * <p><b>刻意没有 {@code source} 列</b>：它由上面几列完全决定，多一个字段就多一处
 * 可以自相矛盾的地方（{@code source='org'} 而 {@code product='metadata'} 该听谁的）。
 * 同理，入口页与外链**都与 {@code product} 互斥、且互相排斥** —— 那几条校验在
 * {@code NavNodeService.apply()} 里，与 {@code adminOnly} / {@code mounted} 放在一处。
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

  /**
   * 入口页（V29）：这一行自己是一排 **Tab**，列出 {@code nav_entry_links} 挂的菜单。
   *
   * <p>被挂的菜单<b>在它原来的位置也还在</b>（引用，不是父子）—— 这是它与
   * {@link #parentId} 那套的本质区别。
   *
   * <p>单独一列而不是靠「有没有关联记录」判：<b>空入口页是合法状态</b>（建好了还没挂东西），
   * 靠关联表判会让它退化成目录节点（{@link #path} 为空、点不动），而它明明有页面。
   */
  private Boolean entryPage;

  /**
   * 外链菜单的目标地址（V29）；<b>非空 = 这一行是一条外部链接</b>（{@link #product} 必为空）。
   *
   * <p>只允许 {@code http} / {@code https}：它会落到前端的 {@code href} 与 iframe {@code src} 上，
   * 放 {@code javascript:} 进来就是一处可执行的注入口。协议校验在 {@code NavNodeService}
   * 写入时做（拒绝，而不是静默丢弃）。
   */
  private String externalUrl;

  /**
   * 打开方式：{@code embed}（内嵌进壳）/ {@code jump}（新标签页）。
   *
   * <p>取值白名单在 {@code NavNodeService.OPEN_MODES}。
   */
  private String openMode;

  /**
   * 认证方式：{@code none} / {@code token} / {@code basic}。
   *
   * <p><b>{@code basic} 不会带来任何自动化</b>：现代浏览器禁止 {@code https://user:pass@host}
   * 作为 iframe 地址，也无法代填第三方的登录表单。这一档的实际用途只剩「平台管理员
   * 保存备查 + 复制」，UI 上必须写明。取值白名单在 {@code NavNodeService.AUTH_MODES}。
   */
  private String authMode;

  /**
   * 认证 token 的密文（V29）。
   *
   * <p>加密复用 {@code LlmCrypto}，与 LLM Key / 调度器 Token 同一把密钥（类名带 {@code Llm}
   * 是历史原因）。<b>任何回传路径只给 {@code hasToken} 布尔</b>，唯一的明文出口是平台管理员
   * 显式调用的「复制」接口。
   */
  private byte[] tokenEnc;

  /** {@code basic} 认证的用户名（不算秘密，明文存）。 */
  private String basicUser;
  /** {@code basic} 认证的密码密文（V29）。处置同 {@link #tokenEnc}。 */
  private byte[] basicPassEnc;

  /**
   * 外链菜单独立的可见性开关：{@code all}（所有人）/ {@code tenant_admin}（仅租户管理员）。
   *
   * <p><b>不复用 {@link #adminOnly}</b>：那个的语义是「仅租户管理员可见」的 org 自有页面，
   * 而这里要回答的是「谁能用这条带凭据的入口」。也不复用模块可见范围那四档 ——
   * 后三档都要 {@code product} 才能算（{@code currentRole(projectId, product)}），
   * 而外链菜单没有产品。
   */
  private String visibility;

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
  public Boolean getEntryPage() { return entryPage; }
  public void setEntryPage(Boolean entryPage) { this.entryPage = entryPage; }
  public String getExternalUrl() { return externalUrl; }
  public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
  public String getOpenMode() { return openMode; }
  public void setOpenMode(String openMode) { this.openMode = openMode; }
  public String getAuthMode() { return authMode; }
  public void setAuthMode(String authMode) { this.authMode = authMode; }
  public byte[] getTokenEnc() { return tokenEnc; }
  public void setTokenEnc(byte[] tokenEnc) { this.tokenEnc = tokenEnc; }
  public String getBasicUser() { return basicUser; }
  public void setBasicUser(String basicUser) { this.basicUser = basicUser; }
  public byte[] getBasicPassEnc() { return basicPassEnc; }
  public void setBasicPassEnc(byte[] basicPassEnc) { this.basicPassEnc = basicPassEnc; }
  public String getVisibility() { return visibility; }
  public void setVisibility(String visibility) { this.visibility = visibility; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(OffsetDateTime createdAt) { this.createdAt = createdAt; }
}
