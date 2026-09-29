package com.dwai.platform.meta;

import com.dwai.platform.internal.ServiceRegistry;
import com.dwai.platform.meta.dto.ApiModels;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping({"/api/platform", "/api/v1/platform"})
public class PlatformController {
  private final PlatformService platform;
  private final AccessService access;
  private final ServiceRegistry registry;
  private final NavNodeService nav;
  private final MenuCandidateService candidates;
  private final ProductRoleService productRoles;

  public PlatformController(
      PlatformService platform,
      AccessService access,
      ServiceRegistry registry,
      NavNodeService nav,
      MenuCandidateService candidates,
      ProductRoleService productRoles) {
    this.platform = platform;
    this.access = access;
    this.registry = registry;
    this.nav = nav;
    this.candidates = candidates;
    this.productRoles = productRoles;
  }

  @GetMapping("/tenants")
  public List<ApiModels.TenantDto> tenants() {
    return platform.listTenants();
  }

  @PostMapping("/tenants")
  public ApiModels.TenantDto createTenant(@RequestBody ApiModels.CreateAdminTenantReq req) {
    return platform.createTenant(req);
  }

  @PatchMapping("/tenants/{id}")
  public ApiModels.TenantDto patchTenant(@PathVariable String id, @RequestBody ApiModels.PatchAdminTenantReq req) {
    return platform.patchTenant(id, req);
  }

  /**
   * 重置该租户管理员的密码，并作废其已签发的 refresh token。
   *
   * <p>单独开一个端点而不是塞进 {@code PATCH /tenants/{id}}：那个是「改租户属性」，
   * 这个是「改某个账号的凭据」—— 两者的授权对象不同（前者动租户，后者动用户），
   * 混在一起以后要单独收紧或单独记审计时会很别扭。
   */
  @PostMapping("/tenants/{id}/reset-admin-password")
  public void resetAdminPassword(@PathVariable String id, @RequestBody(required = false) ApiModels.ResetAdminPasswordReq req) {
    platform.resetTenantAdminPassword(id, req == null ? null : req.password());
  }

  @GetMapping("/accounts")
  public List<ApiModels.OrgUserDto> accounts() {
    return platform.listAccounts();
  }

  @GetMapping("/users")
  public List<ApiModels.OrgUserDto> users() {
    return platform.listUsers();
  }

  @PostMapping("/users")
  public ApiModels.OrgUserDto createUser(@RequestBody ApiModels.CreatePlatformUserReq req) {
    return platform.createUser(req);
  }

  @PatchMapping("/users/{id}")
  public ApiModels.OrgUserDto patchUser(@PathVariable String id, @RequestBody ApiModels.PatchPlatformUserReq req) {
    return platform.patchUser(id, req);
  }

  @GetMapping("/appearance")
  public ApiModels.AppearanceDto appearance() {
    return platform.getAppearance();
  }

  @PutMapping("/appearance")
  public ApiModels.AppearanceDto putAppearance(@RequestBody ApiModels.AppearanceDto body) {
    return platform.putAppearance(body);
  }

