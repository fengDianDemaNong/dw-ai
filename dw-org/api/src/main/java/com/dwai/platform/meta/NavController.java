package com.dwai.platform.meta;

import com.dwai.platform.auth.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 侧栏菜单树（消费面）：当前租户里，这个人该看到哪些入口。
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
 * <p><b>返回的是「这个人」的菜单，不是「这个租户」的菜单</b>：挂了权限词的节点要按
 * 这个人当前项目下的角色判一次（见 {@link NavNodeService#treeFor}），看不见就不返回。
 * 所以同一条 {@code GET /api/nav}，租户管理员、只读成员、以及同一个人在两个项目下，
 * 拿到的树可以不同 —— 也因为如此，<b>前端切项目时必须重拉一次</b>，否则会拿上一个项目的
 * 结论画侧栏（见 dw-org/ui 的 {@code stores/app.ts}）。
 *
 * <p><b>V23 起返回的是一棵树</b>（{@code children} 递归），而不是平铺的菜单项列表：
 * 分组与菜单合并成了 {@code nav_nodes} 一张表，分组就是 {@code path} 为空的目录节点。
 * 顺带删掉了 {@code GET /api/nav/groups} —— 分组元数据现在是树里那一行自己的字段
 * （{@code emptyPolicy}），没有第二份需要单独拉的东西，也就没有「菜单到了、分组没到」
 * 这种半成品状态。
 */
@RestController
@RequestMapping({"/api/nav", "/api/v1/nav"})
public class NavController {

  private final NavNodeService nav;

  public NavController(NavNodeService nav) {
    this.nav = nav;
  }

  @GetMapping
  public List<Map<String, Object>> menu() {
    // 带上当前项目：同一个产品在不同项目里可以是不同角色（`X-Project-Id` 由壳按
    // sessionStorage 里的当前项目带上，见 dw-org/ui 的 `api/client.ts`）。
    return nav.treeFor(TenantContext.tenantId(), TenantContext.projectId());
  }
}
