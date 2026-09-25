package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dwai.platform.meta.entity.NavGroupEntity;
import com.dwai.platform.meta.entity.NavItemEntity;
import com.dwai.platform.meta.mapper.NavGroupMapper;
import com.dwai.platform.meta.mapper.NavItemMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 门户菜单分组：管理面（平台管理员提前把分组建好）与消费面（侧栏读分组元数据）。
 *
 * <p><b>它与 {@link NavItemService} 的关系是软约束</b>，没有外键：菜单项里的
 * {@code group_title} 仍是自由字符串，本表只是「下拉候选 + 组间顺序 + 空组策略」。
 * 之所以不能做成强约束，是因为仓建设的「建模中心」等分组是按项目分层动态生成的，
 * 天生进不了静态分组表 —— 详见 {@code V19__nav_groups.sql} 顶部注释。
 *
 * <p><b>由此推出本类最重要的一条设计</b>：{@link #apply} 绝不去校验
 * {@code nav_items.group_title} 是否出现在本表里。手工填一个未登记的分组名必须
 * 仍然能落库，否则动态分组当场就坏了。
 *
 * <p>与 {@code NavItemService} 一样两条线共用一个 service，但鉴权不同：
 * 管理面走 {@code PlatformController}（{@code requirePlatform()}），消费面走
 * {@code NavController}（登录 + 已选租户）。分组是配置的投影，读它不该要平台权限。
 */
@Service
public class NavGroupService {

  /**
   * {@code nav_groups.empty_policy} 的白名单。
   *
   * <p>两个取值各有一条规范依据，不是可选项的自助餐：
   * {@code always} 对应「数据地图分组始终出现，未开通或未派角色时禁用并说明」，
   * {@code hide} 对应「启航未配则项目无调度分组」。
   *
   * <p>与 {@link NavItemService#SCOPES} 同样的「写入时就拒绝」：写进一个谁也不认的
   * 策略名，表现为接口 200、行为却按某个没人预期的默认值走，没有任何报错。
   */
  public static final Set<String> EMPTY_POLICIES = Set.of("always", "hide");

  /** 与 {@code nav_items.group_title} 同宽（见 {@code V19__nav_groups.sql}）。 */
  private static final int TITLE_MAX = 64;

  /** 空组策略里「对用户承诺更强」的那一个；同名合并时用它（见前端 {@code groupMenus}）。 */
  public static final String ALWAYS = "always";

  /** 默认策略，与迁移里的列默认值一致 = 现状行为（空分组不渲染）。 */
  public static final String HIDE = "hide";

  private final NavGroupMapper mapper;
  private final NavItemMapper navItems;

  public NavGroupService(NavGroupMapper mapper, NavItemMapper navItems) {
    this.mapper = mapper;
    this.navItems = navItems;
  }

  // ------------------------------------------------------------------
  // 管理面
  // ------------------------------------------------------------------

  /**
   * 全部分组，按「壳 → 产品 → 组间顺序 → 组名」排。平台管理员用。
   *
   * <p>过滤参数是可选的：菜单管理页顶部有壳 + 产品两个筛选，与菜单项列表同一套。
   */
  public List<Map<String, Object>> all(String scope, String product) {
    LambdaQueryWrapper<NavGroupEntity> q = new LambdaQueryWrapper<NavGroupEntity>()
        .orderByAsc(NavGroupEntity::getScope)
        .orderByAsc(NavGroupEntity::getProduct)
        .orderByAsc(NavGroupEntity::getSortOrder)
        .orderByAsc(NavGroupEntity::getTitle);
    if (scope != null && !scope.isBlank()) q.eq(NavGroupEntity::getScope, scope.trim());
    if (product != null && !product.isBlank()) q.eq(NavGroupEntity::getProduct, product.trim());

    List<NavGroupEntity> rows = mapper.selectList(q);
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (NavGroupEntity row : rows) out.add(toRow(row));
    return out;
  }

  public Map<String, Object> create(NavGroupReq req) {
    NavGroupEntity e = new NavGroupEntity();
    // 与 nav_items 的 nav- 前缀同一套生成惯例（见 NavItemService#create）
    e.setId("grp-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    apply(e, req, true);
    e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    insertOrConflict(e);
    return toRow(e);
  }

  /**
   * 改分组名 / 组间顺序 / 空组策略。改名时级联，见 {@link #cascadeRename}。
   *
   * <p>返回体里额外带 {@code renamedItems}：改了名时告诉管理员「顺带更新了几条菜单项」。
   * 不返回的话，管理员改完名看不到任何数字，只能自己去菜单列表里翻。
   */
  @Transactional
  public Map<String, Object> update(String id, NavGroupReq req) {
    NavGroupEntity e = require(id);
    if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体");

    // scope/product 是分组身份的一部分（唯一键就是 scope+product+title）：
    // 允许改就会与「改名」语义撞车 —— 到底是改名还是搬家？前端把它们做成只读，
    // 这里也显式拒掉，免得 curl 调用者拿到一个自己没预期的结果。
    if (req.scope != null && !req.scope.trim().equals(e.getScope())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归属壳不能改（它是分组身份的一部分）");
    }
    if (req.product != null && !req.product.trim().equals(e.getProduct())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "产品不能改（它是分组身份的一部分）");
    }

    String oldTitle = e.getTitle();
    apply(e, req, false);
    insertOrConflict(e);

    int renamed = oldTitle.equals(e.getTitle())
        ? 0
        : cascadeRename(e.getScope(), e.getProduct(), oldTitle, e.getTitle());

    Map<String, Object> out = toRow(e);
    out.put("renamedItems", renamed);
    return out;
  }

  /**
   * 删掉一个分组。<b>不阻塞、不级联清空菜单项的分组名。</b>
   *
   * <p>返回 {@code referenced} = 仍写着这个名字的菜单项条数，前端据此提示
   * 「已删除，但有 N 条菜单仍写着这个名字，它们照常显示」。为什么不顺手把它们
   * 的 {@code group_title} 清掉：那等于<b>静默改菜单</b>—— 管理员删的是一个分组
   * 配置，不该连带菜单的观感一起变。要看效果就显式去改菜单项。
   */
  public Map<String, Object> delete(String id) {
    NavGroupEntity e = require(id);
    long referenced = countItems(e.getScope(), e.getProduct(), e.getTitle());
    mapper.deleteById(id);
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("referenced", referenced);
    return out;
  }

  /**
   * 把请求体套到实体上，并校验。
   *
   * <p><b>这里刻意没有「这个分组名有没有菜单项引用」这类校验</b>，也没有反过来的
   * 「菜单项的分组名有没有登记」。软约束就是软约束，两个方向都不该拦（见类注释）。
   *
   * @param creating 新建时缺省值才生效（改已有项时字段为空 = 不改，而不是清空）
   */
  private void apply(NavGroupEntity e, NavGroupReq req, boolean creating) {
    if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体");

    if (req.scope != null) {
      String scope = req.scope.trim();
      if (!NavItemService.SCOPES.contains(scope)) {
        // 复用菜单项那套白名单而不是新开一份：分组的 scope 与菜单项的 scope
        // 必须是同一套值，否则「分组挂项目壳、菜单挂工作台壳」永远匹配不上，
        // 表现为分组建好了、侧栏毫无变化。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知归属壳 " + scope + "。可用值：" + NavItemService.SCOPES);
      }
      e.setScope(scope);
    } else if (creating) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 scope");
    }

    if (req.product != null) {
      String product = req.product.trim();
      if (!ProductCodes.isKnown(product)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知产品码 " + product + "。可用值：" + ProductCodes.KNOWN);
      }
      e.setProduct(product);
    } else if (creating) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 product");
    }

    if (req.title != null) {
      String title = req.title.trim();
      if (title.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分组名不能为空");
      // 显式校验而不是靠列宽兜底：超长时由哪个方言、以什么形态报错是不确定的
      // （MySQL 严格模式报错、宽松模式截断）。截断尤其糟 —— 分组名被悄悄改短，
      // 与菜单项里写的那个名字就对不上了。
      if (title.length() > TITLE_MAX) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "分组名最长 " + TITLE_MAX + " 个字符，当前 " + title.length());
      }
      e.setTitle(title);
    } else if (creating) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 title");
    }

    if (req.sortOrder != null) e.setSortOrder(req.sortOrder);
    else if (creating) e.setSortOrder(0);

    if (req.emptyPolicy != null) {
      String policy = req.emptyPolicy.trim();
      if (!EMPTY_POLICIES.contains(policy)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知空组策略 " + policy + "。可用值：" + EMPTY_POLICIES);
      }
      e.setEmptyPolicy(policy);
    } else if (creating) {
      e.setEmptyPolicy(HIDE);
    }
  }

  /**
   * 改名级联：把同壳同产品下所有写着旧名的菜单项一起改成新名，返回受影响条数。
   *
   * <p><b>为什么必须级联</b>：侧栏的分组标题来自菜单项的 {@code group_title}（字符串），
   * 分组表只是元数据。不级联的话，管理员改完名侧栏仍显示旧名，而新名会以一个
   * 「已登记但空」的分组出现 —— 看起来就是改名没生效。软约束意味着字符串仍是
   * 渲染真源，级联只是<b>显式地把真源同步过去</b>，这正是「分组管理」相对
   * 「手打分组名」的全部价值。
   *
   * <p>只动旧名匹配的行：手工填的、别的名字的、以及动态生成的分组名一概不受影响。
   * 级联范围限定在同一个 {@code (scope, product)} 内 —— 另一个产品下同名的分组
   * 是另一个分组，不该被这次改名波及。
   */
  private int cascadeRename(String scope, String product, String oldTitle, String newTitle) {
    NavItemEntity patch = new NavItemEntity();
    patch.setGroupTitle(newTitle);
    return navItems.update(patch, new LambdaUpdateWrapper<NavItemEntity>()
        .eq(NavItemEntity::getScope, scope)
        .eq(NavItemEntity::getProduct, product)
        .eq(NavItemEntity::getGroupTitle, oldTitle));
  }

  /** {@code (scope, product, title)} 上有唯一约束，撞了要变成 400 而不是 500 —— 这是可预期的用户错误。 */
  private void insertOrConflict(NavGroupEntity e) {
    try {
      if (mapper.selectById(e.getId()) == null) mapper.insert(e);
      else mapper.updateById(e);
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "这个壳下已经有叫「" + e.getTitle() + "」的分组了（" + e.getScope() + " / " + e.getProduct() + "）");
    }
  }

  private long countItems(String scope, String product, String title) {
    return navItems.selectCount(new LambdaQueryWrapper<NavItemEntity>()
        .eq(NavItemEntity::getScope, scope)
        .eq(NavItemEntity::getProduct, product)
        .eq(NavItemEntity::getGroupTitle, title));
  }

  private NavGroupEntity require(String id) {
    NavGroupEntity e = id == null ? null : mapper.selectById(id);
    if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "分组不存在");
    return e;
  }

  // ------------------------------------------------------------------
  // 消费面
  // ------------------------------------------------------------------

  /**
   * 侧栏用的分组元数据（全部分组）。
   *
   * <p><b>刻意不做许可过滤</b>，这与 {@link NavItemService#menuFor} 的直觉相反，
   * 但正是 {@code always} 策略的要求：规范要「数据地图」在租户<b>未开通</b>时
   * 也出现在侧栏（置灰并说明）。按许可先滤一道，那个分组就永远不会出现，
   * 空组策略也就成了死代码。
   *
   * <p>代价是未开通产品的分组名也返回了。可接受：分组名不是敏感信息，而 {@code hide}
   * 分组的元数据多返回一份也无害 —— 前端据「这一组有没有可用菜单项」自行决定不渲染。
   * <b>不要在以后「顺手」补一个许可过滤</b>：{@code NavGroupTest} 有一条专门钉住这条。
   *
   * <p>租户拿不到时返回空列表而不是报错，理由同 {@code menuFor}：侧栏是每个页面
   * 都要画的东西，让它因为「没选租户」而 500 会把整个壳带下水。
   */
  public List<Map<String, Object>> groupsFor(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) return List.of();
    return all(null, null);
  }

  /**
   * 管理面与消费面共用一个行形状。
   *
   * <p>不拆成两个方法：字段完全重合（消费面只要 title / sortOrder / emptyPolicy，
   * 但 scope / product 要用来判归属），拆开只会让两边慢慢漂移。多暴露的 id / createdAt
   * 对租户成员没有利用价值 —— 写管理面仍要平台权限。
   */
  private static Map<String, Object> toRow(NavGroupEntity e) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", e.getId());
    row.put("scope", e.getScope());
    row.put("product", e.getProduct());
    row.put("title", e.getTitle());
    row.put("sortOrder", e.getSortOrder());
    row.put("emptyPolicy", e.getEmptyPolicy());
    row.put("createdAt", e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
    return row;
  }

  /** 管理面请求体。字段可空 = 「不改」，只有新建时才会用缺省值补。 */
  public static class NavGroupReq {
    /** {@code NavItemService.SCOPES} 之一。新建必填，改时不能变。 */
    public String scope;
    /** {@link ProductCodes#KNOWN} 之一。新建必填，改时不能变。 */
    public String product;
    public String title;
    public Integer sortOrder;
    /** {@link #EMPTY_POLICIES} 之一；不传时新建默认 {@link #HIDE}（= 现状行为）。 */
    public String emptyPolicy;
  }
}
