package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwai.platform.auth.LlmCrypto;
import com.dwai.platform.internal.ServiceRegistry;
import com.dwai.platform.meta.dto.ApiModels;
import com.dwai.platform.meta.entity.NavEntryLinkEntity;
import com.dwai.platform.meta.entity.NavEntryProductLinkEntity;
import com.dwai.platform.meta.entity.NavNodeEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.mapper.NavEntryLinkMapper;
import com.dwai.platform.meta.mapper.NavEntryProductLinkMapper;
import com.dwai.platform.meta.mapper.NavNodeMapper;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * <h2>五类节点，判据只看几列（V29 起由三类扩到五类）</h2>
 *
 * <ul>
 *   <li><b>org 自有节点</b>（{@code product = ''}）：{@code path} 是 org 的完整路由
 *       （{@code /org/workbench/users}），直接可用。工作台壳与项目壳的 org 自有菜单
 *       现在是 {@code V23} 的种子数据，不再由前端硬编码。</li>
 *   <li><b>产品节点</b>（{@code product != ''}）：分手工复制（{@code mounted = false}，
 *       {@code path} 是子应用内路径，前端拼 {@code /org/embed/{product}}）与
 *       <b>挂载</b>（{@code mounted = true}，按 {@code (product, ref)} 实时展开）两种。</li>
 *   <li><b>入口页</b>（{@link NavNodeEntity#getEntryPage()}，V29）：自己是一排 Tab，
 *       表里列出 {@code nav_entry_links} 挂的菜单。被挂的菜单<b>在原位置也还在</b> ——
 *       那是引用，不是父子。</li>
 *   <li><b>外链菜单</b>（{@link NavNodeEntity#getExternalUrl()} 非空，V29）：指向平台外的
 *       一个地址，可内嵌（{@code embed}）可新标签页打开（{@code jump}）。</li>
 * </ul>
 *
 * <p>入口页与外链<b>都与 {@code product} 互斥、且互相排斥</b>（{@link #applyEntryAndExternal}）；
 * 判据是 {@link #isEntry} / {@link #isExternal} 两个静态方法，别在别处另写一份。
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

  /**
   * {@code nav_nodes.open_mode} 的白名单（V29 外链菜单用）。
   *
   * <p>与 {@link #SCOPES} 同样的「写入时就拒绝」：写进一个谁也不认的档名，
   * 表现为接口 200、而打开方式按某个没人预期的默认值走。
   */
  public static final Set<String> OPEN_MODES = Set.of("embed", "jump");

  /**
   * {@code nav_nodes.auth_mode} 的白名单（V29 外链菜单用）。
   *
   * <p>{@code basic} 单独说明：它<b>不会带来任何自动化</b>（浏览器禁止
   * {@code https://user:pass@host} 与代填第三方登录表单），保留它是因为用户要求
   * 「账号密码」这一档存在，实际用途是平台管理员保存备查。
   */
  public static final Set<String> AUTH_MODES = Set.of("none", "token", "basic");

  /**
   * {@code nav_nodes.visibility} 的白名单（V29 外链菜单的独立可见性开关）。
   *
   * <p><b>只有两档，不是四档</b>：模块可见范围那四档里的后三档都要 {@code product} 才能算
   * （{@code currentRole(projectId, product)}，见 {@link Render#visible}），而外链菜单
   * {@code product} 必为空。少的是能力，不是漏了。
   */
  public static final Set<String> VISIBILITIES = Set.of("all", "tenant_admin");

  /** 外链可见性：仅租户管理员（{@link #VISIBILITIES} 里唯一会拦人的那一档）。 */
  public static final String VIS_TENANT_ADMIN = "tenant_admin";

  /** 外链打开方式：内嵌进壳。选它才会出现「在新标签页打开」的兜底按钮。 */
  public static final String OPEN_EMBED = "embed";

  /** 与 {@code nav_nodes.title} 同宽（见 {@code V23__nav_nodes.sql}）。 */
  private static final int TITLE_MAX = 64;
  private static final int REF_MAX = 128;
  private static final int PATH_MAX = 512;

  /**
   * 入口页上一条 Tab 自己填的名称的上限（V31）。与 {@link #TITLE_MAX} 同宽：它替换的就是
   * 那个标题在 Tab 上显示的位置，比它更长的名字在这里被拒、在标题那里也一样被拒。
   */
  private static final int LABEL_MAX = TITLE_MAX;

  /**
   * {@code links[]} 里两端引用的类型标记（V31）：{@code node} = 本站菜单（{@code nav_nodes.id}），
   * {@code product} = 产品清单里的节点（{@code product + ref}）。
   *
   * <p>这两个词是**请求与响应共用**的契约：写进去的 {@code kind} 会原样回显给管理页，
   * 改成别的写法会让前端的分流静默失配（全部当成 {@code node} → 提交回来时 400）。
   */
  private static final String KIND_NODE = "node";
  private static final String KIND_PRODUCT = "product";

  /** 顶层节点的 {@code parent_id}（不是 {@code null}，理由见实体注释）。 */
  private static final String ROOT = "";

  /**
   * 同层排序值的步长：新建时取「同层最大 + 它」，移动后整层按 {@code 10,20,30…} 重编号。
   *
   * <p>为什么不是 1,2,3：留出「日后想在两条之间插一条」的余量 —— 把中间值给新节点即可，
   * 不必整层重排。菜单是低频配置数据，这个余量够用。
   *
   * <p><b>不引入分数索引（LexoRank / 分数排序）</b>：那是为「拖动排序 + 只改一行 + 多人并发」
   * 设计的（Jira 用它做拖动），代价是 key 会越插越长、需要周期性重平衡。这里的写路径是
   * 上移/下移一格，同层通常不到 10 条，整层重编号的成本可以忽略 —— 那套复杂度换不来东西。
   */
  private static final int SORT_STEP = 10;

  private final NavNodeMapper mapper;
  /**
   * 入口页挂了哪些菜单（V29）。引用关系，与 {@link #mapper} 那棵父子树是两回事。
   */
  private final NavEntryLinkMapper links;
  /**
   * 入口页挂的**产品清单节点**（V30）。和 {@link #links} 是同一件事的两半：那一张装的是
   * {@code nav_nodes.id}，这一张装的是产品清单里的 id（在 {@code nav_nodes} 里没有行）。
   * 为什么不合成一张，见 {@code V30__nav_entry_product_links.sql} 的注释。
   */
  private final NavEntryProductLinkMapper productLinks;
  /** 外链菜单的 token / 密码加密落库（V29），与 LLM Key、调度器 Token 同一把密钥。 */
  private final LlmCrypto crypto;
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
      NavEntryLinkMapper links,
      NavEntryProductLinkMapper productLinks,
      LlmCrypto crypto,
      TenantLicenseMapper licenses,
      ServiceRegistry registry,
      MenuCandidateService candidates,
      AccessService access) {
    this.mapper = mapper;
    this.links = links;
    this.productLinks = productLinks;
    this.crypto = crypto;
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

  /** 这条节点是**入口页**（V29）：自己是一排 Tab，内容在 {@code nav_entry_links} 里。 */
  private static boolean isEntry(NavNodeEntity n) {
    return Boolean.TRUE.equals(n.getEntryPage());
  }

  /** 这条节点是**外链菜单**（V29）：指向平台外的一个地址。 */
  private static boolean isExternal(NavNodeEntity n) {
    return !nz(n.getExternalUrl()).isEmpty();
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

    List<NavNodeEntity> rows = mapper.selectList(q);
    return tree(rows, entryLinksByEntry(rows));
  }

  /**
   * 入口页 id → 它挂的**全部 Tab**（V29 的本站菜单 + V30 的产品清单节点，V31 起合成一份）。
   * 管理面回显用。
   *
   * <p>只查一次而不是每个入口页查一次：菜单管理页一屏可能就是几十个节点，
   * 一个个查会让这个接口的往返次数跟着节点数走。
   *
   * <p><b>消费面不走这里</b>（{@link #entryPage} 另有实现）：那边要把每个被挂的菜单
   * 按许可与角色裁一遍，只有真去渲染时才做得了。
   *
   * <p>形状（<b>已按 Tab 顺序排好</b>）：
   * {@code [{kind:'node', target, label, sortOrder}]} 或
   * {@code [{kind:'product', product, ref, label, sortOrder}]}。
   * 前端按 {@code kind} 分流 —— <b>不要让它按 id 的形状去猜</b>，猜错的后果见
   * {@link #applyLinks} 的注释（报错指向的原因与真实原因差得很远）。
   *
   * <p><b>不在这里回显被挂菜单的标题</b>：本站菜单的标题前端手里有整棵树；
   * 产品清单节点的标题要去拉 {@code menu.json}，等于给每一次菜单管理页的加载都挂上一次
   * 可能超时的外部请求（产品挂了就是每次等一次超时）。管理页本来就为「候选清单」拉过
   * 一次（{@code navCandidates}），前端拿已有的那份索引就能把 {@code ref} 翻成标题 ——
   * 拉不到时降级显示 id 本身。{@code label} 只是管理员自己填的 Tab 名，为空即未改名。
   */
  private Map<String, List<Map<String, Object>>> entryLinksByEntry(List<NavNodeEntity> rows) {
    List<String> entryIds = new ArrayList<>();
    for (NavNodeEntity n : rows) {
      if (isEntry(n)) entryIds.add(n.getId());
    }
    if (entryIds.isEmpty()) return Map.of();

    Map<String, List<Map<String, Object>>> out = new HashMap<>();

    // 先本站菜单、后产品节点：这个插入序就是 V31 之前的旧顺序，而 sortByTabOrder 是稳定排序，
    // 于是 V31 之前存的老行（sort_order 全是 0，迁移刻意不回填）排完仍是旧顺序。
    for (NavEntryLinkEntity l : links.selectList(
        new LambdaQueryWrapper<NavEntryLinkEntity>().in(NavEntryLinkEntity::getEntryId, entryIds))) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("kind", KIND_NODE);
      item.put("target", nz(l.getTargetId()));
      item.put("label", nz(l.getLabel()));
      item.put("sortOrder", l.getSortOrder() == null ? 0 : l.getSortOrder());
      out.computeIfAbsent(l.getEntryId(), k -> new ArrayList<>()).add(item);
    }
    for (NavEntryProductLinkEntity l : productLinks.selectList(
        new LambdaQueryWrapper<NavEntryProductLinkEntity>()
            .in(NavEntryProductLinkEntity::getEntryId, entryIds)
            .orderByAsc(NavEntryProductLinkEntity::getSortOrder))) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("kind", KIND_PRODUCT);
      item.put("product", nz(l.getProduct()));
      item.put("ref", nz(l.getRef()));
      item.put("label", nz(l.getLabel()));
      item.put("sortOrder", l.getSortOrder() == null ? 0 : l.getSortOrder());
      out.computeIfAbsent(l.getEntryId(), k -> new ArrayList<>()).add(item);
    }

    for (List<Map<String, Object>> one : out.values()) sortByTabOrder(one);
    return out;
  }

  /**
   * 按 Tab 顺序排（V31）：{@code sortOrder} 升序，**稳定** —— 并列的保持传入的相对顺序。
   *
   * <p>这个「稳定」是这份实现的关键，不是随手写的：管理员存过一次的入口页每行都有唯一序号，
   * 稳不稳定看不出差别；而 V31 之前存的老行 {@code sort_order} 全是 0（迁移刻意不回填），
   * 排完必须还是「本站菜单按自己的顺序在前、产品那些按勾选顺序在后」—— 那正是旧行为。
   * {@code List.sort} 走 TimSort，是稳定排序，这里依赖这一点。
   */
  private static void sortByTabOrder(List<Map<String, Object>> one) {
    one.sort(Comparator.comparingInt((Map<String, Object> m) -> {
      Object v = m.get("sortOrder");
      return v instanceof Integer ? (Integer) v : 0;
    }));
  }

  /** 把一批节点按 {@code parentId} 组成树，同级按 {@code sortOrder → title} 排。 */
  private static List<Map<String, Object>> tree(
      List<NavNodeEntity> rows, Map<String, List<Map<String, Object>>> entryLinks) {
    Map<String, List<NavNodeEntity>> byParent = new HashMap<>();
    for (NavNodeEntity n : rows) {
      byParent.computeIfAbsent(nz(n.getParentId()), k -> new ArrayList<>()).add(n);
    }
    return childrenOf(ROOT, byParent, entryLinks);
  }

  private static List<Map<String, Object>> childrenOf(
      String parentId, Map<String, List<NavNodeEntity>> byParent,
      Map<String, List<Map<String, Object>>> entryLinks) {
    List<NavNodeEntity> kids = byParent.getOrDefault(parentId, List.of());
    List<Map<String, Object>> out = new ArrayList<>(kids.size());
    for (NavNodeEntity n : kids) {
      Map<String, Object> row = toRow(n);
      // 入口页把挂了哪些 Tab 一并回传 —— 管理页的编辑表单要拿它回填那串行。
      //
      // **一份带类型的列表，不是两个扁平数组**（V31 改的，之前是 linkTargets + productLinks）：
      // 现在每一条还要带自己的 `label` 与 `sortOrder`，而「先本站后产品」这个分法本身也过时了
      // —— Tab 顺序由行序决定，两类是混排的，拆成两个数组就再也拼不回一个顺序。
      // 带上 `kind` 前端就不必靠 id 的形状猜：猜错的后果是把清单 id 当成 nav id 提交回来，
      // 服务端 400 报的是「要挂的菜单不存在」，与真实原因（分错类了）差得很远。
      if (isEntry(n)) {
        row.put("links", entryLinks.getOrDefault(n.getId(), List.of()));
      }
      row.put("children", childrenOf(n.getId(), byParent, entryLinks));
      out.add(row);
    }
    return out;
  }

  public Map<String, Object> create(NavNodeReq req) {
    NavNodeEntity e = new NavNodeEntity();
    // 与旧 nav_items 的 nav- 前缀同一套生成惯例（种子数据用的是可读的 nav-sys-*）
    e.setId("nav-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    apply(e, req, true);
    // 关联行的校验在写库之前跑完（见 applyLinks）：校验不过时这一行还没落库，不留脏数据
    applyLinks(e, req, true);
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
    applyLinks(e, req, false);
    insertOrConflict(e);
    dropCandidateCache();
    return toRow(e);
  }

  /**
   * 删掉一个节点<b>连同它的整棵子树</b>。
   *
   * <p>返回 {@code subtree} = 连带删掉的子孙条数（不含自己）。<b>不做「有子节点就拒绝」</b>：
   * 拒绝的话管理员得从下往上一个一个删，而他要表达的意思恰恰是「这一支不要了」。
   * 级联删除的后果由前端二次确认承载，返回的条数就是那句确认里的数字。
   *
   * <p><b>但「这一支里有 org 自己的菜单」会拒</b>（400，见下方守卫）：那不是「有几行」
   * 的问题，是那几行删了<b>找不回来</b>（路径是内部知识、外链凭据只存不回显）——
   * 只能停用。判据是整棵子树，不然「删产品父节点」就成了绕过守卫的后门。
   *
   * <p>返回 {@code mounted} = 这一支里有多少行是挂载的：那些行没有本地内容，
   * 删掉它们只是「不再跟随产品」，与删掉手工行不是一回事，值得让管理员看见。
   *
   * <p><b>V29 起还要清两类关联行</b>：这一支作为入口页挂出去的（{@code entry_id}），
   * 以及这一支被别的入口页挂着的（{@code target_id}）。后者容易漏 —— 删的是<b>被挂</b>
   * 的那条菜单，而容器在别处、看着完全没事，只有进那个入口页才会发现表里多了一行空的。
   */
  public Map<String, Object> delete(String id) {
    NavNodeEntity e = require(id);
    List<NavNodeEntity> doomed = subtreeOf(e, allRows());

    // org 自己的菜单（product 为空：页面 / 目录 / 入口页 / 外链）**只能停用，不能删除**。
    // 它们是平台骨架，`/org/project/{code}/members` 这类路径是内部知识 —— 删掉之后
    // 菜单项没了，管理员不知道填什么才能恢复（外链的凭据还只存不回显，是真空了）。
    // 判据与前端「删除按钮给不给」同源，也照 `isProductNode` 这一处，不另写一套。
    //
    // **要连整棵子树一起看**：`delete()` 是级联的，只判自己等于留了个后门 ——
    // 建一个产品父节点、把 org 自有菜单挂在下面，删父就把它一起带走了。
    // `subtreeOf` 的第一个元素就是自己（BFS 且 root 先入列，见 :423-424），
    // 所以下面这一个判断同时说清「是它自己」还是「是它子树里的某一个」。
    NavNodeEntity blocker = doomed.stream().filter(n -> !isProductNode(n)).findFirst().orElse(null);
    if (blocker != null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          blocker.getId().equals(id)
              ? "org 自己的菜单不能删除，只能停用"
              : "这一支下面的「" + blocker.getTitle() + "」是 org 自己的菜单，不能连带删除");
    }

    int mounted = (int) doomed.stream().filter(NavNodeService::isMounted).count();
    List<String> doomedIds = doomed.stream().map(NavNodeEntity::getId).toList();
    links.delete(new LambdaQueryWrapper<NavEntryLinkEntity>()
        .in(NavEntryLinkEntity::getEntryId, doomedIds));
    links.delete(new LambdaQueryWrapper<NavEntryLinkEntity>()
        .in(NavEntryLinkEntity::getTargetId, doomedIds));
    // V30 那张表**只有 entry 一侧要清**：它装的是产品清单里的 id，那些 id 不是 nav_nodes 的行，
    // 删 nav_nodes 不会让它们变成悬空；反过来「这一支被谁挂着」在这一类里不存在
    // （产品清单节点没有自己的管理页，也就没有「从产品侧删掉」这条路）。
    productLinks.delete(new LambdaQueryWrapper<NavEntryProductLinkEntity>()
        .in(NavEntryProductLinkEntity::getEntryId, doomedIds));
    for (NavNodeEntity n : doomed) mapper.deleteById(n.getId());
    // 删掉挂载行后这一支立刻回落到手工行 —— 不丢缓存的话，管理员删完刷新侧栏，
    // 看到的还是产品展开出来的那一支，会以为没删掉。
    dropCandidateCache();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("subtree", doomed.size() - 1);
    out.put("mounted", mounted);
    return out;
  }

  /**
   * 把一个节点在同层里上移 / 下移一格（{@code delta} = -1 / 1），并把该层重编号成 10/20/30。
   *
   * <p><b>「哪一层」由节点自身决定，调用方说不出别的层</b>：{@code scope} 与
   * {@code parent_id} 都从被移动的那个节点上读，请求体里没有这两个字段。让调用方传的话，
   * 「传错层」这类请求在协议上就成立了 —— 而它的后果是静默改错一层：没有报错，
   * 只有某个用户看不见的地方顺序变了。
   *
   * <p><b>为什么整层重编号，而不是只交换两个值</b>：老数据里的并列（同层全是 0）与导入
   * 带来的外来值（产品清单的前序编号，同层跳号）都会在这一次写里被归一。只交换两个值的话
   * 并列会继续存在，而并列之下「谁在前」由 SQL 次键 {@code title} 决定 —— 管理员点了上移
   * 却没动，是最难查的那一类 bug。代价是写同层若干行，而菜单是低频配置数据，同层通常
   * 不到 10 条。
   *
   * <p>已经在本层首 / 末时返回 {@code changed:false} 且<b>不写库</b>（不重编号）：越界不是
   * 错误，是「到头了」，前端据此把按钮置灰。
   *
   * <p><b>一条已知的跨效应</b>：入口页在兼容路径上（{@code nav_entry_links.sort_order}
   * 全 0 的老行）会按被挂 target 自身的 {@code (sortOrder, title)} 兜底排序，所以重编号
   * {@code nav_nodes} 理论上会微调这种老入口页的 Tab 顺序。影响面仅限「链接行全 0 且跨父节点
   * 挂」这一极窄情况，与 V31 里「管理员下次保存时自然被重编号」的既有取向一致。
   */
  @Transactional
  public Map<String, Object> move(String id, Integer delta) {
    if (delta == null || (delta != -1 && delta != 1)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "delta 只能是 -1（上移）或 1（下移）");
    }
    NavNodeEntity e = require(id);

    // 与 all() / enabledRows() 同一套序：(sortOrder, title)。uk_nav_node 落在
    // (scope, parent_id, title) 上，所以这个序在兄弟集合里是**确定全序** —— 不会出现两条
    // 分不出先后、需要再拿 id 或创建时间兜底的情况。
    List<NavNodeEntity> siblings = mapper.selectList(new LambdaQueryWrapper<NavNodeEntity>()
        .eq(NavNodeEntity::getScope, e.getScope())
        .eq(NavNodeEntity::getParentId, e.getParentId())
        .orderByAsc(NavNodeEntity::getSortOrder)
        .orderByAsc(NavNodeEntity::getTitle));

    int at = -1;
    for (int i = 0; i < siblings.size(); i++) {
      if (siblings.get(i).getId().equals(id)) {
        at = i;
        break;
      }
    }
    int to = at + delta;

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("changed", false);
    if (at < 0 || to < 0 || to >= siblings.size()) return out;

    NavNodeEntity moved = siblings.remove(at);
    siblings.add(to, moved);
    for (int i = 0; i < siblings.size(); i++) {
      NavNodeEntity n = siblings.get(i);
      n.setSortOrder((i + 1) * SORT_STEP);
      mapper.updateById(n);
    }
    // 顺序变了，侧栏与入口页的缓存都要失效（与 create/update/delete 同一处收口）
    dropCandidateCache();
    out.put("changed", true);
    out.put("sortOrder", moved.getSortOrder());
    return out;
  }

  /**
   * 外链菜单的凭据**明文**（平台管理员「复制」按钮用，见
   * {@code PlatformController#navNodeCredential}）。
   *
   * <p>这是全仓唯一的凭据明文出口，是有意开的：账号密码那一档浏览器不允许代填，
   * 不给人复制就是个死字段。门禁因此收到最严（平台管理员），消费面没有对应接口。
   *
   * <p>没有配 / 没加密文时对应字段回空串，而不是 404 —— 「复制」按钮点下去发现没东西
   * 可复制，与「这个节点不存在」是两件事。
   */
  public Map<String, Object> credential(String id) {
    NavNodeEntity e = require(id);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", e.getId());
    out.put("label", e.getTitle());
    out.put("authMode", oneOf(nz(e.getAuthMode()), AUTH_MODES, "none"));
    out.put("token", decryptOrEmpty(e.getTokenEnc()));
    out.put("basicUser", nz(e.getBasicUser()));
    out.put("password", decryptOrEmpty(e.getBasicPassEnc()));
    return out;
  }

  private String decryptOrEmpty(byte[] enc) {
    if (enc == null || enc.length == 0) return "";
    String plain = crypto.decrypt(enc);
    return plain == null ? "" : plain;
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
   * 同层（同壳 + 同父）里下一个可用的排序值 = 该层最大值 + {@link #SORT_STEP}；
   * 该层还是空的时候就是 {@code SORT_STEP} 本身。
   *
   * <p><b>为什么在 Java 里取最大而不是 {@code select max(...)}</b>：空集时各库返回得不一样
   * （MySQL 给 {@code NULL}、有的驱动给 0），要为三种方言各写一遍 {@code COALESCE}。
   * 兄弟集合很小（一屏几十条），且这个查询走 {@code uk_nav_node (scope, parent_id, title)}
   * 的前缀，在内存里取没有任何代价。
   */
  private int nextSortOrder(String scope, String parentId) {
    Integer max = mapper
        .selectList(new LambdaQueryWrapper<NavNodeEntity>()
            .eq(NavNodeEntity::getScope, scope)
            .eq(NavNodeEntity::getParentId, parentId))
        .stream()
        .map(NavNodeEntity::getSortOrder)
        .filter(Objects::nonNull)
        .max(Integer::compareTo)
        .orElse(null);
    return (max == null ? 0 : max) + SORT_STEP;
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
      if (Boolean.TRUE.equals(parent.getMounted())) {
        // 挂载行的子菜单只能来自产品：`renderMounted` 直接展开产品清单的子树，
        // **根本不读 org 侧的子行** —— 挂在这下面在侧栏里永远不显示，管理页却看得见，
        // 又是一次静默失效（与空目录 hide 同一类）。前端已经不给这个入口了，
        // 这里再拦一道是因为 API 也能造出这种行。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "「" + parent.getTitle() + "」是挂载行，它的子菜单由产品清单决定 —— "
                + "挂在它下面的行不会显示。换个父节点，或改成挂产品清单里对应的那个节点。");
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

    // 显式给了就照给（`dw-org/ui/e2e/workbench.spec.ts` 里那条「侧栏按登记顺序排」的护栏
    // 靠它）；不填时**落在同层末尾**，而不是原先的 0。
    //
    // 缺省 0 是「排序看着乱」的根因之一：同层全 0 = 并列，而并列之下谁在前由 SQL 的次键
    // `title` 决定 —— 管理员没填过的两条菜单，先后由标题的字符序说了算，界面上的数字还都是 0，
    // 看不出这个差别是从哪来的。
    if (req.sortOrder != null) e.setSortOrder(req.sortOrder);
    else if (creating) e.setSortOrder(nextSortOrder(e.getScope(), e.getParentId()));

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

    // V29 的两类新节点（入口页 / 外链菜单）。放在最后：它们要用到上面已经套好的所有字段。
    applyEntryAndExternal(e, req, creating);
  }

  /**
   * 套上并校验 V29 的两类新节点：**入口页**（{@code entry_page}）与**外链菜单**
   * （{@code external_url} 非空）。
   *
   * <h2>三条互斥，一次收口</h2>
   *
   * <ol>
   *   <li>入口页 与 外链 <b>互相排斥</b> —— 一个是「壳内的一排 Tab」，一个是「指向外面的地址」，
   *       同时成立时渲染要听谁的没有答案。</li>
   *   <li>两者都与 {@code product} <b>互斥</b> —— 它们都是 org 自己的东西，挂上产品码之后
   *       「要不要按租户许可过滤」「要不要拼 embed 前缀」这两处判据会同时成立。</li>
   *   <li>外链与 {@code admin_only} <b>互斥</b> —— 外链有自己的 {@code visibility} 开关
   *       （用户裁定）。两个开关叠在一起时「为什么这个人看不到」就没有唯一答案了，
   *       这与上面拒绝「产品节点用 admin_only」是同一个理由。</li>
   * </ol>
   *
   * <p><b>凭据的处置照 {@code ComputeService}</b>：{@code token} / {@code password} 空值 =
   * <b>保持原值</b>（页面上那格永远显示不出明文，用户不改它就不该被清掉）。
   */
  private void applyEntryAndExternal(NavNodeEntity e, NavNodeReq req, boolean creating) {
    if (req.entryPage != null) e.setEntryPage(req.entryPage);
    else if (creating) e.setEntryPage(Boolean.FALSE);

    if (req.externalUrl != null) e.setExternalUrl(normalizeExternalUrl(req.externalUrl));
    else if (creating) e.setExternalUrl("");

    if (req.openMode != null) {
      String mode = req.openMode.trim();
      if (!OPEN_MODES.contains(mode)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知打开方式 " + mode + "。可用值：" + OPEN_MODES);
      }
      e.setOpenMode(mode);
    } else if (creating) {
      e.setOpenMode("jump");
    }

    if (req.authMode != null) {
      String mode = req.authMode.trim();
      if (!AUTH_MODES.contains(mode)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知认证方式 " + mode + "。可用值：" + AUTH_MODES);
      }
      e.setAuthMode(mode);
    } else if (creating) {
      e.setAuthMode("none");
    }

    if (req.visibility != null) {
      String vis = req.visibility.trim();
      if (!VISIBILITIES.contains(vis)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知可见范围 " + vis + "。可用值：" + VISIBILITIES);
      }
      e.setVisibility(vis);
    } else if (creating) {
      e.setVisibility("all");
    }

    if (req.token != null && !req.token.isBlank()) e.setTokenEnc(crypto.encrypt(req.token));
    if (req.basicUser != null) e.setBasicUser(req.basicUser.trim());
    else if (creating) e.setBasicUser("");
    if (req.password != null && !req.password.isBlank()) {
      e.setBasicPassEnc(crypto.encrypt(req.password));
    }

    if (isEntry(e) && isExternal(e)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "一条菜单不能既是入口页又是外链 —— 一个在壳内开表格，一个指向外部地址");
    }
    if ((isEntry(e) || isExternal(e)) && isProductNode(e)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "入口页与外链菜单都是 org 自己的东西，不能带产品码");
    }
    if (isExternal(e) && Boolean.TRUE.equals(e.getAdminOnly())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "外链菜单用「可见范围」开关，不用「仅管理员可见」—— 两个开关叠在一起就说不清为什么看不到");
    }

    // 这两类的 `path` 由服务端写成**模板**，管理员不必（也不该）手填 —— 入口页的 id 是
    // 服务端生成的，管理员在提交时根本不知道它。占位符 `{node}` 由前端用节点自己的 id
    // 替换，与 `{code}` 是同一套机制（见 `sysNav.ts` 的替换处）。
    //
    // 写模板而不是「渲染时注入完整路径」：管理面（本方法写的这一列）与消费面共用同一份
    // 判据 —— `path` 为空 = 目录节点。渲染时才注入的话，管理页看到的是空串，会把入口页
    // 显示成一个「目录」，而它明明有页面。
    if (isEntry(e)) {
      e.setPath(entryPathTemplate(e.getScope()));
    } else if (isExternal(e)) {
      // 内嵌才有一个壳内地址；外跳直接是 href，没有壳内路径。
      e.setPath(OPEN_EMBED.equals(nz(e.getOpenMode())) ? externalPathTemplate(e.getScope()) : "");
    } else if (nz(e.getPath()).contains("{node}")) {
      // 从入口页 / 外链改回普通节点：把服务端自己写的模板清掉。这一列从来不是管理员填的，
      // 留着就是一个指向「已经没有页面」的壳内地址（前端照样拼得出来，进去是 404）。
      e.setPath("");
    }
  }

  /** 入口页在壳内的地址模板（`{node}` 由前端替换成节点 id）。 */
  private static String entryPathTemplate(String scope) {
    return PROJECT.equals(scope)
        ? "/org/project/{code}/entry/{node}"
        : "/org/workbench/entry/{node}";
  }

  /** 外链内嵌页在壳内的地址模板（同上）。 */
  private static String externalPathTemplate(String scope) {
    return PROJECT.equals(scope)
        ? "/org/project/{code}/external/{node}"
        : "/org/workbench/external/{node}";
  }

  /**
   * 外链地址的校验与归一：<b>只允许 http / https</b>，且必须是个绝对地址。
   *
   * <p>这条校验是硬要求，不是洁癖：这个值会落到前端的 {@code href}（{@code :href} 绑
   * {@code javascript:} 会执行）与 iframe 的 {@code src} 上 —— 这是本次新增的唯一一处
   * 「管理员可控的任意 URL」。空串是合法的（= 这一条不是外链）。
   */
  private static String normalizeExternalUrl(String raw) {
    String url = raw == null ? "" : raw.trim();
    if (url.isEmpty()) return "";
    String lower = url.toLowerCase();
    if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "外链地址只支持 http / https，当前是「" + url + "」");
    }
    if (url.length() > PATH_MAX) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "外链地址最长 " + PATH_MAX + " 个字符，当前 " + url.length());
    }
    return url;
  }

  /**
   * 落入口页挂的菜单。
   *
   * <h2>两套写法（V31 起）</h2>
   *
   * <ul>
   *   <li><b>{@code links}（新，前端唯一会发的形状）</b>：一份<b>带类型</b>的列表，
   *       数组顺序就是 Tab 顺序，每条可带自己的 {@code label}（Tab 名）。全量替换
   *       （两类一起）：{@code null} = 不改，空列表 = 都清空。</li>
   *   <li><b>{@code linkTargets} / {@code productLinks}（老，仍然收）</b>：两类
   *       <b>各管各的</b>“{@code null} = 不改，空列表 = 清空”，互不牵连；顺序 = 各自数组
   *       的下标，本站那一类整体在前、产品整体在后 —— 与 V31 之前逐字相同。</li>
   * </ul>
   *
   * <p>两条路径<b>不能同时用</b>：以哪一份为准没有唯一说法（见 {@link #normalizeLinks}）。
   *
   * <p><b>V29 说的「为什么不合成一个列表」现在只对了一半</b>：仍然不能合成一份
   * <b>无类型</b>的 id 列表 —— 两类 id 长相不同但要靠产品码才解析得开，让服务端猜形状，
   * 猜错时报的是「要挂的菜单不存在」，与真实原因（发错列表了）差得很远。所以新形状
   * 每一条都显式带 {@code kind}。而「顺序」这一半被推翻了：两个数组<b>拼不回一个混合顺序</b>，
   * 而用户裁定的正是混合顺序（行顺序 = Tab 顺序）。
   *
   * <h2>{@code linkTargets} 的三条校验（V29，一个字没改）</h2>
   *
   * <ul>
   *   <li>target 必须存在、与入口页<b>同壳</b> —— 跨壳挂会让侧栏按壳取树时那一支整个丢掉
   *       （与 {@link #apply} 里「父节点属于别的壳」同一个理由）。</li>
   *   <li>target 不能是<b>入口页</b>（含它自己）—— 这一条同时把自引用与多级循环全部挡掉，
   *       比写一个环检测简单且够用：Tab 里嵌另一层 Tab，没有任何一处能说清该显示什么。</li>
   *   <li>只有入口页能挂东西 —— 传了 {@code linkTargets} 却不是入口页时<b>报错而不是静默忽略</b>：
   *       静默会让管理员以为挂上了。</li>
   * </ul>
   *
   * <h2>{@code productLinks} 的校验（V30）</h2>
   *
   * <p>口径照 {@link #requireMountable} 那一套来 —— 挂载节点与产品清单引用是同一件事的
   * 两种落点，判据不该有两份：产品必须是<b>已登记页面地址</b>的（否则挂上就是点不开）、
   * 清单里确实有这个 id（否则这一支永远是空的）、清单自报的 {@code scope} 必须与入口页
   * 同壳。**清单拉不到时 fail-open 放行**，理由与 {@code requireMountable} 逐字相同：
   * 拿一处配置缺失把整个功能锁死比放过一个坏引用更糟，而且坏引用在渲染侧另有交代
   * （见 {@link #renderProductLink}，置灰说明而不是静默消失）。
   */
  private void applyLinks(NavNodeEntity e, NavNodeReq req, boolean creating) {
    List<LinkSpec> specs = normalizeLinks(req);
    boolean byLinks = req.links != null;
    // 「传了空数组 = 清空这一类」与「没传 = 不动这一类」是两回事，所以判据是 != null 而不是非空。
    // byLinks 时两类一起重写 —— 那时它们共用一套行号，本来就不该分开动（见 normalizeLinks）。
    boolean writeNodes = byLinks || req.linkTargets != null;
    boolean writeProducts = byLinks || req.productLinks != null;
    if (!isEntry(e)) {
      if (!specs.isEmpty()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "只有入口页能挂其它菜单，这一条不是入口页");
      }
      // 从入口页改成别的类型：把它原来挂的东西清掉，免得留下再也不会显示的悬空记录
      if (!creating) {
        links.delete(new LambdaQueryWrapper<NavEntryLinkEntity>()
            .eq(NavEntryLinkEntity::getEntryId, e.getId()));
        productLinks.delete(new LambdaQueryWrapper<NavEntryProductLinkEntity>()
            .eq(NavEntryProductLinkEntity::getEntryId, e.getId()));
      }
      return;
    }

    // 去重 + 保序（同一条传两遍不该落两条，而主键会直接撞成 500）。
    // 去重键两类不同：本站是 target_id，产品是 (product, ref) —— 两个产品可以有同名 ref。
    Set<String> seen = new LinkedHashSet<>();
    List<LinkSpec> kept = new ArrayList<>();
    for (LinkSpec s : specs) {
      if (seen.add(s.dedupeKey())) kept.add(s);
    }

    for (LinkSpec s : kept) {
      if (s.label().length() > LABEL_MAX) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "Tab 名称最长 " + LABEL_MAX + " 个字符，当前 " + s.label().length());
      }
    }

    List<NavEntryLinkEntity> nodeRows = null;
    if (writeNodes) {
      nodeRows = new ArrayList<>();
      for (LinkSpec s : kept) {
        if (!KIND_NODE.equals(s.kind())) continue;
        NavNodeEntity target = requireMountableTarget(e, s.target());
        nodeRows.add(new NavEntryLinkEntity(
            e.getId(), target.getId(), blankToNull(s.label()), s.order()));
      }
    }

    // 产品清单引用。形状校验（产品码非空、是已知产品、ref 没超长）在这里统一做：
    // 老路径进来的 productLinks 没经过 normalizeLinks 那一层，只有这里挡得住。
    List<NavEntryProductLinkEntity> productRows = null;
    if (writeProducts) {
      productRows = new ArrayList<>();
      for (LinkSpec s : kept) {
        if (!KIND_PRODUCT.equals(s.kind())) continue;
        String product = s.product();
        String ref = s.ref();
        if (product.isEmpty() || ref.isEmpty()) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "挂产品节点要同时给出产品码与节点 id，收到的是 product=「" + product
                  + "」ref=「" + ref + "」");
        }
        if (!ProductCodes.isKnown(product)) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "未知产品码 " + product + "。可用值：" + ProductCodes.KNOWN);
        }
        if (ref.length() > REF_MAX) {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "产品清单里的节点 id 最长 " + REF_MAX + " 个字符，当前 " + ref.length());
        }
        // 已经校验过的那一条在重提交时不该再拉一次清单：同一个入口页里同一个产品可能挂很多条
        checkProductRef(e, product, ref);
        productRows.add(new NavEntryProductLinkEntity(
            e.getId(), product, ref, s.order(), blankToNull(s.label())));
      }
    }

    if (nodeRows != null) {
      links.delete(new LambdaQueryWrapper<NavEntryLinkEntity>()
          .eq(NavEntryLinkEntity::getEntryId, e.getId()));
      for (NavEntryLinkEntity l : nodeRows) links.insert(l);
    }
    if (productRows != null) {
      productLinks.delete(new LambdaQueryWrapper<NavEntryProductLinkEntity>()
          .eq(NavEntryProductLinkEntity::getEntryId, e.getId()));
      for (NavEntryProductLinkEntity l : productRows) productLinks.insert(l);
    }
  }

  /**
   * 归一化后的一条 Tab（V31）：两类引用<b>合成一份带类型的列表</b>，下标即 Tab 顺序。
   *
   * <p>{@code kind} 只有 {@link #KIND_NODE}（本站菜单，{@code target} 是
   * {@code nav_nodes.id}）与 {@link #KIND_PRODUCT}（产品清单节点，{@code product + ref}）
   * 两种，用不到的字段是空串 —— 这样 {@code applyLinks} 里没有一处需要判空。
   *
   * <p>{@code order} 直接取请求下标（不是重排后的序号）：去重会跳号，而排序只看相对大小。
   */
  private record LinkSpec(
      String kind, String target, String product, String ref, String label, int order) {

    /** 去重键。主键是复合的，两类各按自己的那一组列。 */
    String dedupeKey() {
      return KIND_NODE.equals(kind)
          ? KIND_NODE + '\u0000' + target
          : KIND_PRODUCT + '\u0000' + product + '\u0000' + ref;
    }
  }

  /**
   * 把请求体归一成一份 {@link LinkSpec} 列表（V31）。
   *
   * <p><b>新写法 {@code links}</b>：一个带类型的数组，<b>数组顺序就是 Tab 顺序</b>，
   * 每条可以带自己的 {@code label}（Tab 名称）。这是 V31 之后前端唯一会发的形状 ——
   * 用户裁定「行顺序 = Tab 顺序」，而两个扁平数组拼不回一个混合顺序。
   *
   * <p><b>老写法 {@code linkTargets} / {@code productLinks} 照旧收</b>（老客户端与既有测试）：
   * 各自数组的下标就是顺序，本站那一类整体在前、产品那一类整体在后 —— 与 V31 之前逐字相同。
   * 所以两条路径**不能同时用**：同时传的话「以哪一份为准」没有唯一说法，直接 400 而不是
   * 挑一份执行 —— 挑错的那次会静默丢掉管理员刚排好的 Tab 顺序。
   *
   * <p>这里只做<b>形状</b>校验（{@code kind} 合法、{@code node} 那类给了 target）——
   * 需要查库、拉产品清单的那些求证在 {@link #applyLinks} 里做，与老路径共用同一段代码。
   */
  private static List<LinkSpec> normalizeLinks(NavNodeReq req) {
    if (req.links != null) {
      if (req.linkTargets != null || req.productLinks != null) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "links 不能与 linkTargets / productLinks 同时传：links 是那两者的替代"
                + "（多带 Tab 名称与行顺序），同时传的话以哪一份为准没有唯一说法");
      }
      List<LinkSpec> out = new ArrayList<>();
      for (int i = 0; i < req.links.size(); i++) {
        NavEntryLinkReq raw = req.links.get(i);
        if (raw == null) continue;
        String kind = str(raw.kind).toLowerCase(Locale.ROOT);
        if (KIND_NODE.equals(kind)) {
          String target = str(raw.target);
          if (target.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "links 里 kind=node 的每一行都要给 target（本站菜单在 nav_nodes 里的 id）");
          }
          out.add(new LinkSpec(KIND_NODE, target, "", "", str(raw.label), i));
        } else if (KIND_PRODUCT.equals(kind)) {
          out.add(new LinkSpec(
              KIND_PRODUCT, "", str(raw.product), str(raw.ref), str(raw.label), i));
        } else {
          throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
              "links 里 kind 只能是「" + KIND_NODE + "」或「" + KIND_PRODUCT
                  + "」，收到的是「" + kind + "」");
        }
      }
      return out;
    }

    List<LinkSpec> out = new ArrayList<>();
    if (req.linkTargets != null) {
      for (String raw : req.linkTargets) {
        String targetId = str(raw);
        if (!targetId.isEmpty()) {
          out.add(new LinkSpec(KIND_NODE, targetId, "", "", "", out.size()));
        }
      }
    }
    if (req.productLinks != null) {
      for (NavProductLinkReq raw : req.productLinks) {
        if (raw == null) continue;
        out.add(new LinkSpec(
            KIND_PRODUCT, "", str(raw.product), str(raw.ref), "", out.size()));
      }
    }
    return out;
  }

  /**
   * 一条本站引用的写入前求证（V29 的三条，一个字没改）：存在、与入口页同壳、不是入口页
   * （后者同时把自引用与多级循环全部挡掉）。理由见 {@link #applyLinks} 的注释。
   */
  private NavNodeEntity requireMountableTarget(NavNodeEntity entry, String targetId) {
    if (targetId.equals(entry.getId())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口页不能把自己挂进来");
    }
    NavNodeEntity target = mapper.selectById(targetId);
    if (target == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "要挂的菜单不存在：" + targetId);
    }
    if (!nz(target.getScope()).equals(nz(entry.getScope()))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "「" + target.getTitle() + "」在别的壳里（" + target.getScope()
              + "），不能挂到「" + entry.getScope() + "」壳的入口页上");
    }
    if (isEntry(target)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "「" + target.getTitle() + "」自己也是入口页，不能挂进另一张表里");
    }
    return target;
  }

  /** 一条产品清单引用的写入前求证（V30）。判据与理由见 {@link #applyLinks} 的注释。 */
  private void checkProductRef(NavNodeEntity entry, String product, String ref) {
    if (frontendUrl(product).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "产品「" + product + "」还没在「服务注册」里登记页面地址，挂上之后这一支点不开。"
              + "请先在平台侧登记它的前端地址");
    }
    List<Map<String, Object>> menus = candidates.menusOrNull(product);
    if (menus == null) return; // 拉不到 = 跳过校验（fail-open，见 applyLinks 的注释）
    Map<String, Object> pn = findByRef(menus, ref);
    if (pn == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "产品「" + product + "」的清单里没有 id 为「" + ref + "」的节点，"
              + "挂上后这一个 Tab 会是空的。它现在报的顶层节点是：" + describe(menus));
    }
    String scope = str(pn.get("scope"));
    if (!scope.equals(nz(entry.getScope()))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "「" + str(pn.get("label")) + "」在别的壳里（" + scope + "），不能挂到「"
              + entry.getScope() + "」壳的入口页上");
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

    List<NavNodeEntity> rows = enabledRows();
    if (rows.isEmpty()) return List.of();

    Render r = renderContext(tenantId, projectId, rows);
    Map<String, List<NavNodeEntity>> byParent = byParent(rows, r);

    List<Map<String, Object>> out = new ArrayList<>();
    for (NavNodeEntity n : byParent.getOrDefault(ROOT, List.of())) {
      Map<String, Object> node = render(n, byParent, r);
      if (node != null) out.add(node);
    }
    return out;
  }

  /** 所有**启用**的节点，按 (sortOrder, title) —— 侧栏与入口页共用这一份顺序。 */
  private List<NavNodeEntity> enabledRows() {
    return mapper.selectList(
        new LambdaQueryWrapper<NavNodeEntity>()
            .eq(NavNodeEntity::getEnabled, true)
            .orderByAsc(NavNodeEntity::getSortOrder)
            .orderByAsc(NavNodeEntity::getTitle));
  }

  /**
   * 一次渲染的上下文：许可、角色、以及「挂载展开会吐出来的路径」。
   *
   * <p>第三项（{@code taken}）必须在正式渲染<b>之前</b>收齐 —— 手工行要先知道哪些被接管了，
   * 否则同一批菜单会在侧栏里出现两遍。见 {@link #treeFor} 的注释。
   *
   * <p>抽出来是因为 {@link #entryPage} 也要这一整套：入口页里的每一项都必须与它在侧栏
   * 原位置的样子是同一个结论，各建一套上下文迟早会漂移。
   */
  private Render renderContext(String tenantId, String projectId, List<NavNodeEntity> rows) {
    Render r = new Render(
        projectId, licensedProducts(tenantId), access.isRealTenantAdmin(), policiesOf(tenantId));
    for (NavNodeEntity n : rows) {
      if (!isMounted(n)) continue;
      collectPaths(r.productNode(n.getProduct(), n.getRef()), n.getProduct(), r.taken);
    }
    return r;
  }

  /** 按父节点分组；产品节点里这个人看不到的那些整条不进来。 */
  private Map<String, List<NavNodeEntity>> byParent(List<NavNodeEntity> rows, Render r) {
    Map<String, List<NavNodeEntity>> byParent = new HashMap<>();
    for (NavNodeEntity n : rows) {
      if (isProductNode(n) && !r.shows(n)) continue;
      byParent.computeIfAbsent(nz(n.getParentId()), k -> new ArrayList<>()).add(n);
    }
    return byParent;
  }

  /**
   * 入口页的内容：把 {@code nav_entry_links} 挂的每个菜单渲染一遍。
   *
   * <p><b>走的是与侧栏同一个 {@link #render}</b>，不是另写一套取数。这是这个功能能成立的
   * 关键：判权、置灰、许可过滤、空目录策略全部自动一致 —— 表里看不到的东西，在侧栏原位
   * 也看不到，反过来也一样。另写一套的话，「原位置看不到、表里看得到」这类不一致迟早出现，
   * 而且要两处同时改。
   *
   * <p><b>V31 起顺序是「行顺序」</b>（用户裁定）：{@code nav_entry_links} 与
   * {@code nav_entry_product_links} 的行号在<b>同一个序号空间</b>里，两类混排，
   * 管理员在编辑表单里上下移动就改了 Tab 顺序。
   *
   * <p>这推翻了 V29 的「顺序跟随 target 自己」。动机是 Tab 名可以自己起之后，
   * 两个同名菜单必须靠顺序区分（见 {@code V31__entry_link_label_and_order.sql}）。
   * <b>但老入口页的顺序分毫不变</b>：V31 之前存的行 {@code sort_order} 全是 0，
   * 而这里的排序是<b>稳定排序</b>，装入顺序就是 V31 之前的旧顺序（本站按
   * {@code rows} 即 {@code (sortOrder, title)}、产品按自己的勾选顺序）。
   *
   * <p><b>V30 起多一类条目</b>：{@code nav_entry_product_links} 里挂的产品清单节点。
   * 那一类在 {@code nav_nodes} 里没有行，所以只能走产品清单渲染（{@link #renderProductNode}）。
   *
   * <p><b>V31 起每一行可以有自己的 Tab 名</b>（关联表上的 {@code label}，非空时覆盖
   * 渲染出来的标题）—— 这是用户报的那个问题：两个 Tab 都叫「概况」时分不出谁是谁。
   *
   * <p>找不到、不是入口页、或这个人看不到这个入口页 → 404（不是 403：看不见的入口页
   * 不必让调用方知道它存在）。
   */
  public Map<String, Object> entryPage(String tenantId, String projectId, String id) {
    NavNodeEntity entry = id == null ? null : mapper.selectById(id);
    if (entry == null || !Boolean.TRUE.equals(entry.getEnabled()) || !isEntry(entry)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "入口页不存在");
    }
    if (tenantId == null || tenantId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "入口页不存在");
    }

    List<NavNodeEntity> rows = enabledRows();
    Render r = renderContext(tenantId, projectId, rows);
    Map<String, List<NavNodeEntity>> byParent = byParent(rows, r);

    // 入口页自己也可能是「仅租户管理员可见」的 org 节点 —— 复用同一条判据，不另写。
    if (render(entry, byParent, r) == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "入口页不存在");
    }

    List<NavEntryLinkEntity> nodeLinks = links.selectList(
        new LambdaQueryWrapper<NavEntryLinkEntity>().eq(NavEntryLinkEntity::getEntryId, entry.getId()));
    Map<String, NavEntryLinkEntity> nodeLinkByTarget = new HashMap<>();
    for (NavEntryLinkEntity l : nodeLinks) nodeLinkByTarget.put(nz(l.getTargetId()), l);

    // V31：两类引用合成「一行一个 Tab」，行号在**同一个序号空间**里，所以要一起排。
    // 装入顺序刻意保持 V31 之前的样子（本站按 rows 的顺序、产品按勾选顺序），
    // 再按行号做稳定排序 —— 老行（迁移刻意不回填，行号全是 0）排完仍是旧顺序，
    // 而管理员存过一次的入口页每行都有唯一行号，于是严格按他排的顺序出。
    record Pending(Map<String, Object> item, int order, String label) {}
    List<Pending> pending = new ArrayList<>();

    for (NavNodeEntity n : rows) { // rows 已是 (sortOrder, title)
      NavEntryLinkEntity link = nodeLinkByTarget.get(n.getId());
      if (link == null) continue;
      Map<String, Object> item = render(n, byParent, r);
      if (item == null) continue;
      pending.add(new Pending(item, link.getSortOrder() == null ? 0 : link.getSortOrder(),
          nz(link.getLabel())));
    }

    for (NavEntryProductLinkEntity l : productLinks.selectList(
        new LambdaQueryWrapper<NavEntryProductLinkEntity>()
            .eq(NavEntryProductLinkEntity::getEntryId, entry.getId())
            .orderByAsc(NavEntryProductLinkEntity::getSortOrder))) {
      Map<String, Object> item = renderProductLink(entry, nz(l.getProduct()), nz(l.getRef()), r);
      if (item == null) continue;
      pending.add(new Pending(item, l.getSortOrder() == null ? 0 : l.getSortOrder(),
          nz(l.getLabel())));
    }

    pending.sort(Comparator.comparingInt((Pending p) -> p.order()));

    List<Map<String, Object>> items = new ArrayList<>(pending.size());
    for (Pending p : pending) {
      Map<String, Object> item = p.item();
      // 改过名就覆盖掉渲染出来的标题（V31）。判据是**非空**而不是非 null：库里那一列
      // 空串与 NULL 是同一个意思（没改过名），见 blankToNull。
      if (!p.label().isEmpty()) item.put("label", p.label());
      items.add(item);
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", entry.getId());
    out.put("label", entry.getTitle());
    out.put("scope", entry.getScope());
    out.put("items", items);
    return out;
  }

  /**
   * 外链菜单最终要打开的地址 —— <b>token 已经拼进查询串</b>（用户裁定：内嵌与外跳都带）。
   *
   * <p><b>token 只在这里出口，且一次只给这一条外链。</b>侧栏树里只有 {@code hasToken}
   * 布尔（见 {@link #toRow}），所以明文不会随每个页面的侧栏请求反复下发。
   *
   * <p>判权复用 {@link #render}（可见性不通过 → 403），与侧栏同一条判据。
   *
   * <p>参数名固定 {@code token}：做成可配之后「这个参数是不是敏感」就无从判定，
   * 而它恰恰是唯一一个会把密钥拼到 URL 上的地方。
   */
  public Map<String, Object> externalTarget(String tenantId, String projectId, String id) {
    NavNodeEntity e = id == null ? null : mapper.selectById(id);
    if (e == null || !Boolean.TRUE.equals(e.getEnabled()) || !isExternal(e)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "外链菜单不存在");
    }
    if (tenantId == null || tenantId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "外链菜单不存在");
    }

    List<NavNodeEntity> rows = enabledRows();
    Render r = renderContext(tenantId, projectId, rows);
    if (render(e, byParent(rows, r), r) == null) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有访问这条外链的权限");
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", e.getId());
    out.put("label", e.getTitle());
    out.put("url", withToken(nz(e.getExternalUrl()), e));
    out.put("openMode", oneOf(nz(e.getOpenMode()), OPEN_MODES, "jump"));
    return out;
  }

  /**
   * 按认证方式把凭据拼进地址。
   *
   * <p><b>只有 token 会拼</b>，且只拼查询串 —— iframe 不能带自定义请求头，这是浏览器限制，
   * 没有别的落点。副作用要如实告知使用者：这个地址会进浏览器历史、目标站的访问日志、
   * 以及（未加 {@code referrerpolicy} 时的）Referer。
   *
   * <p>{@code basic} 这一档<b>什么都不做</b>：现代浏览器禁止 {@code https://user:pass@host}
   * 作为 iframe 地址，也无法代填第三方的登录表单。那对凭据的实际用途只有「平台管理员
   * 保存备查 + 复制」（见 {@code NavNodeEntity#getAuthMode()}）。
   */
  private String withToken(String url, NavNodeEntity e) {
    if (url.isEmpty() || !"token".equals(nz(e.getAuthMode()))) return url;
    byte[] enc = e.getTokenEnc();
    if (enc == null || enc.length == 0) return url;
    String token = crypto.decrypt(enc);
    if (token == null || token.isEmpty()) return url;
    // 已经带了查询串就用 `&`，否则 `?` —— 目标地址自带参数（`?tab=1`）是常见形态，
    // 一律用 `?` 会把原来的参数挤进 token 的值里。
    return url + (url.indexOf('?') >= 0 ? "&" : "?") + "token="
        + URLEncoder.encode(token, StandardCharsets.UTF_8);
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
    } else if (isExternal(n)) {
      // 外链走**独立的可见性开关**（用户裁定 4），不复用 admin_only —— 后者的语义是
      // 「org 自有页面里只给租户管理员看」，而这里要回答的是「谁能用这条带凭据的入口」。
      //
      // 看不见时**整条不返回**而不是置灰：置灰那一套是给「这个壳自己的功能」的
      // （空侧栏会让人以为壳坏了），而外链是别人家的站点被配进来，与产品节点同类。
      if (VIS_TENANT_ADMIN.equals(nz(n.getVisibility())) && !r.tenantAdmin) return null;
    } else if (Boolean.TRUE.equals(n.getAdminOnly()) && !r.tenantAdmin) {
      return null;
    }

    Map<String, Object> row = toRow(n);
    // 外链菜单：`jump` 的落点是目标站（走叶子的 `<a target="_blank">` 通路），
    // `embed` 的落点是壳内那一页（走 `<router-link>`），两者给的是不同的字段。
    if (isExternal(n)) {
      row.put("external", true);
      if (OPEN_EMBED.equals(nz(n.getOpenMode()))) {
        row.put("path", externalPathTemplate(n.getScope()));
      } else {
        row.put("href", nz(n.getExternalUrl()));
      }
    }
    // 每条产品节点都带上自己产品的页面地址：前端据此拼 `{frontendUrl}{path}` 的嵌入地址，
    // 缺了它这一项在侧栏里点不动（或跳到一个猜出来的默认端口，见 servicesFor 的说明）。
    // 为空 = 「服务注册」里还没登记地址，前端显示占位而不是嵌一个空 iframe。
    if (isProductNode(n)) row.put("frontendUrl", frontendUrl(n.getProduct()));
    // org 自有节点若判不动，**置灰而不是抹掉**（见类注释）：它是这个壳自己的功能，
    // 什么都不显示会让人以为壳里压根没有这一项。
    //
    // 外链不走这一套（`!isExternal`）：它的门禁是上面那条 visibility，而角色词要 `product`
    // 才判得动（`currentRole`），外链没有产品。两套叠着判的话，一条配了权限词的外链
    // 会对所有人置灰 —— 且「置灰」还看不出来是权限问题。
    if (!isProductNode(n) && !isExternal(n) && hasPermWord(n.getPerm()) && !r.roleHas(access.productCode(), n.getPerm())) {
      row.put("disabled", true);
      row.put("disabledReason", "需要本项目的相应权限（在产品角色里授权）");
    }

    List<Map<String, Object>> kids = new ArrayList<>();
    for (NavNodeEntity child : byParent.getOrDefault(n.getId(), List.of())) {
      Map<String, Object> kid = render(child, byParent, r);
      if (kid != null) kids.add(kid);
    }
    row.put("children", kids);

    // 空目录判定。外链要排除在外：`jump` 那一档的 `path` **本来就该是空串**（它有 `href`、
    // 没有壳内地址），落进这条判据会被当成「空目录」，默认策略 `hide` 直接把它整条抹掉 ——
    // 表现为「保存成功、侧栏里没有这一项」，且没有任何报错。
    if (!isExternal(n) && nz(n.getPath()).isEmpty() && kids.isEmpty()) {
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

  /**
   * 入口页里挂的一个**产品清单节点**（V30）渲染成一个 Tab。
   *
   * <p>四种结局，每一种都是刻意的：
   *
   * <ul>
   *   <li><b>产品对本租户/本人不可用</b> → 整条不出现。这一条是为了与侧栏一致 ——
   *       同一个节点在侧栏里也是「整条不出现」（产品节点的既有口径），而入口页的全部意义
   *       就是「表里看不到的，侧栏原位也看不到」。这里改成置灰的话，同一份配置在两个地方
   *       结论不同，而没有任何一处说得清哪个对。</li>
   *   <li><b>清单拉不到</b> → 置灰 + 说明。写入侧对这种情况是 fail-open 放行的
   *       （见 {@link #applyLinks}），所以渲染侧必须给个交代，否则就是「配好了、什么都没有，
   *       也不报错」。</li>
   *   <li><b>清单里没有这个 id</b>（产品改了 id 生成规则）→ 置灰 + 说明，文案与菜单管理页的
   *       {@code mismatchTip} 同一个说法。静默跳过是这里最坏的一种做法：配置看起来完全正常，
   *       只有进这个入口页才发现少了一格，而那时没人会想到是产品侧换了 id。</li>
   *   <li><b>正常</b> → 清单节点的子树照常渲染。</li>
   * </ul>
   *
   * <p><b>顶层行的 {@code scope} 归一成入口页的壳</b>：清单自报的 scope 可能与这个入口页
   * 不同（写入侧虽然校验过同壳，但那是「拉得到清单时」才校验的，而产品自己也可能改），
   * 前端 {@code toNavItems} 只挑顶层、且按 {@code ctx.scope} 过滤 —— 留着清单自报的值会让
   * 整个 Tab 变成一张「这一项不在这个壳的菜单里」的卡片。同一处理在 {@code navMount.ts}
   * 的 {@code toRows} 里已有先例，理由是同一个：挂在哪里就是哪里，归属由配置决定，
   * 不由被挂的那一方自报。
   */
  private Map<String, Object> renderProductLink(
      NavNodeEntity entry, String product, String ref, Render r) {
    if (!r.available(product)) return null;
    if (r.menus(product) == null) {
      return unavailable(productLinkRow(product, ref),
          "拉不到产品「" + product + "」的菜单清单（「服务注册」里没登记它的页面地址，"
              + "或该产品暂时不可达）");
    }
    Map<String, Object> pn = r.productNode(product, ref);
    if (pn == null) {
      return unavailable(productLinkRow(product, ref),
          "产品「" + product + "」的清单里现在没有 id 为「" + ref + "」的节点 —— 多半是产品侧"
              + "改了节点 id 的生成规则。回「菜单管理」把这一条重新挂一次");
    }
    Map<String, Object> row = renderProductNode(pn, product, r);
    if (row == null) return null; // 判不动 / 空目录：与产品节点同一口径，整条不出现
    row.put("scope", nz(entry.getScope()));
    return row;
  }

  /**
   * 一条挂不上（清单拉不到 / 清单里没这个 id）的产品引用的**外壳行**，交给
   * {@link #unavailable} 补上置灰信息。
   *
   * <p>标签只能是 {@code ref}：看得懂的那个名字在产品清单里，而这一条恰恰是清单读不到的
   * 情况。{@code disabledReason} 里带了产品码与 ref，管理员据此能查。
   */
  private Map<String, Object> productLinkRow(String product, String ref) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", product + "/" + ref);
    row.put("parentId", "");
    row.put("label", ref);
    row.put("path", "");
    // 图标名必须取自前端 config/navIcons.ts —— 写一个不在表里的名字不会报错，只会静默不渲染
    row.put("icon", "BlockOutlined");
    row.put("perm", "");
    row.put("product", product);
    row.put("ref", ref);
    row.put("mounted", false);
    row.put("enabled", true);
    row.put("adminOnly", false);
    row.put("emptyPolicy", HIDE);
    row.put("sortOrder", 0);
    row.put("scope", "");
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
    // V29 的两类新节点。**密文一律不出现在这里** —— 回传的是 hasToken / hasPassword 两个布尔，
    // 照 {@code ComputeDto} 那一套（见该类注释）。唯一的明文出口是平台管理员的「复制」接口。
    row.put("entryPage", isEntry(e));
    row.put("externalUrl", nz(e.getExternalUrl()));
    row.put("openMode", oneOf(nz(e.getOpenMode()), OPEN_MODES, "jump"));
    row.put("authMode", oneOf(nz(e.getAuthMode()), AUTH_MODES, "none"));
    row.put("hasToken", e.getTokenEnc() != null && e.getTokenEnc().length > 0);
    row.put("basicUser", nz(e.getBasicUser()));
    row.put("hasPassword", e.getBasicPassEnc() != null && e.getBasicPassEnc().length > 0);
    row.put("visibility", oneOf(nz(e.getVisibility()), VISIBILITIES, "all"));
    row.put("createdAt", e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
    return row;
  }

  /** 读到一个不在白名单里的值（脏数据 / 老行）时回落默认，而不是把它原样交给前端。 */
  private static String oneOf(String value, Set<String> allowed, String fallback) {
    return allowed.contains(value) ? value : fallback;
  }

  private static String nz(String v) {
    return v == null ? "" : v;
  }

  private static String str(Object v) {
    return v == null ? "" : String.valueOf(v).trim();
  }

  /** 空串落成 {@code null} 再入库：Tab 名那一列的 {@code NULL} 有一个明确含义（没改过名）。 */
  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v;
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

    // ---- V29：入口页与外链菜单 ----

    /**
     * 这一行是不是一个「入口页」。为 {@code true} 时 {@link #linkTargets} 才有意义。
     *
     * <p>改这一位要谨慎：置 {@code false} 之后 {@code nav_entry_links} 里的行不会自动消失，
     * 只是再也读不出来（渲染与回显都先看这一位）。清关联要显式传空列表。
     */
    public Boolean entryPage;
    /** 入口页挂了哪些菜单（{@code nav_nodes.id}）。<b>全量替换语义</b>：{@code null} = 不改，空列表 = 清空。 */
    public List<String> linkTargets;
    /**
     * 入口页挂的**产品清单节点**（V30）。与 {@link #linkTargets} 各自独立的全量替换语义。
     *
     * <p>单独一个字段而不是并进 {@code linkTargets}：两类 id 的解析方式不同（查
     * {@code nav_nodes} vs 查产品清单），混在一起服务端只能靠形状猜。理由见
     * {@link #applyLinks}。
     */
    public List<NavProductLinkReq> productLinks;
    /**
     * 入口页挂了哪些 Tab —— <b>V31 起的推荐写法，也是前端唯一会发的形状</b>。
     * <b>数组顺序 = Tab 顺序</b>，每条可带自己的名称。
     *
     * <p>与 {@link #linkTargets} / {@link #productLinks} 是<b>互斥的两套写法</b>：
     * 同时传会被拒（以哪一份为准没有唯一说法，挑错的那次会静默丢掉刚排好的 Tab 顺序）。
     * 老的两个字段仍然收（老客户端与既有测试），语义与 V31 之前逐字相同。
     *
     * <p><b>全量替换</b>（两类一起）：{@code null} = 不改，空列表 = 把两类都清空 ——
     * 因为在 {@code links} 这套语义里它们本来就是同一张列表。
     */
    public List<NavEntryLinkReq> links;
    /**
     * 外链目标地址；非空 = 这一行是一条外链菜单（{@link #product} 必为空）。
     *
     * <p>只允许 {@code http}/{@code https}，写入时校验。
     */
    public String externalUrl;
    /** {@link #OPEN_MODES} 之一；不传时新建默认 {@code jump}。 */
    public String openMode;
    /** {@link #AUTH_MODES} 之一；不传时新建默认 {@code none}。 */
    public String authMode;
    /**
     * token 明文，<b>只进不出</b>：写入时加密落库，任何响应都不回显（只给 {@code hasToken}）。
     *
     * <p>空串 / {@code null} = <b>保持原值</b>（不是清空）。这是照 {@code ComputePutReq}
     * 的惯例 —— 表单里那一栏在编辑态本来就是空的，若把「空」当清空，改一次标题就会把
     * token 抹掉，且用户看不出发生了什么。真要清空得把 {@link #authMode} 改掉。
     */
    public String token;
    /** 账号密码认证的用户名（明文，不算秘密）。 */
    public String basicUser;
    /** 账号密码认证的密码明文，处置同 {@link #token}。 */
    public String password;
    /** {@link #VISIBILITIES} 之一；不传时新建默认 {@code all}。只对外链菜单生效。 */
    public String visibility;
  }

  /**
   * 入口页要挂的一个产品清单节点（V30）—— {@code {product, ref}} 两个字段就够，
   * 因为产品清单里的节点在 {@code nav_nodes} 里没有行、没有任何本地配置可写。
   *
   * <p>V31 起这是<b>老写法</b>：新前端发的是 {@code links}（{@link NavEntryLinkReq}），
   * 因为产品与本站菜单的 Tab 是混排的，两个数组拼不回一个顺序，也带不了 Tab 名称。
   */
  public static class NavProductLinkReq {
    /** 产品码，见 {@link ProductCodes#KNOWN}。 */
    public String product;
    /** 产品清单（{@code {frontendUrl}/menu.json}）里那个节点的 id。 */
    public String ref;
  }

  /**
   * 入口页要挂的一个 Tab（V31）。<b>数组顺序就是 Tab 顺序</b>（用户裁定：行顺序 = Tab 顺序）。
   *
   * <p>{@code kind} 决定读哪几个字段（另一个的字段忽略）：
   *
   * <ul>
   *   <li>{@code node} —— 本站菜单，读 {@link #target}（{@code nav_nodes.id}）；</li>
   *   <li>{@code product} —— 产品清单节点，读 {@link #product} + {@link #ref}。</li>
   * </ul>
   *
   * <p>{@link #label} 是这一条 Tab 自己显示的名字，<b>空 = 没改名</b>（用被挂菜单自己的标题）。
   * 这是本次加它的原因：同一个入口页挂两个同名菜单（比如两个「概况」）时，
   * 不改名就分不出谁是谁。
   */
  public static class NavEntryLinkReq {
    /** {@code node} 或 {@code product}，见 {@link #KIND_NODE} / {@link #KIND_PRODUCT}。 */
    public String kind;
    /** {@code kind = node} 时必填：本站菜单的 {@code nav_nodes.id}。 */
    public String target;
    /** {@code kind = product} 时必填：产品码，见 {@link ProductCodes#KNOWN}。 */
    public String product;
    /** {@code kind = product} 时必填：产品清单（{@code {frontendUrl}/menu.json}）里的节点 id。 */
    public String ref;
    /** 这一条 Tab 显示的名字；空 = 用被挂菜单自己的标题。最长 {@link #LABEL_MAX} 个字符。 */
    public String label;
  }

  /**
   * 「上移 / 下移一格」的请求体（{@code POST /platform/nav-nodes/{id}/move}，见 {@link #move}）。
   *
   * <p>只有这一个字段，刻意的：同层由被移动的节点自身决定。
   */
  public static class MoveReq {
    /** {@code -1} = 上移，{@code 1} = 下移。其余值 400 —— 没有「移 N 格」，那需要另一个交互。 */
    public Integer delta;
  }
}
