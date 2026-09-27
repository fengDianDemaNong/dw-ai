package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwai.platform.internal.ServiceRegistry;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.NavNodeEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.mapper.NavNodeMapper;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 门户菜单树：管理面（平台管理员配树）与消费面（租户成员读树）。
 *
 * <p><b>分组与菜单在这里是同一个东西</b>：一个「分组」就是一个 {@code path} 为空的
 * 目录节点，层级由 {@code parent_id} 表达、不限深度（见 {@code V23__nav_nodes.sql}）。
 * 本类取代了 V19~V22 时代的 {@code NavItemService} + {@code NavGroupService} ——
 * 那两个类是「分组」「菜单」两个概念的产物，合并之后归到一个 service。
 *
 * <h2>两类来源，判据只看两列</h2>
 *
 * <ul>
 *   <li><b>org 自有节点</b>（{@code product = ''}）：{@code path} 是 org 的完整路由
 *       （{@code /org/workbench/users}），直接可用。工作台壳与项目壳的 org 自有菜单
 *       现在是 {@code V23} 的种子数据，不再由前端硬编码。</li>
 *   <li><b>产品节点</b>（{@code product != ''}）：分手工复制（{@code mounted = false}，
 *       {@code path} 是子应用内路径，前端拼 {@code /org/embed/{product}}）与
 *       <b>挂载</b>（{@code mounted = true}，按 {@code (product, ref)} 实时展开）两种。</li>
 * </ul>
 *
 * <h2>消费面的三条既有规则，全部保留</h2>
 *
 * <ol>
 *   <li><b>展开</b>：挂载节点的内容<b>每次读侧栏都按产品自报的清单现算</b>，产品新增
 *       子菜单，org 的壳上就跟着多一条，中间不需要任何管理动作。</li>
 *   <li><b>接管</b>：展开<b>会吐出来的那些</b> {@code (product, path)}，同键的手工行
 *       不再输出 —— 否则同一批菜单会在侧栏里出现两遍（先复制过、后来又挂了同一个节点，
 *       是常见状态）。范围刻意收窄成「展开真的会吐出来的那些路径」：收窄之后，被接管掉的
 *       每一项都保证在本次结果里有替代品，<b>任何一个菜单项都不会因为一次管理动作而失踪</b>。</li>
 *   <li><b>降级</b>：产品清单<b>拉不到</b>（地址没登记 / 连不上 / 非 JSON）时，那一个产品
 *       既不展开也不接管，它的复制行照常渲染。侧栏是关键路径，宁可显示旧配置也不能因为
 *       某个产品挂了就空掉；展开为空的挂载节点按 {@code empty_policy} 处理。</li>
 * </ol>
 *
 * <h2>第三层：租户自己的模块策略（V27）</h2>
 *
 * <p>许可（{@code tenant_licenses.modules}）是<b>平台</b>给这个租户开通的上限，只有平台
 * 管理员能改；{@code tenant_licenses.module_policies}（工作台「模块管理」页写的）是租户
 * 在这个上限之内的第二层：本组织启用哪些、每个模块给谁看。项目侧栏 = 平台已开通 ∩
 * 租户已启用 ∩ 当前人可见，就是这里实现的。
 *
 * <p><b>无策略 = 不判</b>（{@link #policiesOf} 返回空 map 就是这种情况）：老租户没有这一行，
 * 升级后侧栏必须逐字不变。这是这一层唯一的向后兼容命门 —— 页面上的「默认值」只是
 * <b>显示的初值</b>（供管理员一进来就有个合理的起点），保存之后才真正生效。两者刻意
 * 不对称，别顺手「统一」：把生效默认也按 {@code role_holders} 判，会悄悄拿走一批人
 * 今天看得到的入口，而表现只是「侧栏少了一条」，不会有任何报错。
 *
 * <p>与许可的<b>fail 方向也不同</b>：许可那一份是 fail-closed（{@link #licensedProducts}，
 * 没有行 = 一个都没开）；策略这一份即使读到脏数据也只会「少挡一点」而不是把侧栏打空
 * （解析时逐项丢弃非法项，见 {@link #policiesOf}）。
 *
 * <h2>不满足条件时的两种表现，与 V18 迁移的既有口径一致</h2>
 *
 * <ul>
 *   <li><b>org 自有节点置灰</b>（{@code disabled} + {@code disabledReason}）而不是隐藏 ——
 *       一个空侧栏会让人以为壳坏了。现状的「成员管理」就是这么做的。</li>
 *   <li><b>产品节点不返回</b> —— 那个产品的入口与他无关，产品没开通时连行都不该有。</li>
 * </ul>
 * 这个区别不是随手定的：它对应「这个壳自己的功能」与「别人的功能被配进来」两类东西。
 */
@Service
public class NavNodeService {

  /** 工作台壳：进项目**之前**那一级（`/org/workbench/*`）。 */
  public static final String WORKBENCH = "workbench";
  /** 项目壳：进项目**之后**那一级（`/org/project/{code}/*`）。 */
  public static final String PROJECT = "project";

  /**
   * {@code nav_nodes.scope} 的白名单 —— 一个节点挂哪个壳。
   *
   * <p>与 {@link ProductCodes#KNOWN} 同样的「写入时就拒绝」：写进一个谁也不认的壳名，
   * 表现为接口 200、侧栏什么都不出现、没有任何报错，是最难查的一种坏法。
   */
  public static final Set<String> SCOPES = Set.of(WORKBENCH, PROJECT);

  /**
   * {@code nav_nodes.empty_policy} 的白名单。
   *
   * <p>两个取值各有一条规范依据：{@code always} 对应「数据地图那一支始终出现，
   * 未开通或未派角色时置灰并说明」，{@code hide} 对应「启航未配则项目无调度分组」。
   *
   * <p>与 {@link #SCOPES} 同样的「写入时就拒绝」：写进一个谁也不认的策略名，
   * 表现为接口 200、行为却按某个没人预期的默认值走。
   */
  public static final Set<String> EMPTY_POLICIES = Set.of("always", "hide");

  /**
   * {@code visibleTo} 的白名单 —— 模块可见范围的四档。
   *
   * <p>与 {@link #SCOPES} / {@link #EMPTY_POLICIES} 同样的「写入时就拒绝」：写进一个谁也不认
   * 的档名，表现为接口 200、权限<b>看起来</b>配了却按默认放行，是最难查的一种坏法。
   *
   * <p>四档的判据见 {@link Render#visible}。默认档是 {@code role_holders}
   * （warehouse 例外，见 {@link #defaultVisibleTo}）。
   */
  public static final Set<String> VISIBLE_TO =
      Set.of("tenant_admin", "project_admin", "role_holders", "all_members");

  /** 默认可见范围档（页面显示的初值，见 {@link #defaultVisibleTo}）。 */
  public static final String DEFAULT_VISIBLE_TO = "role_holders";

  /** 空目录策略里「对用户承诺更强」的那一个。 */
  public static final String ALWAYS = "always";

  /** 默认策略，与迁移里的列默认值一致 = 现状行为（空目录不渲染）。 */
  public static final String HIDE = "hide";

  /** 与 {@code nav_nodes.title} 同宽（见 {@code V23__nav_nodes.sql}）。 */
  private static final int TITLE_MAX = 64;
  private static final int REF_MAX = 128;
  private static final int PATH_MAX = 512;

  /** 顶层节点的 {@code parent_id}（不是 {@code null}，理由见实体注释）。 */
  private static final String ROOT = "";

  private final NavNodeMapper mapper;
  /**
   * 侧栏按租户许可过滤产品节点。
   *
   * <p>注入 mapper 而不是 {@code AccessService.licensedProducts}：那一份在「没有许可行」时
   * 回落 {@code ["warehouse"]}（fail-open，建项目时要用），而侧栏要的是 <b>fail-closed</b>
   * ——没有许可行 = 一个产品都没开，与前端 {@code hasModule} 一致。两处口径不同是有意的，
   * 所以各读各的表，不互相借。
   */
  private final TenantLicenseMapper licenses;
  private final ServiceRegistry registry;
  /** 挂载节点的内容来自产品自报的清单；管理动作后要让缓存失效，见 {@link #dropCandidateCache}。 */
  private final MenuCandidateService candidates;
  private final AccessService access;

  public NavNodeService(
      NavNodeMapper mapper,
      TenantLicenseMapper licenses,
      ServiceRegistry registry,
      MenuCandidateService candidates,
      AccessService access) {
    this.mapper = mapper;
    this.licenses = licenses;
    this.registry = registry;
    this.candidates = candidates;
    this.access = access;
  }

  /** 这条节点指向产品（判据只有一列，见类注释）。 */
  private static boolean isProductNode(NavNodeEntity n) {
    return n.getProduct() != null && !n.getProduct().isBlank();
  }

  /** 这条节点是「挂载窗口」：内容由产品在渲染时提供。 */
  private static boolean isMounted(NavNodeEntity n) {
    return Boolean.TRUE.equals(n.getMounted()) && isProductNode(n);
  }

  /** 这条节点挂了权限词（挂了才需要判「这个人看不看得见」）。 */
  private static boolean hasPermWord(String perm) {
    return perm != null && !perm.isBlank();
  }

  // ------------------------------------------------------------------
  // 管理面
  // ------------------------------------------------------------------

  /**
   * 全树（含停用的），按「壳 → 父节点 → 顺序 → 标题」排。平台管理员用。
   *
   * <p><b>返回嵌套树而不是平铺列表</b>：管理页要展示的本来就是一棵树，让前端拿
   * {@code parentId} 自己拼一遍等于把「谁是顶层」「谁排在谁下面」这两条规则实现两遍，
   * 两边漂移的表现是「管理页里的层级与侧栏里的不一样」。
   *
   * <p>不判权限、不判 {@code admin_only}、不管空目录策略 —— 管理面要看到配置的原样。
   */
  public List<Map<String, Object>> all(String scope) {
    LambdaQueryWrapper<NavNodeEntity> q = new LambdaQueryWrapper<NavNodeEntity>()
        .orderByAsc(NavNodeEntity::getScope)
        .orderByAsc(NavNodeEntity::getSortOrder)
        .orderByAsc(NavNodeEntity::getTitle);
    if (scope != null && !scope.isBlank()) q.eq(NavNodeEntity::getScope, scope.trim());

    return tree(mapper.selectList(q));
  }

  /** 把一批节点按 {@code parentId} 组成树，同级按 {@code sortOrder → title} 排。 */
  private static List<Map<String, Object>> tree(List<NavNodeEntity> rows) {
    Map<String, List<NavNodeEntity>> byParent = new HashMap<>();
    for (NavNodeEntity n : rows) {
      byParent.computeIfAbsent(nz(n.getParentId()), k -> new ArrayList<>()).add(n);
    }
    return childrenOf(ROOT, byParent);
  }

  private static List<Map<String, Object>> childrenOf(String parentId, Map<String, List<NavNodeEntity>> byParent) {
    List<NavNodeEntity> kids = byParent.getOrDefault(parentId, List.of());
    List<Map<String, Object>> out = new ArrayList<>(kids.size());
    for (NavNodeEntity n : kids) {
      Map<String, Object> row = toRow(n);
      row.put("children", childrenOf(n.getId(), byParent));
      out.add(row);
    }
    return out;
  }

  public Map<String, Object> create(NavNodeReq req) {
    NavNodeEntity e = new NavNodeEntity();
    // 与旧 nav_items 的 nav- 前缀同一套生成惯例（种子数据用的是可读的 nav-sys-*）
    e.setId("nav-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    apply(e, req, true);
    e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    insertOrConflict(e);
    dropCandidateCache();
    return toRow(e);
  }

  /**
   * 改一个节点：标题 / 图标 / 顺序 / 权限词 / 启停 / 父子关系 / 空目录策略 / 挂载。
   *
   * <p><b>scope 不能改</b>（它是节点身份的一部分，且与父节点必须同壳）：允许改会与
   * 「搬家」语义撞车 —— 到底是换壳还是换父节点？前端把它做成只读，这里也显式拒掉。
   *
   * <p><b>挂载节点改名是安全的</b>：匹配键是 {@code ref} 而不是标题（见实体注释），
   * 所以改标题只影响显示。这是相对 V21「已挂载的分组不能改名」的一处改进。
   */
  public Map<String, Object> update(String id, NavNodeReq req) {
    NavNodeEntity e = require(id);
    if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体");
    if (req.scope != null && !req.scope.trim().equals(e.getScope())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归属壳不能改（它是节点身份的一部分）");
    }
    apply(e, req, false);
    insertOrConflict(e);
    dropCandidateCache();
    return toRow(e);
  }

  /**
   * 删掉一个节点<b>连同它的整棵子树</b>。
   *
   * <p>返回 {@code subtree} = 连带删掉的子孙条数（不含自己）。<b>不阻塞、不做「有子节点就拒绝」</b>：
   * 拒绝的话管理员得从下往上一个一个删，而他要表达的意思恰恰是「这一支不要了」。
   * 级联删除的后果由前端二次确认承载，返回的条数就是那句确认里的数字。
   *
   * <p>返回 {@code mounted} = 这一支里有多少行是挂载的：那些行没有本地内容，
   * 删掉它们只是「不再跟随产品」，与删掉手工行不是一回事，值得让管理员看见。
   */
  public Map<String, Object> delete(String id) {
    NavNodeEntity e = require(id);
    List<NavNodeEntity> doomed = subtreeOf(e, allRows());
    int mounted = (int) doomed.stream().filter(NavNodeService::isMounted).count();
    for (NavNodeEntity n : doomed) mapper.deleteById(n.getId());
    // 删掉挂载行后这一支立刻回落到手工行 —— 不丢缓存的话，管理员删完刷新侧栏，
    // 看到的还是产品展开出来的那一支，会以为没删掉。
    dropCandidateCache();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("subtree", doomed.size() - 1);
    out.put("mounted", mounted);
    return out;
  }

  /** 自己 + 所有子孙（按「父先子后」序，删除时不必再排）。 */
  private static List<NavNodeEntity> subtreeOf(NavNodeEntity root, List<NavNodeEntity> rows) {
    List<NavNodeEntity> out = new ArrayList<>();
    out.add(root);
    Set<String> seen = new HashSet<>();
    seen.add(root.getId());
    for (int i = 0; i < out.size(); i++) {
      String pid = out.get(i).getId();
      for (NavNodeEntity n : rows) {
        if (nz(n.getParentId()).equals(pid) && seen.add(n.getId())) out.add(n);
      }
    }
    return out;
  }

  private List<NavNodeEntity> allRows() {
    return mapper.selectList(new LambdaQueryWrapper<>());
  }

  /**
   * 把请求体套到实体上，并校验。
   *
   * <p><b>这里刻意没有「这个节点有没有被别的东西引用」这类校验</b>，也没有反过来的
   * 「菜单的父节点有没有登记」—— 父子关系是强约束（{@code parent_id}），但「配了一个
   * 没有任何子节点的空目录」是合法状态，由 {@code empty_policy} 决定它出不出现在侧栏。
   *
   * @param creating 新建时缺省值才生效（改已有项时字段为空 = 不改，而不是清空）
   */
  private void apply(NavNodeEntity e, NavNodeReq req, boolean creating) {
    if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体");

    // scope 要么显式给，要么从父节点推（子节点跟着父走，不可能跨壳）
    if (req.parentId != null) e.setParentId(req.parentId.trim());

    if (req.scope != null) {
      String scope = req.scope.trim();
      if (!SCOPES.contains(scope)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知归属壳 " + scope + "。可用值：" + SCOPES);
      }
      e.setScope(scope);
    }
    if (creating && e.getScope() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 scope");
    }

    if (e.getParentId() == null) e.setParentId(ROOT);
    if (!ROOT.equals(e.getParentId())) {
      NavNodeEntity parent = mapper.selectById(e.getParentId());
      if (parent == null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "父节点不存在：" + e.getParentId());
      }
      if (!parent.getScope().equals(e.getScope())) {
        // 子节点跟着父走：跨壳挂会让侧栏按壳取树时那一支整个丢掉，且没有任何报错
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "父节点属于「" + parent.getScope() + "」壳，不能挂在「" + e.getScope() + "」壳下");
      }
    }
    requireNoCycle(e);

    if (req.title != null) {
      String title = req.title.trim();
      if (title.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "菜单名不能为空");
      // 显式校验而不是靠列宽兜底：超长时由哪个方言、以什么形态报错是不确定的
      // （MySQL 严格模式报错、宽松模式截断）。截断尤其糟 —— 名字被悄悄改短，
      // 管理员看到的与库里存的不是一回事。
      if (title.length() > TITLE_MAX) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "菜单名最长 " + TITLE_MAX + " 个字符，当前 " + title.length());
      }
      e.setTitle(title);
    } else if (creating) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 title");
    }

    if (req.path != null) e.setPath(normalizePath(req.path));
    else if (creating) e.setPath("");

    if (req.icon != null) e.setIcon(req.icon.trim());
    else if (creating) e.setIcon("");

    if (req.product != null) {
      String product = req.product.trim();
      if (!product.isEmpty() && !ProductCodes.isKnown(product)) {
        // 拒绝是为了把「配了个永远不会显示的菜单」挡在写入时：消费面按租户许可过滤，
        // 产品码与许可模块名对不上就静默不显示 —— 接口 200、侧栏空白、没有报错。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知产品码 " + product + "。可用值：" + ProductCodes.KNOWN);
      }
      e.setProduct(product);
    } else if (creating) {
      e.setProduct("");
    }

    if (req.perm != null) e.setPerm(checkPerm(e.getProduct(), req.perm));
    else if (creating) e.setPerm("");

    if (req.sortOrder != null) e.setSortOrder(req.sortOrder);
    else if (creating) e.setSortOrder(0);

    if (req.enabled != null) e.setEnabled(req.enabled);
    else if (creating) e.setEnabled(Boolean.TRUE);

    if (req.adminOnly != null) e.setAdminOnly(req.adminOnly);
    else if (creating) e.setAdminOnly(Boolean.FALSE);

    if (req.emptyPolicy != null) {
      String policy = req.emptyPolicy.trim();
      if (!EMPTY_POLICIES.contains(policy)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知空目录策略 " + policy + "。可用值：" + EMPTY_POLICIES);
      }
      e.setEmptyPolicy(policy);
    } else if (creating) {
      e.setEmptyPolicy(HIDE);
    }

    // 放在最后：挂载校验要用到上面已经套好的 product / ref / scope
    if (req.ref != null) e.setRef(req.ref.trim());
    else if (creating) e.setRef("");

    if (req.mounted != null) {
      // 只在「从没挂到挂」这一下求证，取消挂载与「已挂载时重复提交同一份表单」都不必再拉一遍清单
      if (req.mounted && !Boolean.TRUE.equals(e.getMounted())) {
        requireMountable(e);
      }
      e.setMounted(req.mounted);
    } else if (creating) {
      e.setMounted(Boolean.FALSE);
    }

    if (Boolean.TRUE.equals(e.getMounted())) {
      if (!isProductNode(e)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "只有产品节点能挂载 —— org 自己的页面（product 为空）没有可展开的内容");
      }
      if (nz(e.getRef()).isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "挂载要指定产品清单里的节点 id（ref）—— 不知道挂什么就挂不上，"
                + "表现为侧栏里一个永远空着的目录");
      }
    }
    if (Boolean.TRUE.equals(e.getAdminOnly()) && isProductNode(e)) {
      // admin_only 只对 org 自有节点有意义：产品节点的可见性由许可与角色决定（见类注释），
      // 两个规则叠在一起时「为什么这个成员看不到」就没有唯一答案了
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "「仅管理员可见」只能用在 org 自己的页面上（product 为空）");
    }
  }

  /**
   * 防环：不能把自己的父节点设成自己的子孙。
   *
   * <p>不加这一条的话，一次手误（或一个构造出来的请求）就能让整棵子树从侧栏里消失 ——
   * 组树时从 {@code parent_id = ''} 往下走，环上的节点永远不会被访问到，
   * 而管理页里它们明明还在。这与「任何一项都不会因为一次管理动作而失踪」是同一个要求。
   */
  private void requireNoCycle(NavNodeEntity e) {
    if (e.getId() == null || ROOT.equals(e.getParentId())) return;
    String pid = e.getParentId();
    Set<String> seen = new HashSet<>();
    while (!ROOT.equals(pid)) {
      if (pid.equals(e.getId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能把一个节点挂到它自己的子孙下面");
      }
      if (!seen.add(pid)) return; // 库里已有环（不该发生），就此停下而不是死循环
      NavNodeEntity parent = mapper.selectById(pid);
      if (parent == null) return;
      pid = nz(parent.getParentId());
    }
  }

  /**
   * 校验一个节点挂的权限词。
   *
   * <p><b>两层校验，第二层可以不生效</b>：
   *
   * <ul>
   *   <li><b>形状</b>（{@link PermWords}）：永远查。空串合法 = 不判权。</li>
   *   <li><b>归属</b>：该产品自报的词表里有没有这个词。词表拿不到时<b>跳过</b> ——
   *       某个服务在「服务注册」里的页面地址一没配，管理员就配不了它任何菜单，
   *       是拿一处配置缺失把整个功能锁死。</li>
   * </ul>
   *
   * <p>org 自有节点（{@code product} 为空）按<b>本服务的产品码</b>查词表
   * （见 {@link AccessService#productCode()}）：它挂的词（{@code iam:member}）本就是
   * 本服务的词，与 {@code requireMember} 判的是同一件事。
   */
  private String checkPerm(String product, String raw) {
    String perm = PermWords.requireWellFormed(raw, "权限词");
    if (perm.isEmpty()) return "";

    String owner = nz(product).isEmpty() ? access.productCode() : product;
    Set<String> known = candidates.knownPerms(owner);
    if (!known.isEmpty() && !known.contains(perm)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "产品「" + owner + "」的菜单不能挂权限词「" + perm + "」。它上报的词表是：" + known);
    }
    return perm;
  }

  /**
   * 挂载前求证：**该产品的清单里确实有 id 为 {@code ref} 的节点**。
   *
   * <p>不校验的话，「ref 打错一个字符」表现为挂载成功、侧栏里那一支永远空着 ——
   * 一个不报错、只在有人去看侧栏时才发现是坏的开关。这是挂载功能最容易踩的坑：
   * 挂载行本身在管理页里看起来完全正常。
   *
   * <p><b>与旧版按「分组名」求证的区别</b>：现在匹配键是产品清单里的节点 id，
   * 所以断言可以更精确 —— 旧版只能断言「有人报过这个名字」，现在是「这个 id 存在」。
   *
   * <p><b>清单拉不到时跳过校验</b>，与 {@link #checkPerm} 同一口径：服务注册里页面地址
   * 一没配，管理员就挂不了任何节点 —— 拿一处配置缺失把整个功能锁死。反过来，产品恢复后
   * 渲染路径会自然把结果暴露出来（挂对了就有内容，挂错了就是个空目录）。
   */
  private void requireMountable(NavNodeEntity e) {
    List<Map<String, Object>> menus = candidates.menusOrNull(e.getProduct());
    if (menus == null) return;
    if (findByRef(menus, e.getRef()) != null) return;

    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
        "产品「" + e.getProduct() + "」的清单里没有 id 为「" + e.getRef() + "」的节点，"
            + "挂载后这一支会是空的。它现在报的顶层节点是：" + describe(menus));
  }

  /**
   * 把「这个产品现在报了什么」摊开写进报错里。
   *
   * <p>只回一句「没有这个节点」时，管理员看到产品明明有这个页面，却没有任何线索知道
   * 该去核对哪个 id。只列顶层：清单可能很深，而挂载点通常就是顶层那几个之一。
   */
  private static String describe(List<Map<String, Object>> menus) {
    if (menus.isEmpty()) return "（空清单）";
    List<String> parts = new ArrayList<>();
    for (Map<String, Object> m : menus) {
      parts.add("「" + str(m.get("label")) + "」id=" + str(m.get("id"))
          + (str(m.get("path")).isEmpty() ? "（目录）" : ""));
    }
    return String.join("、", parts);
  }

  /** 在清单里按 id 找节点，深度优先。 */
  private static Map<String, Object> findByRef(List<Map<String, Object>> menus, String ref) {
    if (ref == null || ref.isBlank()) return null;
    for (Map<String, Object> m : menus) {
      if (ref.equals(str(m.get("id")))) return m;
      Map<String, Object> hit = findByRef(children(m), ref);
      if (hit != null) return hit;
    }
    return null;
  }

  /**
   * 归一化子应用路径。
   *
   * <p>只做三件事：空串保持空串（= 目录节点）、补前导 {@code /}、把连续的前导斜杠收成一个。
   * 第三件不是洁癖 —— {@code //host} 在 URL 里是「协议相对地址」，虽然拼出来仍是同源
   * （前缀是绝对地址），但让这种形态进库里，以后任何一处把它当成「以 / 开头的相对路径」
   * 用都会出岔子。
   */
  private static String normalizePath(String raw) {
    String p = raw.trim();
    if (p.isEmpty()) return "";
    while (p.startsWith("//")) p = p.substring(1);
    if (!p.startsWith("/")) p = "/" + p;
    return p.length() > PATH_MAX ? p.substring(0, PATH_MAX) : p;
  }

  /** {@code uk_nav_node} 落在 {@code (scope, parent_id, title)}，撞了要变成 400 而不是 500。 */
  private void insertOrConflict(NavNodeEntity e) {
    try {
      if (mapper.selectById(e.getId()) == null) mapper.insert(e);
      else mapper.updateById(e);
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "「" + e.getScope() + "」壳的同一层里已经有叫「" + e.getTitle() + "」的菜单了");
    }
  }

  /**
   * 丢掉所有产品的清单缓存，让下一次读重新去拉。
   *
   * <p>管理动作（增删改一个节点）都可能改变「哪些产品节点该被展开」，而「到底哪几个产品
   * 报了相关内容」正是拉一遍才知道的事。与其先拉一遍再决定清谁，不如全清 ——
   * 代价是几个网络请求，换掉一整类「改了配置但看的还是旧结果」的困惑。
   *
   * <p>为什么是「所有产品」：一个挂载节点只指向一个产品，但同一个产品可能被多个挂载节点
   * 引用，而 {@code invalidateAll} 本来就分不出来谁是谁（见 {@code MenuCandidateService}）。
   */
  private void dropCandidateCache() {
    candidates.invalidateAll();
  }

  private NavNodeEntity require(String id) {
    NavNodeEntity e = id == null ? null : mapper.selectById(id);
    if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜单节点不存在");
    return e;
  }

  // ------------------------------------------------------------------
  // 消费面
  // ------------------------------------------------------------------

  /**
   * 某租户里，**这个人**该看到哪棵菜单树。
   *
   * <p>{@code projectId} 参与判权，所以调用方必须把「用户当前在哪个项目」传进来
   * （壳里是 {@code X-Project-Id} 头）。同一个产品在不同项目里可以是不同角色，
   * 传空就等于按租户级的角色判 —— 工作台（还没进项目）正是这种情况。
   *
   * <p><b>租户拿不到（平台管理员没选租户 / 租户不存在）时返回空列表而不是报错</b>：
   * 侧栏是每个页面都要画的东西，让它因为「没选租户」而 500 会把整个壳带下水。
   *
   * <p>返回<b>所有壳的顶层节点</b>（每项带 {@code scope}），由前端按当前壳取用 ——
   * 与旧版 {@code /api/nav} 的口径一致，前端换壳时不必重新请求。
   */
  public List<Map<String, Object>> treeFor(String tenantId, String projectId) {
    if (tenantId == null || tenantId.isBlank()) return List.of();

    List<NavNodeEntity> rows = mapper.selectList(
        new LambdaQueryWrapper<NavNodeEntity>()
            .eq(NavNodeEntity::getEnabled, true)
            .orderByAsc(NavNodeEntity::getSortOrder)
            .orderByAsc(NavNodeEntity::getTitle));
    if (rows.isEmpty()) return List.of();

    Render r = new Render(
        projectId, licensedProducts(tenantId), access.isRealTenantAdmin(), policiesOf(tenantId));

    // 先把「挂载展开会吐出来的路径」收齐。接管判据就是它 —— 见方法注释最后一段：
    // 只有保证有替代品的才敢吞。放在正式渲染之前，是因为手工行要先知道哪些被接管了。
    for (NavNodeEntity n : rows) {
      if (!isMounted(n)) continue;
      collectPaths(r.productNode(n.getProduct(), n.getRef()), n.getProduct(), r.taken);
    }

    Map<String, List<NavNodeEntity>> byParent = new HashMap<>();
    for (NavNodeEntity n : rows) {
      if (isProductNode(n) && !r.shows(n)) continue;
      byParent.computeIfAbsent(nz(n.getParentId()), k -> new ArrayList<>()).add(n);
    }

    List<Map<String, Object>> out = new ArrayList<>();
    for (NavNodeEntity n : byParent.getOrDefault(ROOT, List.of())) {
      Map<String, Object> node = render(n, byParent, r);
      if (node != null) out.add(node);
    }
    return out;
  }

  /**
   * 渲染一个节点；返回 {@code null} = 这一项在本次结果里不出现。
   *
   * <p>三种「不出现」：产品节点而该产品未开通 / 被挂载展开接管 / 挂了权限词而这个人
   * 判不动（产品节点）；以及目录节点 {@code empty_policy = hide} 而展开后一个子项都没有。
   */
  private Map<String, Object> render(NavNodeEntity n, Map<String, List<NavNodeEntity>> byParent, Render r) {
    if (isMounted(n)) return renderMounted(n, r);

    if (isProductNode(n)) {
      String product = n.getProduct();
      if (r.taken.contains(slot(product, n.getScope(), n.getPath()))) return null;
      // 本组织没启用 / 你看不到 —— 走到这一步的只剩「承诺了始终出现」的那一支
      // （其余的在 treeFor 里就已经不出现），所以这里是置灰而不是抹掉。
      if (!r.available(product)) {
        return unavailable(toRow(n), r.unavailableReason(product));
      }
      if (hasPermWord(n.getPerm()) && !r.roleHas(product, n.getPerm())) return null;
    } else if (Boolean.TRUE.equals(n.getAdminOnly()) && !r.tenantAdmin) {
      return null;
    }

    Map<String, Object> row = toRow(n);
    // 每条产品节点都带上自己产品的页面地址：前端据此拼 `{frontendUrl}{path}` 的嵌入地址，
    // 缺了它这一项在侧栏里点不动（或跳到一个猜出来的默认端口，见 servicesFor 的说明）。
    // 为空 = 「服务注册」里还没登记地址，前端显示占位而不是嵌一个空 iframe。
    if (isProductNode(n)) row.put("frontendUrl", frontendUrl(n.getProduct()));
    // org 自有节点若判不动，**置灰而不是抹掉**（见类注释）：它是这个壳自己的功能，
    // 什么都不显示会让人以为壳里压根没有这一项。
    if (!isProductNode(n) && hasPermWord(n.getPerm()) && !r.roleHas(access.productCode(), n.getPerm())) {
      row.put("disabled", true);
      row.put("disabledReason", "需要本项目的相应权限（在产品角色里授权）");
    }

    List<Map<String, Object>> kids = new ArrayList<>();
    for (NavNodeEntity child : byParent.getOrDefault(n.getId(), List.of())) {
      Map<String, Object> kid = render(child, byParent, r);
      if (kid != null) kids.add(kid);
    }
    row.put("children", kids);

    if (nz(n.getPath()).isEmpty() && kids.isEmpty()) {
      if (!ALWAYS.equals(n.getEmptyPolicy())) return null;
      row.put("children", List.of(emptyPlaceholder()));
    }
    return row;
  }

  /**
   * 渲染一个挂载节点：把产品清单里 {@code ref} 指的那个节点**透出在这里**。
   *
   * <p><b>显示属性来自 org 侧那一行（标题、图标、顺序、空目录策略），可点属性来自产品
   * （路径、权限词、以及整棵子树）</b>。这样：
   *
   * <ul>
   *   <li>管理员给这一行起的名字就是侧栏里显示的名字（改它只影响显示，不会与产品失配）；</li>
   *   <li>产品换了页面路径、加了子菜单，侧栏自动跟上，不用回来改 org 的配置；</li>
   *   <li>挂叶子得到一条可点的项，挂目录得到一棵子树 —— 不会出现「目录 › 同名目录 › 子项」
   *       那种多一层的观感。</li>
   * </ul>
   *
   * <p>产品清单拉不到、或那个 id 在产品清单里消失了（产品改了 id 生成规则，见
   * {@code gen-menu.mjs} 的注释）时，这一支**降级成空目录**：{@code always} 保留并置灰说明，
   * {@code hide} 整枝不出现。不会因为某个产品挂了就让侧栏空掉。
   */
  private Map<String, Object> renderMounted(NavNodeEntity n, Render r) {
    Map<String, Object> row = toRow(n);
    row.put("mounted", true);
    // 挂载行自己也要带地址：产品拉不到时它是这一支唯一的外壳，
    // 前端要能把它当「这个产品的入口」用（与手工行同一口径）。
    row.put("frontendUrl", frontendUrl(n.getProduct()));

    // 不可用时**不拉产品清单**：这一支要渲染成置灰的空目录，拉回来也没用 —— 而每一次
    // 清单拉取在拉不到时都要等一次超时，侧栏是每个页面都画的。
    if (!r.available(n.getProduct())) {
      return unavailable(row, r.unavailableReason(n.getProduct()));
    }

    Map<String, Object> pn = r.productNode(n.getProduct(), n.getRef());

    List<Map<String, Object>> kids = new ArrayList<>();
    if (pn != null) {
      String perm = str(pn.get("perm"));
      if (hasPermWord(perm) && !r.roleHas(n.getProduct(), perm)) {
        // 产品那一项自己就判不动：整支不出现（这是产品节点的口径，见类注释）
        if (!ALWAYS.equals(n.getEmptyPolicy())) return null;
        row.put("path", "");
        row.put("children", List.of(emptyPlaceholder()));
        return row;
      }
      // 路径与权限词取自产品：改了它们在渲染时也不生效，所以不读 org 侧那一行的这两列
      row.put("path", str(pn.get("path")));
      row.put("perm", perm);
      for (Map<String, Object> kid : children(pn)) {
        Map<String, Object> rendered = renderProductNode(kid, n.getProduct(), r);
        if (rendered != null) kids.add(rendered);
      }
    }
    row.put("children", kids);

    // 「挂了个叶子」不是空目录：这一行自己就有路径（上面取自产品那一项），
    // 它就是那个可点项。不加 path 判据的话，挂叶子会被当成展开为空的目录而整支消失 ——
    // 而它恰恰是最常见的一种挂法（挂产品侧栏里的某一个页面）。
    if (kids.isEmpty() && str(row.get("path")).isEmpty()) {
      if (!ALWAYS.equals(n.getEmptyPolicy())) return null;
      row.put("children", List.of(emptyPlaceholder()));
    }
    return row;
  }

  /** 产品清单里的一项 → 侧栏节点（{@code null} = 这一项不出现）。子树递归。 */
  private Map<String, Object> renderProductNode(Map<String, Object> pn, String product, Render r) {
    String perm = str(pn.get("perm"));
    if (hasPermWord(perm) && !r.roleHas(product, perm)) return null;

    List<Map<String, Object>> kids = new ArrayList<>();
    for (Map<String, Object> kid : children(pn)) {
      Map<String, Object> rendered = renderProductNode(kid, product, r);
      if (rendered != null) kids.add(rendered);
    }

    String path = str(pn.get("path"));
    if (path.isEmpty() && kids.isEmpty()) return null; // 空目录：产品报了个没内容的分组，不渲染

    Map<String, Object> row = new LinkedHashMap<>();
    // 产品节点的 id 只在它自己的清单里唯一，加上产品码才是全局唯一的前端 key
    row.put("id", product + "/" + str(pn.get("id")));
    row.put("scope", str(pn.get("scope")));
    row.put("parentId", "");
    row.put("label", str(pn.get("label")));
    row.put("path", path);
    row.put("icon", str(pn.get("icon")));
    row.put("perm", perm);
    row.put("product", product);
    row.put("children", kids);
    // 产品这一支的可见性由许可与角色决定，没有「仅管理员可见」与空目录策略这两回事
    row.put("adminOnly", false);
    row.put("mounted", false);
    row.put("ref", "");
    row.put("emptyPolicy", HIDE);
    row.put("enabled", true);
    row.put("sortOrder", sortOf(pn));
    row.put("frontendUrl", frontendUrl(product));
    return row;
  }

  /** 清单项的 {@code children}（缺字段与空数组在这里是同一件事）。 */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> children(Map<String, Object> node) {
    Object kids = node.get("children");
    return kids instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
  }

  /** 把一棵产品子树里所有有路径的节点收进 {@code taken}（接管判据，见类注释）。 */
  private static void collectPaths(Map<String, Object> pn, String product, Set<String> taken) {
    if (pn == null) return;
    String path = str(pn.get("path"));
    if (!path.isEmpty()) taken.add(slot(product, str(pn.get("scope")), path));
    for (Map<String, Object> kid : children(pn)) collectPaths(kid, product, taken);
  }

  /** {@code always} 空目录里的占位项：这一支照常出现，但里面没得点。 */
  private static Map<String, Object> emptyPlaceholder() {
    return emptyPlaceholder("该产品未对本租户开通，或你还没有对应角色");
  }

  /**
   * 同上，但由调用方给出<b>具体成因</b>。
   *
   * <p>V27 之后「这一支为什么是空的」有三种成因：平台没开通、本组织没启用、你不在可见范围内。
   * 一律套用上面那句默认文案，会把第三种说成第一种 —— 管理员照着「未开通」去平台后台查，
   * 而那边明明开着。
   */
  private static Map<String, Object> emptyPlaceholder(String reason) {
    Map<String, Object> row = new LinkedHashMap<>();
    // 空串是有意的：它不是一个可去的地址。前端的高亮必须跳过 disabled 项，
    // 否则空串会成为「最短前缀」命中任意路径（见 config/nav.ts）。
    row.put("path", "");
    row.put("label", "暂无可用的入口");
    // 图标名必须取自前端 config/navIcons.ts —— 写一个不在表里的名字不会报错，只会静默不渲染图标
    row.put("icon", "BlockOutlined");
    row.put("disabled", true);
    row.put("disabledReason", reason);
    row.put("children", List.of());
    return row;
  }

  /**
   * 一个「留在树里、但这个人用不了」的节点：置灰 + 说明 + 空目录。
   *
   * <p>只有承诺了「始终出现」的节点才走得到这里（见 {@link Render#shows}），也就是数据地图
   * 那一支。一般的产品节点不可用是<b>整条不出现</b>（{@code treeFor} 的过滤），两者不是一回事：
   * 前者是「这一支本该在这里，只是你用不了」，后者是「这个产品与你无关」。
   *
   * <p>{@code path} 必须清掉 —— 留着原路径会让前端把它画成一个可点的入口，点进去恰好是
   * 一个 403，比置灰更糟。
   */
  private static Map<String, Object> unavailable(Map<String, Object> row, String reason) {
    row.put("path", "");
    row.put("disabled", true);
    row.put("disabledReason", reason);
    row.put("children", List.of(emptyPlaceholder(reason)));
    return row;
  }

  /**
   * 某租户已开通的产品及其前端地址（消费面）。
   *
   * <p><b>与菜单树的区别</b>：菜单回答「侧栏有哪些入口」，按菜单表来；这里回答
   * 「每个产品的站点根在哪」，按许可来，一个产品一条。
   *
   * <p>为什么不能直接复用菜单：开通了许可但一条菜单都没配是完全正常的（侧栏看不到入口，
   * 但「进入项目」按钮仍应能进去）。拿菜单当产品目录，那种租户点「进入项目」会拿到空地址
   * —— 与「服务注册里没配地址」表现完全一样，排查时分不清是哪一处没配。
   */
  public List<Map<String, Object>> servicesFor(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) return List.of();
    List<String> licensed = licensedProducts(tenantId);
    List<Map<String, Object>> out = new ArrayList<>(licensed.size());
    for (String product : licensed) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("product", product);
      // 为空 = 「服务注册」里还没登记前端地址。前端据此报「未配置前端地址」，
      // 而不是跳到一个猜出来的默认端口上 —— 那个默认值在跨服务器部署时指向用户自己的 localhost。
      item.put("frontendUrl", frontendUrl(product));
      out.add(item);
    }
    return out;
  }

  private String frontendUrl(String product) {
    ServiceRegistry.Entry svc = registry.get(product);
    return svc == null ? "" : nz(svc.frontendUrl());
  }

  /** 该租户开通的产品；**没有许可行 = 一个都没开**（fail-closed，见字段注释）。 */
  private List<String> licensedProducts(String tenantId) {
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    if (lic == null) return List.of();
    List<String> modules = Jsons.strings(lic.getModules());
    return modules == null ? List.of() : modules;
  }

  /**
   * 该租户<b>显式配过</b>的模块策略，按 product 索引（消费面）。
   *
   * <p>读的是与 {@link #licensedProducts} 同一行，但<b>fail 方向刻意相反</b>：许可那份
   * 没有行 = 一个都没开（fail-closed，那是「能开什么」的上限，宁可少不可多）；策略这份
   * 没有行 = <b>什么都不判</b>（不让一份缺失的配置把侧栏打空）。两者都是有意的，别合并。
   *
   * <p><b>逐项丢弃非法项，而不是整份拒</b>：{@code product} 不在许可模块表里、或
   * {@code visibleTo} 是个不认识的档名，都只跳过这一项。整份拒会让一行脏数据把该租户
   * 全部模块策略一起废掉，而表现是「明明配了却没反应」，没有任何报错。
   *
   * <p>「策略里出现平台没开通的模块」不在这里挡：{@link Render#shows} 先判许可，
   * 没开通的产品根本走不到读策略那一步，于是自然无效（写侧另有 400，见
   * {@code ModulePolicyService}）。
   */
  private Map<String, ApiModels.ModulePolicyDto> policiesOf(String tenantId) {
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    if (lic == null) return Map.of();
    List<Map<String, Object>> raw = Jsons.maps(lic.getModulePolicies());
    if (raw.isEmpty()) return Map.of();
    Map<String, ApiModels.ModulePolicyDto> out = new LinkedHashMap<>();
    for (Map<String, Object> item : raw) {
      String product = str(item.get("product"));
      if (!ProductCodes.LICENSE_MODULES.contains(product)) continue;
      String visibleTo = str(item.get("visibleTo"));
      if (!visibleTo.isEmpty() && !VISIBLE_TO.contains(visibleTo)) continue;
      // 缺 enabled / 缺 visibleTo 都按默认补 —— 一份写得半全的策略不该整项失效。
      out.put(product, new ApiModels.ModulePolicyDto(
          product,
          !Boolean.FALSE.equals(item.get("enabled")),
          visibleTo.isEmpty() ? defaultVisibleTo(product) : visibleTo));
    }
    return out;
  }

  /**
   * 某个模块的默认可见范围（照原型）。
   *
   * <p>warehouse 是 {@code all_members}、其余是 {@code role_holders}：数仓建模是每个项目
   * 都会用到的底座，再收一道会让人进了项目却看不到建模入口。
   *
   * <p><b>这只是「页面显示的初值」</b> —— 生效侧「没配过 = 不判」（见类注释第三层），
   * 两者不对称是刻意的：把生效默认也按这里判，升级后一批人今天看得到的入口会静默消失。
   */
  private static String defaultVisibleTo(String product) {
    return "warehouse".equals(product) ? "all_members" : DEFAULT_VISIBLE_TO;
  }

  /**
   * {@code (product, scope, path)} 的拼接键 —— 接管判据用的就是它。
   *
   * <p>用 {@code \u0000} 分隔而不是 {@code :}/{@code /}：产品码、壳名、路径都可能含这些
   * 常见字符（路径本身就带 {@code /}），拼起来会撞键。NUL 不可能出现在这三处。
   */
  private static String slot(String product, String scope, String path) {
    return product + '\u0000' + scope + '\u0000' + path;
  }

  /** 清单项的 {@code sort} 字段（候选生成时每个分组内从 10 递增，见 {@code gen-menu.mjs}）。 */
  private static int sortOf(Map<String, Object> node) {
    Object v = node.get("sort");
    return v instanceof Number n ? n.intValue() : 0;
  }

  /**
   * 一次渲染的共享状态：许可、角色、产品清单缓存。
   *
   * <p><b>角色按产品算一次</b>：一次 {@code currentRole} 要查成员关系 / 项目成员 / 平台授权
   * 三张表，逐条菜单去算会把它乘以菜单条数，而侧栏每次进项目、每次刷新都要重建一次。
   *
   * <p><b>清单缓存用 {@code containsKey} 判「拉过没有」而不是 {@code get() == null}</b>：
   * {@code null} 是合法值（= 拉不到，见 {@code MenuCandidateService.menusOrNull}），
   * 塌成「没拉过」会让每次判权都重新等一次超时。
   */
  private final class Render {
    private final String projectId;
    private final List<String> licensed;
    private final boolean tenantAdmin;
    /**
     * 这个租户<b>显式配过</b>的模块策略，按 product 索引。
     *
     * <p><b>缺项 = 没配过 = 不判</b>（见类注释第三层）—— 这是老租户升级后侧栏不变的唯一依据，
     * 所以这里只装「库里真有的那些」，不预先用默认值补全（补全就等于默认生效了）。
     */
    private final Map<String, ApiModels.ModulePolicyDto> policies;
    private final Map<String, String> roleByProduct = new HashMap<>();
    private final Map<String, List<Map<String, Object>>> reported = new HashMap<>();
    private final Set<String> taken = new LinkedHashSet<>();

    Render(String projectId, List<String> licensed, boolean tenantAdmin,
        Map<String, ApiModels.ModulePolicyDto> policies) {
      this.projectId = projectId;
      this.licensed = licensed;
      this.tenantAdmin = tenantAdmin;
      this.policies = policies;
    }

    /**
     * 这个产品节点该不该留在树里。
     *
     * <p>三级依次判：平台未开通（既有行为）→ 本组织未启用 → 当前人不可见。
     * 后两级不通过时<b>默认整条不出现</b>；只有承诺了「始终出现」的（{@code empty_policy
     * = always}，即数据地图那一支，见 {@link #ALWAYS} 的注释）才留在树里、交给
     * {@code render} 置灰说明。
     *
     * <p>判据挂在 {@code empty_policy} 上而不是硬编产品码：那条策略的注释里写的就是
     * 「数据地图那一支始终出现，未开通或未派角色时置灰并说明」，复用它，将来别的产品
     * 想要同样的待遇只需要改配置。
     */
    boolean shows(NavNodeEntity n) {
      String product = nz(n.getProduct());
      if (!licensed.contains(product)) return false;
      if (available(product)) return true;
      return ALWAYS.equals(n.getEmptyPolicy());
    }

    /**
     * 这个产品这次渲染里<b>可用</b> = 本组织启用了 且 当前人看得见。
     *
     * <p>没配过策略的产品一律可用 —— 见 {@link #policies} 的注释。
     */
    boolean available(String product) {
      ApiModels.ModulePolicyDto pol = policies.get(product);
      if (pol == null) return true;
      if (!pol.enabled()) return false;
      return visible(product, pol.visibleTo());
    }

    /** 四档见 {@link #VISIBLE_TO}。 */
    private boolean visible(String product, String visibleTo) {
      if (visibleTo == null || visibleTo.isBlank()) return true;
      if (tenantAdmin) return true;
      // `visibleTo` 是**项目内**的概念（「谁能在项目侧栏里看见它」）。工作台壳还没有项目
      // 上下文（X-Project-Id 为空）时判不了，这里**不判**而不是判否：判否会让工作台里挂着的
      // 那几个产品入口对所有人消失，而表现只是「侧栏少了一条」——不会有任何报错。
      if (projectId == null || projectId.isBlank()) return true;
      return switch (visibleTo) {
        case "all_members" -> access.isProjectMember(projectId);
        case "role_holders" -> access.currentRole(projectId, product).isPresent();
        case "project_admin" -> access.isProductAdmin(projectId, product);
        case "tenant_admin" -> false;
        // 写入时已按 VISIBLE_TO 归一；读到没见过的档名时**不判**而不是判否 ——
        // 一行脏数据不该让某个产品对所有人消失。
        default -> true;
      };
    }

    /** 置灰时给出的成因，与 {@link #shows} 的两级一一对应。 */
    String unavailableReason(String product) {
      ApiModels.ModulePolicyDto pol = policies.get(product);
      if (pol != null && !pol.enabled()) {
        return "本组织未启用该模块（工作台 › 模块管理）";
      }
      return "你不在本组织允许看见该模块的范围内（工作台 › 模块管理）";
    }

    boolean roleHas(String product, String perm) {
      if (!roleByProduct.containsKey(product)) {
        roleByProduct.put(product, access.currentRole(projectId, product).orElse(null));
      }
      return access.roleHas(product, roleByProduct.get(product), perm);
    }

    /** 产品自报的清单；{@code null} = 拉不到（降级判据，见类注释）。 */
    List<Map<String, Object>> menus(String product) {
      if (!reported.containsKey(product)) {
        reported.put(product, candidates.menusOrNull(product));
      }
      return reported.get(product);
    }

    /** 清单里 {@code ref} 指的那个节点；拉不到或找不到都返回 {@code null}。 */
    Map<String, Object> productNode(String product, String ref) {
      List<Map<String, Object>> menus = menus(product);
      return menus == null ? null : findByRef(menus, ref);
    }
  }

  /**
   * 管理面与消费面共用一个行形状。
   *
   * <p>不拆成两个方法：字段几乎完全重合，拆开只会让两边慢慢漂移。字段名用 {@code label}
   * 而不是表列名 {@code title}，是为了与前端 {@code NavItem} 的既有词汇一致
   * （菜单页表头、权限词下拉、高亮匹配都叫 label）。
   */
  private static Map<String, Object> toRow(NavNodeEntity e) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", e.getId());
    row.put("scope", e.getScope());
    row.put("parentId", nz(e.getParentId()));
    row.put("label", e.getTitle());
    row.put("path", nz(e.getPath()));
    row.put("icon", nz(e.getIcon()));
    row.put("perm", nz(e.getPerm()));
    row.put("sortOrder", e.getSortOrder() == null ? 0 : e.getSortOrder());
    row.put("enabled", Boolean.TRUE.equals(e.getEnabled()));
    row.put("adminOnly", Boolean.TRUE.equals(e.getAdminOnly()));
    row.put("product", nz(e.getProduct()));
    row.put("ref", nz(e.getRef()));
    row.put("mounted", Boolean.TRUE.equals(e.getMounted()));
    row.put("emptyPolicy", nz(e.getEmptyPolicy()).isEmpty() ? HIDE : e.getEmptyPolicy());
    row.put("createdAt", e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
    return row;
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static String str(Object v) {
    return v == null ? "" : String.valueOf(v).trim();
  }

  /** 管理面请求体。字段可空 = 「不改」，只有新建时才会用缺省值补。 */
  public static class NavNodeReq {
    /** {@link #SCOPES} 之一。新建必填；改时不能变。 */
    public String scope;
    /** 父节点 id；不传/空串 = 壳下的顶层节点（用户说的「主菜单」）。 */
    public String parentId;
    public String title;
    /** 空串 = 目录节点。 */
    public String path;
    public String icon;
    public String perm;
    public Integer sortOrder;
    public Boolean enabled;
    /** 仅租户管理员可见；只能用在 org 自有节点上（{@code product} 为空）。 */
    public Boolean adminOnly;
    /** 产品码；空串 = org 自己的页面。 */
    public String product;
    /** {@link #mounted} 为真时必填：产品清单里那个节点的 id。 */
    public String ref;
    /** 是否挂载：内容跟随产品自报的清单。挂载时 {@code ref} 必须能在清单里找到。 */
    public Boolean mounted;
    /** {@link #EMPTY_POLICIES} 之一；不传时新建默认 {@link #HIDE}。 */
    public String emptyPolicy;
  }
}
