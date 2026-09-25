package com.dwai.platform.meta;

import com.dwai.platform.internal.ServiceRegistry;
import com.dwai.platform.meta.support.Jsons;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 拉各服务的菜单候选，供平台管理员在「菜单管理」里勾选。
 *
 * <h2>为什么是「按前端地址拉一个静态文件」</h2>
 *
 * 菜单的真源是各服务前端的 `config/navData.ts`（构建时由 `scripts/gen-menu.mjs`
 * 导出 `public/menu.json`）。org 这边按服务在「服务注册」里登记的<b>页面地址</b>
 * 去取 `{frontendUrl}/menu.json`。
 *
 * <p>这样<b>不需要新增「服务后端地址」字段</b>：取菜单用的是已经存在的那个地址，
 * 管理员的操作流程一行不变。反过来若让各服务开一个「内部接口吐菜单」，就得先解决
 * 「org 怎么知道每个服务的后端地址」——那正是心跳删除后刻意不再维护的东西
 * （见 {@link ServiceRegistry} 的说明）。
 *
 * <p>为什么不能更直接地"获取"：菜单里的 `path` 能从路由表推导，
 * 但<b>中文名、图标、分组、权限词推导不出来</b>，必须有人写。这份人工清单早就存在于
 * 各服务前端，本方案只是把它搬到一个可 HTTP 取到的位置，没有新增第二份。
 *
 * <h2>只缓存成功的结果</h2>
 *
 * 拉取失败（连不上 / 404 / 格式不对）<b>不落缓存</b>。这不是省事：管理员点「拉取」
 * 这个动作本身就带着「我刚才在服务注册里改了什么，现在看看对不对」的意图，
 * 把失败缓存住会让「改完再点一次」在几十秒内一直看到同一条旧错误 ——
 * 看起来像改了没用。菜单很小、拉取是管理员的一次点击，不存在打垮对方的风险。
 */
@Service
public class MenuCandidateService {
  private static final Logger log = LoggerFactory.getLogger(MenuCandidateService.class);

  /** 各服务前端构建时导出的候选清单，随产物一起发布。 */
  private static final String MENU_PATH = "/menu.json";

  /**
   * 连接/读取超时。
   *
   * <p>不设超时是危险的：{@code RestClient.builder()} 的默认 connect/read timeout 都是
   * 「无限等待」。某个服务的页面地址填成了一个「端口通但不响应」的地址时，
   * 管理员的这一次点击会永久占住一个 Tomcat 线程。
   *
   * <p>比项目同步那组（1s / 3s）宽：那个跑在后台、慢了要影响页面加载；
   * 这个只在管理员点「拉取」时发生，宁可多等一会儿也不要给出假的失败。
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  /** 成功结果缓存时长。菜单是配置，变化远慢于项目镜像。 */
  private static final Duration TTL = Duration.ofSeconds(300);

  /**
   * 词表专用的失败记忆时长，见 {@link #knownPerms}。
   *
   * <p>比 {@link #TTL} 短得多：它挡的不是「陈旧」，而是「连续网络超时」。
   * 产品发版才会改词表，一分钟的陈旧窗口没有实际代价。
   */
  private static final Duration PERM_FAIL_TTL = Duration.ofSeconds(60);

  private final ServiceRegistry registry;
  private final RestClient http;
  private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

  /**
   * 拉不到时记一笔，避免写入路径反复等满超时。
   *
   * <p><b>只用于 {@link #knownPerms}</b>：{@link #candidates()} 是管理员主动点「拉取」，
   * 那里的失败<b>刻意不缓存</b>（见类注释），本表的失败记忆不参与那条路径。
   */
  private final ConcurrentHashMap<String, Instant> permMiss = new ConcurrentHashMap<>();

  /**
   * 一次拉取的完整结果。菜单与词表来自同一个文件，一起缓存、一起失效。
   *
   * <p>{@code perms} 每项是 {@code {value, label}}（人话标签由产品自报，org 原样展示），
   * 不是裸字符串 —— 菜单页的权限词下拉要显示「查看 / 编辑」而不是 {@code catalog:read}。
   */
  private record Payload(List<Map<String, Object>> menus, List<Map<String, Object>> perms) {}

