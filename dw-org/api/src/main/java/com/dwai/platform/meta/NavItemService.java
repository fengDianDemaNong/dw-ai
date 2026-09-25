package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dwai.platform.internal.ServiceRegistry;
import com.dwai.platform.meta.entity.NavItemEntity;
import com.dwai.platform.meta.entity.TenantLicenseEntity;
import com.dwai.platform.meta.mapper.NavItemMapper;
import com.dwai.platform.meta.mapper.TenantLicenseMapper;
import com.dwai.platform.meta.support.Jsons;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 门户菜单：管理面（平台管理员配）与消费面（租户成员读）。
 *
 * <p><b>两条线共用一个 service，但鉴权不同</b>：管理面走
 * {@code PlatformController}（{@code requirePlatform()}），消费面走
 * {@code NavController}（只要求登录 + 已选租户）。这是本模块最容易被做错的一处 ——
 * 消费面若也挂 {@code requirePlatform()}，普通租户成员的侧栏会永远是空的。
 *
 * <p>消费面返回的每一项都带上了 {@code frontendUrl}（来自 {@link ServiceRegistry}），
 * 前端因此不必再查一次服务表，也就少一次「服务表没配 → 菜单点了没反应」的失败模式。
 */
@Service
public class NavItemService {

  /** 工作台壳：进项目**之前**那一级（`/org/workbench/*`）。 */
  public static final String WORKBENCH = "workbench";
  /** 项目壳：进项目**之后**那一级（`/org/project/{code}/*`）。 */
  public static final String PROJECT = "project";

  /**
   * {@code nav_items.scope} 的白名单 —— 一条菜单项挂哪个壳。
   *
   * <p>与 {@link ProductCodes#KNOWN} 同样的「写入时就拒绝」：写进一个谁也不认的壳名，
   * 表现为接口 200、侧栏什么都不出现、没有任何报错，是最难查的一种坏法。
   */
  public static final Set<String> SCOPES = Set.of(WORKBENCH, PROJECT);

  private final NavItemMapper mapper;
  private final TenantLicenseMapper licenses;
  private final ServiceRegistry registry;
  /** 权限词的归属校验要向产品自报的词表求证，见 {@link #checkPerm}。 */
  private final MenuCandidateService candidates;
  /** 消费面按权限词过滤，用的是与判权同一处的那套规则（{@code roleHas}）。 */
  private final AccessService access;

  public NavItemService(
      NavItemMapper mapper,
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

  /** 这条菜单挂了权限词（挂了才需要判「这个人看不看得见」；没挂 = 进得来就看得见）。 */
  private static boolean hasPermWord(NavItemEntity row) {
    return row.getPerm() != null && !row.getPerm().isBlank();
  }

  // ------------------------------------------------------------------
  // 管理面
  // ------------------------------------------------------------------

  /** 全部菜单项（含停用的），按「壳 → 产品 → 顺序 → 路径」排。平台管理员用。 */
  public List<Map<String, Object>> all() {
    List<NavItemEntity> rows = mapper.selectList(
        new LambdaQueryWrapper<NavItemEntity>()
            .orderByAsc(NavItemEntity::getScope)
            .orderByAsc(NavItemEntity::getProduct)
            .orderByAsc(NavItemEntity::getSortOrder)
            .orderByAsc(NavItemEntity::getPath));
    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (NavItemEntity row : rows) out.add(toAdminRow(row));
    return out;
  }

  public Map<String, Object> create(NavItemReq req) {
    NavItemEntity e = new NavItemEntity();
    e.setId("nav-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    apply(e, req, true);
    e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
    insertOrConflict(e);
    return toAdminRow(e);
  }

  public Map<String, Object> update(String id, NavItemReq req) {
    NavItemEntity e = require(id);
    apply(e, req, false);
    insertOrConflict(e);
    return toAdminRow(e);
  }

  public void delete(String id) {
    require(id);
    mapper.deleteById(id);
  }

  /**
   * 把请求体套到实体上，并校验。
   *
   * @param creating 新建时缺省值才生效（改已有项时字段为空 = 不改，而不是清空）
   */
  private void apply(NavItemEntity e, NavItemReq req, boolean creating) {
    if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体");

    if (req.product != null) {
      String product = req.product.trim();
      if (!ProductCodes.isKnown(product)) {
        // 这里拒绝是为了把「配了个永远不会显示的菜单」挡在写入时。
        // 消费面按租户许可的模块名过滤，产品码与许可模块名对不上就静默不显示 ——
        // 那是最难查的一种坏法（接口 200、侧栏空白、没有报错）。
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知产品码 " + product + "。可用值：" + ProductCodes.KNOWN);
      }
      e.setProduct(product);
    } else if (creating) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 product");
    }

    if (req.label != null) {
      String label = req.label.trim();
      if (label.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "菜单名不能为空");
      e.setLabel(label);
    } else if (creating) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 label");
    }

    if (req.scope != null) {
      String scope = req.scope.trim();
      if (!SCOPES.contains(scope)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "未知归属壳 " + scope + "。可用值：" + SCOPES);
      }
      e.setScope(scope);
    } else if (creating) {
      e.setScope(WORKBENCH);
    }

