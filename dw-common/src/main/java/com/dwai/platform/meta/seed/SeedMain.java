package com.dwai.platform.meta.seed;

import com.dwai.platform.meta.support.RunModes;
import com.dwai.platform.meta.support.SeedDb;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 手动灌演示数据。不启动 Web。须已完成 Flyway 建表。
 *
 * <p>dw-org 与 dw-model 共用这一个类 —— 原来两边各有一份逐字节相同的 {@code SeedMain}，
 * 按「逐字节相同才有资格搬进 dw-common」的口径收拢到这里。两处差异都由环境变量给：
 *
 * <ul>
 *   <li>{@code DW_AI_SEED_IS_ORG=true} —— 组织平台在 multi 下<strong>拥有</strong>身份数据，
 *       所以照灌；模块在 multi 下是纯消费者（租户与项目由组织经 {@code /internal/v1/**}
 *       扇出），自己再灌一份只会与扇出的打架。</li>
 *   <li>脚本文件本身 —— 身份层在 dw-common，成员层与业务层各服务自己带。本类按固定名字
 *       去找，找不到就跳过（组织平台没有 {@code business-*.sql}，属正常）。</li>
 * </ul>
 *
 * <p>灌哪些脚本按运行模式分三级，见 {@link #plan}。
 */
public final class SeedMain {
  public static void main(String[] args) throws Exception {
    boolean org = "true".equalsIgnoreCase(env("DW_AI_SEED_IS_ORG", "false"));
    // 三参重载：org 恒 multi，与 DwaiProperties.runMode() 的首行同源。
    String mode = RunModes.resolve(env("DW_AI_RUN_MODE", ""), env("DW_AI_MODE", ""), org);

    String home = env("DW_AI_HOME", System.getProperty("user.dir"));
    String url = env("DB_URL", "");
    String vendor = SeedDb.detect(env("DB_TYPE", ""), url);

    List<String> scripts = plan(mode, org, vendor);
    if (scripts.isEmpty()) {
      System.out.println("运行模式 " + mode + "：本模块的租户与项目由组织平台同步灌入，不往本地库写演示数据。");
      System.out.println("要一份可看的建模演示数据，请改用 standard 模式；"
          + "或先对组织平台执行它的 bin/seed-demo.sh，再回来执行本脚本。");
      return;
    }

    String user;
    String pass;
    if (url.isBlank() && SeedDb.H2.equals(vendor)) {
      url = SeedDb.h2FileUrl(home, env("DWAI_DB_FILE", ""));
      user = env("DB_USER", "sa");
      pass = env("DB_PASSWORD", "");
    } else {
      if (url.isBlank()) {
        System.err.println("请设置 DB_URL，或留空以使用 H2");
        System.exit(2);
        return;
      }
      user = env("DB_USER", "dwai");
      pass = env("DB_PASSWORD", "dwai");
    }

    try (Connection c = DriverManager.getConnection(url, user, pass)) {
      if (!hasTenantsTable(c)) {
        System.err.println("未找到表 tenants。请先启动服务完成建表（./bin/start.sh 或 mvn spring-boot:run），再执行本命令。");
        System.exit(1);
        return;
      }
      for (String path : scripts) {
        ClassPathResource res = new ClassPathResource(path);
        if (!res.exists()) {
          System.out.println("跳过 " + path + "（classpath 里没有这个脚本）");
          continue;
        }
        ScriptUtils.executeSqlScript(c, res);
        System.out.println("已写入 " + path);
      }
    }
    System.out.println("演示数据已写入（" + vendor + " / " + mode + " 模式）");
  }

  /**
   * 按运行模式决定执行哪些脚本，返回顺序即依赖顺序（身份在前，成员与业务在后）。
   *
   * <table>
   *   <caption>三级</caption>
   *   <tr><th>模式</th><th>dw-org</th><th>dw-model</th></tr>
   *   <tr><td>standalone / standard</td><td>—（组织平台恒 multi）</td>
   *       <td>身份层 + 成员 + 业务</td></tr>
   *   <tr><td>multi</td><td>身份层 + 第二租户 + 成员</td>
   *       <td><b>空</b>（身份由组织扇出）</td></tr>
   * </table>
   *
   * <p>standard 下身份层只含「星河电商」一个租户：{@code TenantFilter} 忽略租户头、
   * 租户列表接口也只回隐含租户，第二个租户连同它的项目在本模式下不可见，灌进去
   * 只会变成让人排查半天的不可达数据。
   *
   * <p>multi 下模块连 DELETE 都不跑：多租户场景里本地库的租户与项目完全由组织平台
   * 扇出，本进程不该有任何写动作（与 {@code WarehouseLocalSeedRunner} 在 multi 下
   * 直接 return 同源）。
   */
  private static List<String> plan(String mode, boolean org, String vendor) {
    List<String> paths = new ArrayList<>();
    boolean multi = RunModes.MULTI.equals(mode);
    if (multi && !org) {
      return paths;
    }
    paths.add(SeedDb.identityResource());
    if (multi) {
      paths.add(SeedDb.identityMultiResource());
    }
    paths.add(SeedDb.membersResource());
    paths.add(SeedDb.dialectResource(vendor, "business"));
    return paths;
  }

  private static boolean hasTenantsTable(Connection c) {
    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT 1 FROM tenants WHERE 1=0")) {
      return rs != null;
    } catch (Exception e) {
      return false;
    }
  }

  private static String env(String key, String fallback) {
    String v = System.getenv(key);
    return v == null || v.isBlank() ? fallback : v;
  }
}