  private record Cached(Payload payload, Instant at) {}

  public MenuCandidateService(ServiceRegistry registry) {
    this.registry = registry;
    this.http = RestClient.builder()
        .requestFactory(ClientHttpRequestFactories.get(
            ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(CONNECT_TIMEOUT)
                .withReadTimeout(READ_TIMEOUT)))
        .build();
  }

  /**
   * 每个已登记产品一份候选，<b>各报各的成败</b>。
   *
   * <p>不合成一个「要么全部成功要么整体失败」：仓建设的页面还没构建过时整个接口 500，
   * 管理员会以为是自己配错了，而其实另一个产品的候选本来是能拿到的。
   */
  public Map<String, Object> candidates() {
    List<Map<String, Object>> products = new ArrayList<>();
    for (ServiceRegistry.Entry e : registry.all().stream()
        .sorted(Comparator.comparing(ServiceRegistry.Entry::product))
        .toList()) {
      products.add(one(e));
    }
    return Map.of("products", products);
  }

  private Map<String, Object> one(ServiceRegistry.Entry e) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("product", e.product());

    String frontendUrl = e.frontendUrl() == null ? "" : e.frontendUrl().trim();
    if (frontendUrl.isEmpty()) {
      // 不能只报「没配地址」而不说去哪配 —— 这一栏在另一个页面上
      return fail(row, "「服务注册」里没有登记这个产品的页面地址，补上后再拉取");
    }
    String url = frontendUrl.replaceAll("/+$", "") + MENU_PATH;
    // 回显实际请求地址：把页面地址填成后端端口是常见的手误，而这个接口
    // 除了「拉不到」之外看不出任何别的信息 —— 地址是排查时唯一能对的东西。
    row.put("url", url);