    if (req.groupTitle != null) e.setGroupTitle(req.groupTitle.trim());
    else if (creating) e.setGroupTitle("");

    if (req.perm != null) e.setPerm(checkPerm(e.getProduct(), req.perm));
    else if (creating) e.setPerm("");

    if (req.path != null) e.setPath(normalizePath(req.path));
    else if (creating) e.setPath("/");

    if (req.icon != null) e.setIcon(req.icon.trim());
    else if (creating) e.setIcon("");

    if (req.sortOrder != null) e.setSortOrder(req.sortOrder);
    else if (creating) e.setSortOrder(0);

    if (req.enabled != null) e.setEnabled(req.enabled);
    else if (creating) e.setEnabled(Boolean.TRUE);

    if (e.getProduct() == null || e.getLabel() == null || e.getPath() == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "product / label / path 不能为空");
    }
  }

  /**
   * 校验一个菜单项挂的权限词。
   *
   * <h2>为什么这条校验必须在这一层</h2>
   *
   * {@code nav_items.perm} 此前<b>没有白名单</b>（只 {@code trim}），而同方法的
   * {@code product} / {@code scope} 都有。后果不是「少了个校验」这么轻：管理员可以配一个
   * 产品根本不认识的词（例如凭直觉写 {@code catalog:edit}），接口 200、保存成功，
   * 而那个菜单在侧栏里<b>永远置灰</b> —— 用户点进去才可能知道是坏的，管理员无从发现。
   *
   * <p><b>不能只在「新增菜单」的界面上过滤候选</b>：{@code PUT /platform/nav-items}
   * 是整体替换语义，任何一条都能从别的路径进来。收口必须发生在写入这一层。
   *
   * <h2>两层校验，第二层可以不生效</h2>
   *
   * <ul>
   *   <li><b>形状</b>（{@link PermWords}）：永远查。空串合法 = 不判权。</li>
   *   <li><b>归属</b>：该产品自报的词表里有没有这个词。词表拿不到时<b>跳过</b> ——
   *       某个服务在「服务注册」里的页面地址一没配，管理员就配不了它任何菜单，
   *       是拿一处配置缺失把整个功能锁死。</li>
   * </ul>
   *
   * <p>归属用 {@link MenuCandidateService#knownPerms} 而不是「聚合候选菜单里出现过的
   * {@code perm}」：后者会漏掉<b>不在任何菜单上</b>的词（{@code lineage:write} 只用在
   * SQL 解析页的保存按钮上），于是同一个词在「菜单页」被拒、在「角色页」能用，
   * 两个功能的词表真源必须是同一份。
   */
  private String checkPerm(String product, String raw) {
    String perm = PermWords.requireWellFormed(raw, "权限词");
    if (perm.isEmpty()) return "";

    Set<String> known = candidates.knownPerms(product);
    if (!known.isEmpty() && !known.contains(perm)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "产品「" + product + "」的菜单不能挂权限词「" + perm + "」。它上报的词表是：" + known);
    }
    return perm;
  }

  /**
   * 归一化子应用路径。
   *
   * <p>只做两件事：补前导 {@code /}、把连续的前导斜杠收成一个。第二件不是洁癖 ——
   * {@code //host} 在 URL 里是「协议相对地址」，虽然拼出来仍是同源（前缀是绝对地址），
   * 但让这种形态进库里，以后任何一处把它当成「以 / 开头的相对路径」用都会出岔子。
   */
  private static String normalizePath(String raw) {
    String p = raw.trim();
    if (p.isEmpty()) return "/";
    while (p.startsWith("//")) p = p.substring(1);
    if (!p.startsWith("/")) p = "/" + p;
    return p.length() > 512 ? p.substring(0, 512) : p;
  }

  /** {@code (scope, product, path)} 上有唯一约束，撞了要变成 400 而不是 500 —— 这是可预期的用户错误。 */
  private void insertOrConflict(NavItemEntity e) {
    try {
      if (mapper.selectById(e.getId()) == null) mapper.insert(e);
      else mapper.updateById(e);
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, conflictReason(e));
    }
  }

  private static String conflictReason(NavItemEntity e) {
    return "已经有指向 " + e.getPath() + " 的菜单项了（" + e.getScope() + " / " + e.getProduct() + "）";
  }

  /**
   * 批量新建（菜单管理页「拉取候选 → 勾选 → 保存」走这条路）。
   *
   * <p>为什么不做成前端循环调单条新建：一次勾 8~15 条就是同样次数的往返，
   * 而且部分失败时管理员只看到一串 toast，分不清哪几条进去了。
   * 这里逐条落、逐条报告 —— 已存在的归 {@code skipped}（重复点击是正常操作，不该报错），
   * 参数不合法的归 {@code failed} 并带上它在请求里的序号。
   */
  public Map<String, Object> createBatch(List<NavItemReq> reqs) {
    if (reqs == null || reqs.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少菜单项");
    }
    List<Map<String, Object>> created = new ArrayList<>();
    List<Map<String, Object>> skipped = new ArrayList<>();
    List<Map<String, Object>> failed = new ArrayList<>();

    for (int i = 0; i < reqs.size(); i++) {
      NavItemReq req = reqs.get(i);
      NavItemEntity e = new NavItemEntity();
      e.setId("nav-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
      try {
        apply(e, req, true);
      } catch (ResponseStatusException ex) {
        // 序号给 1 起的 —— 前端直接显示「第 N 项」，不必再各加一次 1
        failed.add(failure(i + 1, ex.getReason()));
        continue;
      }
      if (exists(e.getScope(), e.getProduct(), e.getPath())) {
        skipped.add(skipRow(e));
        continue;
      }
      e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
      try {
        mapper.insert(e);
      } catch (DuplicateKeyException ex) {
        // 并发下 exists 与 insert 之间仍有窗口，撞了按「已配置」处理，不报失败
        skipped.add(skipRow(e));
        continue;
      }
      created.add(toAdminRow(e));
    }

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("created", created);
    out.put("skipped", skipped);
    out.put("failed", failed);
    return out;
  }

  /** 同一壳下同产品同路径是否已经配过。菜单量级很小，逐条查足够。 */
  private boolean exists(String scope, String product, String path) {
    return mapper.selectCount(new LambdaQueryWrapper<NavItemEntity>()
        .eq(NavItemEntity::getScope, scope)
        .eq(NavItemEntity::getProduct, product)
        .eq(NavItemEntity::getPath, path)) > 0;
  }

  private static Map<String, Object> skipRow(NavItemEntity e) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("product", e.getProduct());
    row.put("path", e.getPath());
    row.put("scope", e.getScope());
    row.put("reason", "已配置");
    return row;
  }

  private static Map<String, Object> failure(int index, String reason) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("index", index);
    row.put("reason", reason == null ? "参数不合法" : reason);
    return row;
  }

  private NavItemEntity require(String id) {
    NavItemEntity e = id == null ? null : mapper.selectById(id);
    if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "菜单项不存在");
    return e;
  }

  // ------------------------------------------------------------------
  // 消费面
  // ------------------------------------------------------------------

  /**
   * 某租户里，**这个人**该看到哪些菜单。
   *
   * <p>过滤条件有三条：菜单项启用、其产品在该租户的许可里、以及这个人按权限词判得动。
   * <b>租户拿不到（平台管理员没选租户 / 租户不存在）时返回空列表而不是报错</b> ——
   * 侧栏是每个页面都要画的东西，让它因为「没选租户」而 500 会把整个壳带下水。
   *
   * <p><b>{@code projectId} 参与判权，所以调用方必须把「用户当前在哪个项目」传进来</b>
   * （壳里是 {@code X-Project-Id} 头，见 {@code TenantFilter}）。同一个产品在不同项目里
   * 可以是不同角色，传空就等于按租户级的角色判 —— 工作台（还没进项目）正是这种情况。
   */
  public List<Map<String, Object>> menuFor(String tenantId, String projectId) {
    if (tenantId == null || tenantId.isBlank()) return List.of();

    List<String> licensed = licensedModules(tenantId);
    if (licensed.isEmpty()) return List.of();

    List<NavItemEntity> rows = mapper.selectList(
        new LambdaQueryWrapper<NavItemEntity>()
            .eq(NavItemEntity::getEnabled, true)
            .in(NavItemEntity::getProduct, licensed)
            .orderByAsc(NavItemEntity::getSortOrder)
            .orderByAsc(NavItemEntity::getPath));

    // 权限词过滤用的角色：**按产品算一次**再逐条判定。
    // 一次 roleOf 要查成员关系 / 项目成员 / 平台授权三张表，逐条菜单去算会把它乘以菜单条数，
    // 而侧栏每次进项目、每次刷新都要重建一次。
    Set<String> needRole = new LinkedHashSet<>();
    for (NavItemEntity row : rows) {
      if (hasPermWord(row)) needRole.add(row.getProduct());
    }
    Map<String, String> roleByProduct = new HashMap<>();
    for (String product : needRole) {
      roleByProduct.put(product, access.currentRole(projectId, product).orElse(null));
    }

    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (NavItemEntity row : rows) {
      if (hasPermWord(row) && !access.roleHas(row.getProduct(), roleByProduct.get(row.getProduct()), row.getPerm())) {
        continue;
      }
      ServiceRegistry.Entry svc = registry.get(row.getProduct());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("product", row.getProduct());
      // 壳拿这一项分辨该挂工作台还是挂项目 —— 前端据此派生两套侧栏
      item.put("scope", row.getScope());
      item.put("groupTitle", row.getGroupTitle());
      item.put("label", row.getLabel());
      item.put("icon", row.getIcon());
      item.put("path", row.getPath());
      item.put("perm", row.getPerm());
      // 为空 = 这个产品还没登记页面地址。前端据此显示占位而不是嵌一个空 iframe
      item.put("frontendUrl", svc == null ? "" : svc.frontendUrl());
      out.add(item);
    }
    return out;
  }

  /**
   * 某租户已开通的产品及其前端地址（消费面）。
   *
   * <p><b>与 {@link #menuFor} 的区别</b>：菜单回答「侧栏有哪些入口」，按菜单表来，
   * 同一个产品配了几个菜单项就出现几次；这里回答「每个产品的站点根在哪」，按许可来，
   * 一个产品一条。
   *
   * <p>为什么不能直接复用菜单：菜单项是平台管理员手工配的，<b>开通了许可但没配菜单
   * 是完全正常的</b>（侧栏看不到入口，但「进入项目」按钮仍应能进去）。拿菜单当产品目录，
   * 那种租户点「进入项目」会拿到空地址 —— 与「服务注册里没配地址」表现完全一样，
   * 排查时分不清是哪一处没配。
   *
   * <p>取不到租户时返回空列表而不报错，理由同 {@link #menuFor}。
   */
  public List<Map<String, Object>> servicesFor(String tenantId) {
    if (tenantId == null || tenantId.isBlank()) return List.of();

    List<String> licensed = licensedModules(tenantId);
    List<Map<String, Object>> out = new ArrayList<>(licensed.size());
    for (String product : licensed) {
      ServiceRegistry.Entry svc = registry.get(product);
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("product", product);
      // 为空 = 「服务注册」里还没登记前端地址。前端据此报「未配置前端地址」，
      // 而不是跳到一个猜出来的默认端口上 —— 那个默认值在跨服务器部署时
      // 指向的是用户自己的 localhost。
      item.put("frontendUrl", svc == null ? "" : svc.frontendUrl());
      out.add(item);
    }
    return out;
  }

  /** 该租户开通的模块；没有许可行 = 一个模块都没开（fail-closed，与前端 {@code hasModule} 一致）。 */
  private List<String> licensedModules(String tenantId) {
    TenantLicenseEntity lic = licenses.selectById(tenantId);
    if (lic == null) return List.of();
    List<String> modules = Jsons.strings(lic.getModules());
    return modules == null ? List.of() : modules;
  }

  private static Map<String, Object> toAdminRow(NavItemEntity e) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", e.getId());
    row.put("product", e.getProduct());
    row.put("scope", e.getScope());
    row.put("groupTitle", e.getGroupTitle());
    row.put("label", e.getLabel());
    row.put("icon", e.getIcon());
    row.put("path", e.getPath());
    row.put("perm", e.getPerm());
    row.put("sortOrder", e.getSortOrder());
    row.put("enabled", Boolean.TRUE.equals(e.getEnabled()));
    row.put("createdAt", e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
    return row;
  }

  /** 批量新建的请求体（`{ "items": [ ... ] }`）。用包装对象而不是裸数组，给以后加字段留位置。 */
  public static class BatchNavItemsReq {
    public List<NavItemReq> items;
  }

  /** 管理面请求体。字段可空 = 「不改」，只有新建时才会用缺省值补。 */
  public static class NavItemReq {
    public String product;
    /** {@link #SCOPES} 之一；不传时新建默认 {@link #WORKBENCH}。 */
    public String scope;
    public String groupTitle;
    public String label;
    public String icon;
    public String path;
    public String perm;
    public Integer sortOrder;
    public Boolean enabled;
  }
}
