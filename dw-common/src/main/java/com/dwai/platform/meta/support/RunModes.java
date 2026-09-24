package com.dwai.platform.meta.support;

import java.util.Locale;

/**
 * 运行模式的回落链。
 *
 * <p>与各服务 {@code DwaiProperties.runMode()} <strong>必须一致</strong> —— 演示数据要按模式
 * 分级灌，而 {@code SeedMain} 跑在 Spring 容器之外（{@code java -cp ... SeedMain}），
 * 拿不到 {@code DwaiProperties}。把这条链抽成纯函数放这里，两边共用，免得以后
 * 改了一处忘了另一处。
 *
 * <p>回落方向朝「要认证」倒：认不出的值一律落 {@code multi}，而不是 {@code standalone}
 * —— 后者免登录，猜错就是静默放开鉴权。
 */
public final class RunModes {
  public static final String STANDALONE = "standalone";
  public static final String STANDARD = "standard";
  public static final String MULTI = "multi";

  private RunModes() {}

  /**
   * 组织平台恒 {@code multi}。
   *
   * <p>对应 {@code DwaiProperties.runMode()} 的首行 {@code if (isOrgOnly()) return "multi"}
   * —— 那一行不是可有可无的兜底：组织平台若被配成 {@code standalone}，会连自己的身份
   * 数据都不再拥有，整条扇出链断掉。服务侧靠 {@code dwai.product=org} 判定，seed 进程
   * 拿不到那个配置，由调用方用 {@code DW_AI_SEED_IS_ORG} 把同一个事实传进来。
   */
  public static String resolve(String runMode, String deployMode, boolean orgOnly) {
    if (orgOnly) {
      return MULTI;
    }
    return resolve(runMode, deployMode);
  }

  /** {@code runMode} 非空则用它，否则回落 {@code deployMode}；不在白名单内一律落 {@code multi}。 */
  public static String resolve(String runMode, String deployMode) {
    String raw = runMode != null && !runMode.isBlank() ? runMode : deployMode;
    if (raw == null) {
      return MULTI;
    }
    String m = raw.trim().toLowerCase(Locale.ROOT);
    if (STANDALONE.equals(m) || STANDARD.equals(m) || MULTI.equals(m)) {
      return m;
    }
    return MULTI;
  }
}