    Cached hit = cache.get(e.product());
    if (hit != null && fresh(hit)) {
      return ok(row, hit.payload().menus());
    }
    try {
      Payload payload = fetch(e.product(), url);
      cache.put(e.product(), new Cached(payload, Instant.now()));
      return ok(row, payload.menus());
    } catch (RuntimeException ex) {
      log.warn("拉取 {} 的菜单候选失败: {}", e.product(), ex.getMessage());
      return fail(row, ex.getMessage());
    }
  }

  /**
   * 该产品<b>自报</b>的权限词表，供「产品角色管理」校验角色权限、供菜单页做权限词下拉。
   *
   * <p><b>为什么词表由产品自报，而不是 org 侧硬编码一份</b>：硬编码的那份必然与产品漂移，
   * 而漂移的后果正好是「管理员配了一个产品不认识的词 → 那个菜单永远置灰」——
   * 一个不报错、只在用户点进去时才发现是坏的开关。产品侧的 {@code navData.ts} 里有
   * 每个页面的权限词，让它顺手把全集报出来，org 只做展示与校验，不自己编一套。
   *
   * <p><b>为什么词表必须显式声明、不能靠聚合菜单的 {@code perm} 得出</b>：有些词
   * 不在任何菜单上 —— 例如 {@code lineage:write} 只用在 SQL 解析页的保存按钮上
   * （{@code dw-lineage/ui/src/components/Header/index.vue}）。聚合菜单会漏掉它，
   * 于是「血缘分析」这个角色永远配不出写权限。
   *
   * <h2>拿不到时返回空集，且失败要记一笔</h2>
   *
   * 返回空集 = 「不知道这个词表」，调用方据此<b>只做形状校验、不做归属校验</b>
   * （见 {@link PermWords}）。这不是放水：另一个选择是拉不到就拒，那意味着某个服务的
   * 页面地址一没登记，管理员就建不了任何角色 —— 一个配置缺失把整个功能锁死。
   *
   * <p>失败记 60 秒：本方法跑在<b>写入路径</b>上（管理员保存角色/菜单时），
   * 不是「点一下拉取」那种主动动作。不记的话，页面地址填错时管理员每保存一次
   * 就要等满连接 2 秒 + 读 5 秒的超时，看起来就是保存卡住了。
   */
  public Set<String> knownPerms(String product) {
    Payload payload = payloadOf(product);
    return payload == null ? Set.of() : values(payload);
  }

  /** 词表的完整形状（含人话标签），供菜单页的权限词下拉。拿不到时返回空列表。 */
  public List<Map<String, Object>> permOptions(String product) {
    Payload payload = payloadOf(product);
    return payload == null ? List.of() : payload.perms();
  }

  /**
   * 拿不到返回 {@code null}，而不是空 Payload。
   *
   * <p>「不知道词表」（null）与「词表确实是空的」（有 payload、perms 为空列表）在
   * 调用方那里处理完全不同：前者只做形状校验、后者可以严格拒。塌成一个会让
   * 「产品报了个空词表」被当成「拿不到」，从而静默放宽。
   */
  private Payload payloadOf(String product) {
    if (product == null || product.isBlank()) return null;
    String key = product.trim();

    Cached hit = cache.get(key);
    if (hit != null && fresh(hit)) return hit.payload();

    Instant until = permMiss.get(key);
    if (until != null && until.isAfter(Instant.now())) return null;

    ServiceRegistry.Entry entry = registry.all().stream()
        .filter(e -> key.equals(e.product()))
        .findFirst()
        .orElse(null);
    String frontendUrl = entry == null || entry.frontendUrl() == null ? "" : entry.frontendUrl().trim();
    if (frontendUrl.isEmpty()) {
      // 没登记页面地址就根本没有词表可拿，不必记失败记忆 —— 这个判断是纯本地的，
      // 下一次调用同样立刻返回，不会等网络。
      return null;
    }

    try {
      Payload payload = fetch(key, frontendUrl.replaceAll("/+$", "") + MENU_PATH);
      cache.put(key, new Cached(payload, Instant.now()));
      return payload;
    } catch (RuntimeException ex) {
      log.warn("取 {} 的权限词表失败，本次按「词表未知」处理（只校验形状）: {}", key, ex.getMessage());
      permMiss.put(key, Instant.now().plus(PERM_FAIL_TTL));
      return null;
    }
  }

  private static Set<String> values(Payload payload) {
    Set<String> out = new LinkedHashSet<>();
    for (Map<String, Object> option : payload.perms()) out.add(String.valueOf(option.get("value")));
    return out;
  }

  private static boolean fresh(Cached hit) {
    return Duration.between(hit.at(), Instant.now()).compareTo(TTL) < 0;
  }

  private Payload fetch(String product, String url) {
    String body;
    try {
      body = http.get().uri(URI.create(url)).retrieve().body(String.class);
    } catch (Exception ex) {
      throw new IllegalStateException("拉取 " + url + " 失败：" + ex.getMessage());
    }
    if (body == null || body.isBlank()) {
      throw new IllegalStateException("拉取 " + url + " 拿到空响应 —— 该地址上没有导出的菜单清单");
    }

    Map<String, Object> root = Jsons.map(body);
    if (root == null) {
      throw new IllegalStateException("拉取 " + url + " 拿到的东西不是 JSON 对象 —— "
          + "这个地址上多半是另一个页面（前端没构建过时常见的是 HTML）");
    }

    // 自报产品码对不上 = 这个地址上跑的是另一个产品的前端。这是「页面地址填串了」
    // 最容易识别的一种形态：地址能通、JSON 也合法，只是内容属于别人。
    String declared = str(root.get("product"));
    if (!product.equals(declared)) {
      throw new IllegalStateException("地址上的服务自报产品码是「" + declared + "」，不是「" + product
          + "」—— 「服务注册」里这个产品的页面地址可能填成了另一个产品：" + url);
    }

    Object raw = root.get("menus");
    if (!(raw instanceof List<?> list)) {
      throw new IllegalStateException("拉取 " + url + " 的清单里没有 menus 数组");
    }
    List<Map<String, Object>> menus = new ArrayList<>(list.size());
    for (Object item : list) {
      if (!(item instanceof Map<?, ?> m)) {
        throw new IllegalStateException("拉取 " + url + " 的 menus 里有一项不是对象");
      }
      menus.add(normalize(m, url));
    }
    return new Payload(menus, parsePerms(root.get("perms"), url));
  }

  /**
   * 解析产品自报的权限词表。
   *
   * <p>两种写法都收：裸字符串，或 {@code {value, label}}（后者是产品侧
   * {@code PERM_OPTIONS} 的形状，带人话标签）。统一存成后者，label 缺失时留空 ——
   * 菜单页的下拉对空标签会回落到显示 value。
   *
   * <p><b>缺 {@code perms} 字段不是错误</b>：老版本服务的清单里没有这一项，
   * 表现为「词表未知」（空列表），调用方只做形状校验。
   *
   * <p><b>但成员形状写错要整份拒</b>，与 {@link #normalize} 同一个理由：{@code perms}
   * 不是数组、或里面有个 {@code catalog:edit} 这种拼错的词，都说明产品侧的清单本身有问题。
   * 静默跳过会让「管理员在下拉里少看到一个词」变成一个没人查的悬案 ——
   * 产品侧有构建期守卫，这里拒掉在生产上不该发生。
   */
  private static List<Map<String, Object>> parsePerms(Object raw, String url) {
    if (raw == null) return List.of();
    if (!(raw instanceof List<?> list)) {
      throw new IllegalStateException("拉取 " + url + " 的清单里 perms 不是数组");
    }
    List<Map<String, Object>> out = new ArrayList<>(list.size());
    Set<String> seen = new LinkedHashSet<>();
    for (Object item : list) {
      String value;
      String label;
      if (item instanceof Map<?, ?> m) {
        value = str(m.get("value"));
        label = str(m.get("label"));
      } else {
        value = str(item);
        label = "";
      }
      if (!PermWords.isWellFormed(value)) {
        throw new IllegalStateException("拉取 " + url + " 的词表里有非法权限词「" + value
            + "」—— 格式是「域:动作」，动作只能是 " + PermWords.ACTIONS);
      }
      if (value.isEmpty()) continue;
      if (!seen.add(value)) {
        throw new IllegalStateException("拉取 " + url + " 的词表里「" + value + "」重复了");
      }
      Map<String, Object> option = new LinkedHashMap<>();
      option.put("value", value);
      option.put("label", label);
      out.add(option);
    }
    return out;
  }

  /**
   * 把一个候选菜单项规范成固定的字段集，顺带校验。
   *
   * <p>校验放在这里而不是等落库：`scope` 是 {@code nav_items} 的白名单，
   * 清单里写错的值会让管理员勾完之后拿到一条 400，而看不出是清单本身有问题。
   * 整份清单一起拒（而不是跳过坏项）：候选少一条能手工补，
   * 「悄悄少了一条」则会让人以为服务那边本来就没有这个页面。
   */
  private static Map<String, Object> normalize(Map<?, ?> raw, String url) {
    String scope = str(raw.get("scope"));
    if (!NavItemService.SCOPES.contains(scope)) {
      throw new IllegalStateException("拉取 " + url + " 的候选里有非法的 scope「" + scope
          + "」—— 可用值：" + NavItemService.SCOPES);
    }
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", str(raw.get("id")));
    m.put("scope", scope);
    m.put("group", str(raw.get("group")));
    m.put("path", required(raw, "path", url));
    m.put("label", required(raw, "label", url));
    m.put("icon", str(raw.get("icon")));
    m.put("perm", str(raw.get("perm")));
    m.put("sort", raw.get("sort") instanceof Number n ? n.intValue() : 0);
    return m;
  }

  private static String required(Map<?, ?> raw, String field, String url) {
    String v = str(raw.get(field));
    if (v.isEmpty()) {
      throw new IllegalStateException("拉取 " + url + " 的候选里有一项缺 " + field);
    }
    return v;
  }

  private static String str(Object v) {
    return v == null ? "" : String.valueOf(v).trim();
  }

  private static Map<String, Object> ok(Map<String, Object> row, List<Map<String, Object>> menus) {
    row.put("ok", true);
    row.put("error", "");
    row.put("menus", menus);
    return row;
  }

  private static Map<String, Object> fail(Map<String, Object> row, String error) {
    row.put("ok", false);
    row.put("error", error == null ? "拉取失败" : error);
    row.put("menus", List.of());
    return row;
  }
}
