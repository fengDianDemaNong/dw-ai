package com.dwai.platform.meta;

import com.dwai.platform.auth.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 侧栏菜单（消费面）：当前租户里，这个人该看到哪些入口。
 *
 * <p><b>为什么单独一个 controller，而不是挂在 {@link PlatformController} 下</b>：
 * 那边每一个端点都走 {@code access.requirePlatform()}（平台管理员专属）。
 * 菜单读取若也挂过去，<b>普通租户成员的侧栏会永远是空的</b> —— 而「让平台成员用上
 * 被嵌入的服务」正是这次门户集成的目的。所以这里是另一个门槛：登录 + 已选租户。
 *
 * <p>路径刻意用 {@code /api/nav} 而不是 {@code /api/v1/platform/nav}：后者落在
 * {@code TenantFilter.isPlatformApi()} 的豁免前缀里，会跳过租户状态校验 ——
 * 那样「租户已停用」的人还能拿到菜单，点进去才被拒。这里要的正是
 * {@code /api/**} 的正常待遇。
 *
 * <p>返回空列表而不是报错的情况有几种：平台管理员还没选租户、租户没有许可行、
 * 该租户一个产品都没开通。侧栏是每个页面都要画的东西，让它因为「没选租户」而 500
 * 会把整个壳带下水。
 *
 * <p><b>返回的是「这个人」的菜单，不是「这个租户」的菜单</b>：挂了权限词的菜单项要按
 * 这个人当前项目下的角色判一次（{@code NavItemService.menuFor}），看不见就不返回。
 * 所以同一条 {@code GET /api/nav}，租户管理员、只读成员、以及同一个人在两个项目下，
 * 拿到的条数可以不同 —— 也因为如此，<b>前端切项目时必须重拉一次</b>，否则会拿上一个项目的
 * 结论画侧栏（见 dw-org/ui 的 {@code stores/app.ts}）。
 */
@RestController
@RequestMapping({"/api/nav", "/api/v1/nav"})
public class NavController {

  private final NavItemService nav;
  private final NavGroupService navGroups;

  public NavController(NavItemService nav, NavGroupService navGroups) {
    this.nav = nav;
    this.navGroups = navGroups;
  }

  @GetMapping
  public List<Map<String, Object>> menu() {
    // 带上当前项目：同一个产品在不同项目里可以是不同角色（`X-Project-Id` 由壳按
    // sessionStorage 里的当前项目带上，见 dw-org/ui 的 `api/client.ts`）。
    return nav.menuFor(TenantContext.tenantId(), TenantContext.projectId());
  }

  /**
   * 侧栏用得到的分组元数据（组间顺序、空组策略）。
   *
   * <p><b>为什么另开一个端点、不并进上一条 {@code /api/nav}</b>：两者的失败后果不同。
   * 菜单拉不到 = 侧栏少入口；分组拉不到 = 侧栏回落成现在的样子（按首次出现序、无空组）。
   * 合成一个请求会让这两种症状互相掩盖 —— 与 {@code stores/app.ts} 里
   * {@code navItems} 与 {@code productServices} 分开拉是同一条理由。
   *
   * <p>顺带的：{@code /api/nav} 的返回形状没动，{@code NavMenuTest} 里那个把返回当
   * 数组用的私有助手也就不用改。
   *
   * <p>返回的条数<b>不受租户许可过滤</b>，这正是 {@code always} 空组策略的要求 ——
   * 详见 {@link NavGroupService#groupsFor}。
   */
  @GetMapping("/groups")
  public List<Map<String, Object>> groups() {
    return navGroups.groupsFor(TenantContext.tenantId());
  }
}
