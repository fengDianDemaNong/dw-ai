package com.dwai.platform.meta;

import java.util.Set;

/**
 * 允许登记的产品码，以及它与「租户许可模块名」两套词汇的关系。
 *
 * <p><b>这里是两套词汇，不是一个</b>：{@code service_registry.product} 用的是
 * {@link #KNOWN}（{@code warehouse} / {@code metadata} / {@code quality} / {@code serve}），
 * 而租户许可（{@code tenant_licenses.modules}）与前端 {@code config/iam.ts} 的
 * {@code MODULE_OPTIONS} 用的是 {@link #LICENSE_MODULES}，后者多出
 * {@code materialize} / {@code dev} 两个没有独立进程的模块。
 *
 * <p>四个产品码目前<b>逐字</b>落在许可词汇表里，所以服务注册配的产品码可以直接与
 * 许可里的模块名做字符串比较（消费面 {@code NavController} 就是这么过滤菜单的）。
 * 这是个巧合得靠守卫守住的巧合：只要有人往 {@link #KNOWN} 里加一个许可表里没有的码
 * （比如 {@code lineage}），菜单就会<b>静默不显示</b> —— 接口 200、侧栏空白，
 * 没有一行报错。{@code CrossServiceDesignGuardTest} 钉住了这条包含关系。
 *
 * <p>不加映射表：映射表能容错，但会把「配了个永远显示不出来的产品」这个错误
 * 从写入时推迟到用户看不见的地方。写入时就拒绝更便宜。
 */
public final class ProductCodes {

  private ProductCodes() {}

  /** 允许出现在 {@code service_registry} / {@code nav_items} 里的产品码。 */
  public static final Set<String> KNOWN = Set.of("warehouse", "metadata", "quality", "serve");

  /**
   * 租户许可里可能出现的模块名。
   *
   * <p>镜像的是 {@code dw-org/ui/src/config/iam.ts} 的 {@code MODULE_OPTIONS}
   * 与 {@code TenantLicense} 类型；Java 侧没有共享定义（不引 dw-common 的类型），
   * 所以只能各写一份，靠守卫测试比对。
   */
  public static final Set<String> LICENSE_MODULES =
      Set.of("warehouse", "metadata", "serve", "quality", "materialize", "dev");

  public static boolean isKnown(String product) {
    return product != null && KNOWN.contains(product.trim());
  }
}