  /**
   * 已登记的产品配置。
   *
   * <p>不再返回 {@code status} / {@code seenAt}：那是心跳时代的探活语义，
   * 心跳删除后没有任何写入路径更新它们，留着只会恒为 {@code stale}。
   */
  @GetMapping("/services")
  public List<Map<String, Object>> services() {
    access.requirePlatform();
    return registry.all().stream()
        .sorted(Comparator.comparing(ServiceRegistry.Entry::product))
        .map((e) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          row.put("product", e.product());
          row.put("version", e.version());
          row.put("frontendUrl", e.frontendUrl());
          return row;
        })
        .toList();
  }

  /**
   * 登记（或覆盖）一个产品的配置。
   *
   * <p>{@code baseUrl} 不再由前端提供：模块后端地址随心跳一起退役。
   */
  @PostMapping("/services")
  public Map<String, Object> registerService(@RequestBody(required = false) RegisterServiceReq req) {
    access.requirePlatform();
    if (req == null || req.product == null || req.product.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 product");
    }
    String product = req.product.trim();
    if (!ProductCodes.isKnown(product)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "未知产品码 " + product + "。可用值：" + ProductCodes.KNOWN);
    }
    if (req.frontendUrl == null || req.frontendUrl.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "需要 frontendUrl（产品的页面地址）");
    }
    ServiceRegistry.Entry e = registry.put(product, req.version, null, req.frontendUrl.trim());
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("product", e.product());
    out.put("version", e.version());
    out.put("frontendUrl", e.frontendUrl());
    return out;
  }

  @DeleteMapping("/services/{product}")
  public void removeService(@PathVariable String product) {
    access.requirePlatform();
    registry.remove(product);
  }

  /**
   * 门户菜单树的增删改查（平台管理员）。
   *
   * <p><b>这是管理面；消费面在 {@link NavController}</b>（{@code /api/nav}）——
   * 那边只要求登录 + 已选租户。两处的鉴权刻意不同：把菜单读取也挂在这个
   * {@code requirePlatform()} 下面，普通租户成员的侧栏会永远是空的
   * （{@code PlatformController} 上所有端点都是平台管理员专属）。
   *
   * <p>返回<b>嵌套树</b>：管理页要展示的本来就是树（见 {@link NavNodeService#all}）。
   * V23 之前这里是 {@code /nav-items} + {@code /nav-groups} 两组端点，分组与菜单是两个
   * 概念；合并成一张 {@code nav_nodes} 之后只剩这一组。
   */
  @GetMapping("/nav-nodes")
  public List<Map<String, Object>> navNodes(@RequestParam(required = false) String scope) {
    access.requirePlatform();
    return nav.all(scope);
  }

  @PostMapping("/nav-nodes")
  public Map<String, Object> createNavNode(@RequestBody(required = false) NavNodeService.NavNodeReq req) {
    access.requirePlatform();
    return nav.create(req);
  }

  /**
   * 各服务报上来的菜单候选，供管理员勾选（见 {@link MenuCandidateService}）。
   *
   * <p>与 {@code /nav-nodes} 一样是<b>管理面</b>：它拉的是各服务的前端地址，
   * 属于平台配置动作，不开放给租户成员。
   *
   * <p>每一项的 {@code menus} 现在是<b>一棵树</b>（产品可以报任意层级），
   * 老格式（扁平两层、每项带 {@code group}）也照收 —— 见 {@code foldGroups}。
   */
  @GetMapping("/nav-candidates")
  public Map<String, Object> navCandidates() {
    access.requirePlatform();
    return candidates.candidates();
  }

  /** 改标题 / 图标 / 顺序 / 权限词 / 启停 / 父子关系 / 空目录策略 / 挂载。 */
  @PatchMapping("/nav-nodes/{id}")
  public Map<String, Object> updateNavNode(
      @PathVariable String id, @RequestBody(required = false) NavNodeService.NavNodeReq req) {
    access.requirePlatform();
    return nav.update(id, req);
  }

  /** 删一个节点**连同它的整棵子树**；返回体里的 {@code subtree} 是连带删掉的子孙条数。 */
  @DeleteMapping("/nav-nodes/{id}")
  public Map<String, Object> deleteNavNode(@PathVariable String id) {
    access.requirePlatform();
    return nav.delete(id);
  }

  /**
   * 把一个菜单在同层里上移 / 下移一格（body {@code {"delta": -1 | 1}}）。
   *
   * <p>{@code scope} 与 {@code parentId} <b>不在请求体里</b> —— 它们全部由被移动的节点自身
   * 推导。这两个字段一旦让调用方传，就必然出现「传了别的层」的请求，而那种错误的后果是
   * 静默改错一层；详见 {@link NavNodeService#move}。
   *
   * <p>{@code changed:false} 表示已经在首 / 末位（不是错误），前端据此置灰按钮。
   */
  @PostMapping("/nav-nodes/{id}/move")
  public Map<String, Object> moveNavNode(
      @PathVariable String id, @RequestBody(required = false) NavNodeService.MoveReq req) {
    access.requirePlatform();
    return nav.move(id, req == null ? null : req.delta);
  }

  /**
   * 把一条外链菜单的凭据**明文**读出来，供「复制」按钮用。
   *
   * <p><b>这是全仓唯一的凭据明文出口，是有意开的</b>，与 {@code ComputeDto} 那套
   * 「写了就不回显」的区别在于：那个页面上凭据是拿来用的（服务端自己去调），
   * 而这里的外链凭据<b>服务端用不上</b> —— 账号密码那一档浏览器根本不让我代填
   * （见 {@code NavNodeEntity#getAuthMode()}），不给人复制就完全是个死字段。
   *
   * <p>门禁因此收到最严：平台管理员专属（与 {@code /nav-nodes} 其余端点同一道），
   * 消费面<b>没有</b>对应接口 —— 使用者拿不到明文。
   *
   * <p>{@code no-store}：明文不能被任何中间层缓存。
   */
  @GetMapping("/nav-nodes/{id}/credential")
  public ResponseEntity<Map<String, Object>> navNodeCredential(@PathVariable String id) {
    access.requirePlatform();
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(nav.credential(id));
  }

  /**
   * 产品角色的增删改查（平台管理员）。
   *
   * <p>与 {@code /nav-items} 同一层级、同一门禁：角色定义是<b>平台级配置</b>
   * （全局一套、跟产品版本走，见规范 {@code 06-runtime-modes.md:99}），
   * 租户只在项目里<b>派</b>角色。「谁能进哪个项目」是租户的事，
   * 「某个角色有哪些权限」是产品的事 —— 后者改了会影响所有租户，所以只给平台管理员。
   *
   * <p>注意 {@code /{id}} 这三条与上面 {@code /nav-items/{id}} 同形，
   * 路径前缀不同（{@code product-roles}）不会撞。
   */
  @GetMapping("/product-roles")
  public List<Map<String, Object>> productRoles(@RequestParam(required = false) String product) {
    access.requirePlatform();
    return productRoles.all(product);
  }

  @PostMapping("/product-roles")
  public Map<String, Object> createProductRole(
      @RequestBody(required = false) ProductRoleService.ProductRoleReq req) {
    access.requirePlatform();
    return productRoles.create(req);
  }

  @PatchMapping("/product-roles/{id}")
  public Map<String, Object> updateProductRole(
      @PathVariable String id, @RequestBody(required = false) ProductRoleService.ProductRoleReq req) {
    access.requirePlatform();
    return productRoles.update(id, req);
  }

  @DeleteMapping("/product-roles/{id}")
  public Map<String, Object> deleteProductRole(@PathVariable String id) {
    access.requirePlatform();
    return productRoles.delete(id);
  }

  /**
   * 某产品自报的权限词表（{@code [{value,label}]}），供菜单页与角色页做下拉。
   *
   * <p>与 {@code /nav-candidates} 一样会去拉各服务的前端地址，所以同样是管理面。
   * 单独开这条而不是让前端从 {@code /nav-candidates} 里自己聚合：词表里有<b>不在任何
   * 菜单上</b>的词（{@code lineage:write} 只用在 SQL 解析页的保存按钮上），
   * 聚合菜单必然漏掉它。
   */
  @GetMapping("/product-perms")
  public Map<String, Object> productPerms(@RequestParam String product) {
    access.requirePlatform();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("product", product);
    // known 为空 = 该产品还没上报词表（老版本服务/页面地址没登记），
    // 前端据此回落到「可手填 + 提示」，而不是显示一个空下拉把管理员卡死。
    out.put("perms", candidates.permOptions(product));
    return out;
  }

  public static class RegisterServiceReq {
    public String product;
    public String version;
    public String frontendUrl;
  }
}
