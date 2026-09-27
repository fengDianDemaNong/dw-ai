package com.dwai.platform.meta;

import com.dwai.platform.auth.TenantContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 产品服务目录（消费面）：当前租户开通了哪些产品、各自的前端地址在哪。
 *
 * <p>门户要往别的服务跳（「进入项目」按钮、侧栏产品菜单），必须知道对方站点根在哪。
 * 以前这个地址来自前端的环境变量（`VITE_WAREHOUSE_ORIGIN` / `VITE_LINEAGE_ORIGIN`），
 * 于是每加一个部署就要重新构建一次前端，且漏配的表现是<b>静默跳到
 * `127.0.0.1:517x`</b> —— 跨服务器部署时那正是用户自己的机器。改成向组织问，
 * 地址就只有一个来源：平台管理员在「服务注册」里填的那一份。
 *
 * <p><b>门禁与 {@link NavController} 逐条相同</b>，理由也相同：这里是普通租户成员
 * 也要读的东西，挂 {@code access.requirePlatform()}（平台管理员专属）会让普通成员的
 * 「进入项目」永远拿不到地址。路径同样刻意避开 {@code /api/v1/platform/**} ——
 * 那个前缀落在 {@code TenantFilter.isPlatformApi()} 的豁免里，会跳过租户停用校验。
 *
 * <p>返回空列表而不是报错的情况：平台管理员还没选租户、租户没有许可行、
 * 该租户一个产品都没开通。
 */
@RestController
@RequestMapping({"/api/services", "/api/v1/services"})
public class ServiceCatalogController {

  private final NavNodeService nav;

  public ServiceCatalogController(NavNodeService nav) {
    this.nav = nav;
  }

  @GetMapping
  public List<Map<String, Object>> services() {
    return nav.servicesFor(TenantContext.tenantId());
  }
}
