package com.dwai.platform.meta;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dwai.platform.meta.entity.ProductRoleEntity;
import com.dwai.platform.meta.entity.ProductRolePermEntity;
import com.dwai.platform.meta.entity.ProjectMemberEntity;
import com.dwai.platform.meta.mapper.ProductRoleMapper;
import com.dwai.platform.meta.mapper.ProductRolePermMapper;
import com.dwai.platform.meta.mapper.ProjectMemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 产品角色：把「哪个角色有哪些权限」从 {@code Perms.java} 的硬编码矩阵变成可管理的数据。
 *
 * <p>规范出处见 {@code V20__product_roles.sql} 的顶部注释（{@code 06-runtime-modes.md}
 * :84/:99/:115）。<b>它替代的是判权的第一来源，不是唯一来源</b>：{@code AccessService}
 * 先问本服务，表里没有这个 (产品, 角色码) 才回落 {@code Perms} —— 于是标准/独立模式
 * （读本地 {@code project_members} 后直接调 {@code Perms}）与历史数据都不需要一起迁移。
 *
 * <h2>权限词的校验分两层，缺一不可</h2>
 *
 * <ul>
 *   <li><b>形状</b>（{@link PermWords}）：永远是「域:动作」，动作五档。写错形状是拼写错误，
 *       任何情况下都拒。</li>
 *   <li><b>归属</b>：该产品自报的词表里有没有这个词（{@link MenuCandidateService#knownPerms}）。
 *       词表拿不到时<b>不拒</b> —— 理由见下面 {@link #checkPerms}。</li>
 * </ul>
 */
@Service
public class ProductRoleService {

  /**
   * 角色码的形状。
   *
   * <p>长度上限 32 不是随手定的：它要原样写进 {@code project_members.role VARCHAR(32)}，
   * 两条线用同一个字符串判权，超长会被方言截断或不报错地存进去，然后判权两边对不上。
   *
   * <p>首字符限小写字母（不许下划线、数字开头）：角色码会出现在 URL、
   * {@code authz/check} 的 query 参数与日志里，允许 `-`/`.` 之类会带来转义问题。
   */
  private static final Pattern CODE_SHAPE = Pattern.compile("^[a-z][a-z0-9_]{1,31}$");

  private static final int LABEL_MAX = 64;
  private static final int HINT_MAX = 255;

  private final ProductRoleMapper mapper;
  private final ProductRolePermMapper perms;
  private final ProjectMemberMapper members;
  private final MenuCandidateService candidates;

  public ProductRoleService(
      ProductRoleMapper mapper,
      ProductRolePermMapper perms,
      ProjectMemberMapper members,
      MenuCandidateService candidates) {
    this.mapper = mapper;
    this.perms = perms;
    this.members = members;
    this.candidates = candidates;
  }

  // ------------------------------------------------------------------
  // 管理面
  // ------------------------------------------------------------------

  /** 全部角色，按「产品 → 组内顺序 → 角色码」排。平台管理员用。 */
  public List<Map<String, Object>> all(String product) {
    LambdaQueryWrapper<ProductRoleEntity> q = new LambdaQueryWrapper<ProductRoleEntity>()
        .orderByAsc(ProductRoleEntity::getProduct)
        .orderByAsc(ProductRoleEntity::getSortOrder)
        .orderByAsc(ProductRoleEntity::getCode);
    if (product != null && !product.isBlank()) q.eq(ProductRoleEntity::getProduct, product.trim());

    List<ProductRoleEntity> rows = mapper.selectList(q);
    Map<String, List<String>> permsByRole = permsOf(rows);

    List<Map<String, Object>> out = new ArrayList<>(rows.size());
    for (ProductRoleEntity row : rows) {
      out.add(toRow(row, permsByRole.getOrDefault(row.getId(), List.of())));
    }
    return out;
  }

  public Map<String, Object> create(ProductRoleReq req) {
    ProductRoleEntity e = new ProductRoleEntity();
    e.setId("prole-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
    apply(e, req, true);
    e.setBuiltin(false); // 管理面建出来的一律不是内置；内置只由迁移写入
    e.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));

    Set<String> words = checkPerms(e.getProduct(), req == null ? null : req.perms);
    insertOrConflict(e);
    writePerms(e.getId(), words);

    if (Boolean.TRUE.equals(e.getIsAdmin())) demoteOthers(e.getProduct(), e.getId());
    return toRow(e, List.copyOf(words));
  }

  /**
   * 改角色名 / 说明 / 顺序 / 权限词 / 是否管理角色。
   *
   * <p><b>产品与角色码不能改</b>（照 {@code NavNodeService} 拒绝改 scope 的写法）：
   * 两者一起构成角色身份（唯一键 {@code (product, code)}），允许改就是「换一个角色」，
   * 该走删 + 建。而且角色码还写在 {@code project_members.role} 里，改了它所有持有者
   * 会在一瞬间失去权限 —— 那种变更必须显式发生，不能藏在一个「编辑」里。
   */
  @Transactional
  public Map<String, Object> update(String id, ProductRoleReq req) {
    ProductRoleEntity e = require(id);
    if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体");

    if (req.product != null && !req.product.trim().equals(e.getProduct())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "产品不能改（它是角色身份的一部分）");
    }
    if (req.code != null && !req.code.trim().equals(e.getCode())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色码不能改（它写在成员记录里，改了持有者会立刻失去权限）");
    }

    // 摘掉管理标记是「让这个产品没有管理角色」，必须显式拒绝：租户管理员在该产品里
    // 是短路映射到管理角色的（AccessService.roleOf），没有管理角色 = 管理员自己也进不去。
    // 正确的做法是把管理标记先切给另一个角色（那时本角色会自动降级）。
    if (Boolean.FALSE.equals(req.isAdmin) && Boolean.TRUE.equals(e.getIsAdmin())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "每个产品必须有一个管理角色。要换就给另一个角色设上管理标记，这个会自动降级");
    }

    apply(e, req, false);
    Set<String> words = req.perms == null ? null : checkPerms(e.getProduct(), req.perms);
    insertOrConflict(e);

    Set<String> effective = words;
    if (effective == null) {
      effective = new LinkedHashSet<>();
      for (ProductRolePermEntity p : rowsOf(e.getId())) effective.add(p.getPerm());
    } else {
      writePerms(e.getId(), effective);
    }
    if (Boolean.TRUE.equals(e.getIsAdmin())) demoteOthers(e.getProduct(), e.getId());
    return toRow(e, List.copyOf(effective));
  }

  /**
   * 删角色。<b>不阻塞、不级联清空成员的角色列。</b>
   *
   * <p>返回 {@code referenced} = 仍写着这个角色码的成员人次。与 {@code NavNodeService.delete}
   * 同一个取舍：顺手把成员的角色改掉等于<b>静默改人的权限</b>，而管理员删的是一个角色配置。
   * 那 N 个人此后在该产品下判否（表里没这个角色、{@code Perms} 也没有），是可见且可解释的；
   * 悄悄把他们降成 viewer 则是不可见的。
   */
  @Transactional
  public Map<String, Object> delete(String id) {
    ProductRoleEntity e = require(id);
    if (Boolean.TRUE.equals(e.getBuiltin())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "内置角色不能删（「" + e.getLabel() + "」）。它对应产品出厂时的固定角色，删了就没有兜底了");
    }
    if (Boolean.TRUE.equals(e.getIsAdmin())) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "管理角色不能删。要换就给另一个角色设上管理标记，这个会自动降级");
    }

    long referenced = countHolders(e.getProduct(), e.getCode());
    perms.delete(new LambdaQueryWrapper<ProductRolePermEntity>()
        .eq(ProductRolePermEntity::getRoleId, id));
    mapper.deleteById(id);

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("referenced", referenced);
    // 提醒调用方：这个角色码从此判否。数字为 0 时也返回，前端照样显示「无人在用」。
    out.put("code", e.getCode());
    return out;
  }

  // ------------------------------------------------------------------
  // 判权与成员校验（被 AccessService / ProjectService 调用）
  // ------------------------------------------------------------------

  /**
   * 该角色有哪些权限词。
   *
   * <p><b>返回 {@code Optional.empty()} 表示「表里没有这个角色」</b>，调用方据此回落
   * 到 {@code Perms} 的硬编码矩阵。而 {@code Optional.of(空集)} 是另一个意思：
   * 角色存在、但一项权限都没配 —— 那是「什么都不让做」，<b>不是</b>「不知道」。
   * 两者塌成一个会让一个刚建好、还没配权限的角色静默拿到硬编码矩阵的权限。
   *
   * <p>不做缓存：管理员勾完权限保存，下一次判权就该按新的来。缓存会带来「改完不生效」
   * 这种最难解释的现象，而这里的查询是唯一索引上的单点查，代价可以忽略。
   */
  public Optional<Set<String>> permsOf(String product, String code) {
    ProductRoleEntity role = find(product, code);
    if (role == null) return Optional.empty();
    Set<String> out = new LinkedHashSet<>();
    for (ProductRolePermEntity p : rowsOf(role.getId())) out.add(p.getPerm());
    return Optional.of(out);
  }

  /**
   * 这个 (产品, 角色码) 是不是一个已登记的角色。
   *
   * <p>给 {@code ProjectService.putMember} 用：那里原来写死 {@code admin/modeler/viewer}
   * 三值，产品专属角色码（{@code catalog_admin} 那类）根本存不进去。改成问本表后，
   * 管理员在平台上建的角色立刻可派。
   */
  public boolean exists(String product, String code) {
    return find(product, code) != null;
  }

  /** 某产品下所有已登记的角色码，用于错误信息里列出可用值。 */
  public Set<String> codesOf(String product) {
    if (product == null || product.isBlank()) return Set.of();
    Set<String> out = new LinkedHashSet<>();
    for (ProductRoleEntity e : mapper.selectList(new LambdaQueryWrapper<ProductRoleEntity>()
        .eq(ProductRoleEntity::getProduct, product.trim())
        .orderByAsc(ProductRoleEntity::getSortOrder)
        .orderByAsc(ProductRoleEntity::getCode))) {
      out.add(e.getCode());
    }
    return out;
  }

  // ------------------------------------------------------------------
  // 内部
  // ------------------------------------------------------------------

  private void apply(ProductRoleEntity e, ProductRoleReq req, boolean creating) {
    if (req == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少请求体");

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

    if (req.code != null) {
      String code = req.code.trim();
      if (!CODE_SHAPE.matcher(code).matches()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "角色码「" + code + "」不合法：只能用小写字母、数字、下划线，字母开头，长度 2-32");
      }
      e.setCode(code);
    } else if (creating) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 code");
    }

    if (req.label != null) {
      String label = req.label.trim();
      if (label.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色名不能为空");
      // 显式校验而不是靠列宽兜底：超长时方言要么报错要么截断，截断尤其糟 ——
      // 角色名被悄悄改短，管理员看到的名字与侧栏/成员页显示的不一致。
      if (label.length() > LABEL_MAX) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "角色名最长 " + LABEL_MAX + " 个字符，当前 " + label.length());
      }
      e.setLabel(label);
    } else if (creating) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 label");
    }

    if (req.hint != null) {
      String hint = req.hint.trim();
      if (hint.length() > HINT_MAX) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "说明最长 " + HINT_MAX + " 个字符，当前 " + hint.length());
      }
      e.setHint(hint);
    } else if (creating) {
      e.setHint("");
    }

    if (req.sortOrder != null) e.setSortOrder(req.sortOrder);
    else if (creating) e.setSortOrder(0);

    if (req.isAdmin != null) e.setIsAdmin(req.isAdmin);
    else if (creating) e.setIsAdmin(false);
  }

  /**
   * 校验权限词集合，返回去重后的结果。
   *
   * <p><b>词表拿不到时不拒</b>（{@code known} 为空集）：另一个选择是「拿不到就拒」，
   * 那意味着某个产品在「服务注册」里的页面地址一没登记，管理员就建不了这个产品的任何
   * 角色 —— 一处配置缺失把整个功能锁死，且报错信息（「产品不认这个词」）还指向错误的方向。
   * 拿不到时只做形状校验，前端在下拉为空时也会提示「该产品未上报词表，可手填」。
   *
   * <p>显式空串会被跳过而不是拒：前端多选控件在某些状态下会带出一个空选项，
   * 为它抛 400 会让管理员看到一个自己没做过的操作报错。而「这个角色一项权限都没有」
   * 本身是合法的中间状态（先建好角色、稍后再配）。
   */
  private Set<String> checkPerms(String product, List<String> raw) {
    Set<String> out = new LinkedHashSet<>();
    if (raw == null) return out;

    Set<String> known = candidates.knownPerms(product);
    for (String item : raw) {
      String perm = PermWords.requireWellFormed(item, "权限词");
      if (perm.isEmpty()) continue;
      if (!known.isEmpty() && !known.contains(perm)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "产品「" + product + "」不认权限词「" + perm + "」。它上报的词表是：" + known);
      }
      out.add(perm);
    }
    return out;
  }

  /** 全量替换一个角色的权限词。 */
  private void writePerms(String roleId, Collection<String> words) {
    perms.delete(new LambdaQueryWrapper<ProductRolePermEntity>()
        .eq(ProductRolePermEntity::getRoleId, roleId));
    for (String word : words) perms.insert(new ProductRolePermEntity(roleId, word));
  }

  /**
   * 把该产品其他角色的管理标记清掉 —— 保证「恰有一个管理角色」。
   *
   * <p>这是<b>让操作成功</b>（而不是报错让管理员先手动降级再重试）：
   * 「我要让 B 当管理员」的意图里已经隐含了「A 不再是管理员」。
   */
  private void demoteOthers(String product, String keepId) {
    ProductRoleEntity patch = new ProductRoleEntity();
    patch.setIsAdmin(false);
    mapper.update(patch, new LambdaUpdateWrapper<ProductRoleEntity>()
        .eq(ProductRoleEntity::getProduct, product)
        .eq(ProductRoleEntity::getIsAdmin, true)
        .ne(ProductRoleEntity::getId, keepId));
  }

  private long countHolders(String product, String code) {
    return members.selectCount(new LambdaQueryWrapper<ProjectMemberEntity>()
        .eq(ProjectMemberEntity::getProduct, product)
        .eq(ProjectMemberEntity::getRole, code));
  }

  private ProductRoleEntity find(String product, String code) {
    if (product == null || code == null) return null;
    return mapper.selectOne(new LambdaQueryWrapper<ProductRoleEntity>()
        .eq(ProductRoleEntity::getProduct, product.trim())
        .eq(ProductRoleEntity::getCode, code.trim()));
  }

  private List<ProductRolePermEntity> rowsOf(String roleId) {
    return perms.selectList(new LambdaQueryWrapper<ProductRolePermEntity>()
        .eq(ProductRolePermEntity::getRoleId, roleId)
        .orderByAsc(ProductRolePermEntity::getPerm));
  }

  /** 一次查完这批角色的权限词，避免列表页 N+1。 */
  private Map<String, List<String>> permsOf(List<ProductRoleEntity> roles) {
    if (roles.isEmpty()) return Map.of();
    List<String> ids = roles.stream().map(ProductRoleEntity::getId).toList();
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (ProductRolePermEntity p : perms.selectList(new LambdaQueryWrapper<ProductRolePermEntity>()
        .in(ProductRolePermEntity::getRoleId, ids)
        .orderByAsc(ProductRolePermEntity::getPerm))) {
      out.computeIfAbsent(p.getRoleId(), k -> new ArrayList<>()).add(p.getPerm());
    }
    return out;
  }

  /** {@code (product, code)} 上有唯一约束，撞了要变成 400 而不是 500 —— 这是可预期的用户错误。 */
  private void insertOrConflict(ProductRoleEntity e) {
    try {
      if (mapper.selectById(e.getId()) == null) mapper.insert(e);
      else mapper.updateById(e);
    } catch (DuplicateKeyException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "产品「" + e.getProduct() + "」下已经有角色码「" + e.getCode() + "」了");
    }
  }

  private ProductRoleEntity require(String id) {
    ProductRoleEntity e = id == null ? null : mapper.selectById(id);
    if (e == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
    return e;
  }

  /**
   * 行形状。<b>权限词带上人话标签</b>（取自产品自报的词表），管理页直接显示
   * 「查看 / 编辑」而不是 {@code catalog:read} —— 标签由产品定义，org 不自己编一套。
   * 拿不到标签时留空，前端回落显示 value。
   */
  private Map<String, Object> toRow(ProductRoleEntity e, Collection<String> words) {
    Map<String, String> labels = new LinkedHashMap<>();
    for (Map<String, Object> option : candidates.permOptions(e.getProduct())) {
      Object value = option.get("value");
      Object label = option.get("label");
      labels.put(String.valueOf(value), label == null ? "" : String.valueOf(label));
    }

    List<Map<String, Object>> permRows = new ArrayList<>(words.size());
    for (String word : words) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("value", word);
      row.put("label", labels.getOrDefault(word, ""));
      permRows.add(row);
    }

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", e.getId());
    row.put("product", e.getProduct());
    row.put("code", e.getCode());
    row.put("label", e.getLabel());
    row.put("hint", e.getHint());
    row.put("isAdmin", Boolean.TRUE.equals(e.getIsAdmin()));
    row.put("builtin", Boolean.TRUE.equals(e.getBuiltin()));
    row.put("sortOrder", e.getSortOrder());
    row.put("createdAt", e.getCreatedAt() == null ? "" : e.getCreatedAt().toString());
    row.put("perms", permRows);
    row.put("permCount", permRows.size());
    return row;
  }

  /** 管理面请求体。字段可空 = 「不改」，只有新建时才会用缺省值补。 */
  public static class ProductRoleReq {
    /** {@link ProductCodes#KNOWN} 之一。新建必填，改时不能变。 */
    public String product;
    /** 角色码（写进 {@code project_members.role}）。新建必填，改时不能变。 */
    public String code;
    public String label;
    public String hint;
    public Integer sortOrder;
    /** 是否该产品的管理角色。每个产品恰有一个 TRUE，切换时原来那个自动降级。 */
    public Boolean isAdmin;
    /** 权限词全集，<b>全量替换</b>语义。不传 = 不改。 */
    public List<String> perms;
  }
}
