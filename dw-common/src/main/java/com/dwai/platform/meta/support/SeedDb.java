package com.dwai.platform.meta.support;

import java.nio.file.Path;
import java.util.Locale;

/**
 * 演示数据 seed 的库型判定与脚本定位。
 *
 * <p>这几个方法原来住在各服务的 {@code com.dwai.platform.db.MetaDb} 里，两份只差两行 ——
 * H2 的默认库名 {@code dw_org} / {@code dw_mode}。按「一行差异就是服务角色差异」的口径，
 * 那两行不该共用，其余全部相同，所以搬到这里，把库名<strong>收成参数</strong>由调用方给。
 *
 * <p>数据库产品名判定（{@code DatabaseMetaData.getDatabaseProductName()}）不在这里 ——
 * 那是另一回事，见 {@link DbVendors#isPostgres(String)}。原 {@code MetaDb} 里那份
 * {@code isPostgres} 与它重复且无人调用，搬迁时一并删掉了。
 */
public final class SeedDb {
  public static final String H2 = "h2";
  public static final String MYSQL = "mysql";
  public static final String POSTGRESQL = "postgresql";

  private SeedDb() {}

  /** dbType 优先（现场显式配的那个），否则从 url 猜，都没有则按 H2。 */
  public static String detect(String dbType, String url) {
    if (dbType != null && !dbType.isBlank()) {
      return dbType.trim().toLowerCase(Locale.ROOT);
    }
    String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
    if (u.contains("mysql")) {
      return MYSQL;
    }
    if (u.contains("postgresql") || u.contains("postgres")) {
      return POSTGRESQL;
    }
    return H2;
  }

  /**
   * 安装目录下 H2 库文件的 JDBC url。
   *
   * <p>{@code fileName} <strong>必须显式给出</strong>，这里刻意不留默认值。
   * 原来各自兜底成 {@code dw_org} / {@code dw_mode}，而 seed 与服务是<strong>两个进程</strong> ——
   * 兜底值一旦与 {@code dwai.db-file} 不一致，seed 会安静地灌进另一个库文件，建表在 A
   * 而数据在 B，翻文件系统才发现。现在两处都从 {@code conf/env.sh} 的 {@code DWAI_DB_FILE}
   * 取值（服务侧另由 {@code application.yml} 的 {@code dwai.db-file} 双保险）。
   */
  public static String h2FileUrl(String homeDir, String fileName) {
    if (fileName == null || fileName.isBlank()) {
      throw new IllegalArgumentException(
          "H2 库文件名不能为空：请在 conf/env.sh 里设置 DWAI_DB_FILE（如 dw_org），"
              + "或改设 DB_URL 走 MySQL / PostgreSQL");
    }
    Path file = Path.of(homeDir == null || homeDir.isBlank() ? "." : homeDir, "data", fileName.trim())
        .toAbsolutePath();
    return "jdbc:h2:file:" + file
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1";
  }

  public static String flywayLocation(String vendor) {
    if (POSTGRESQL.equals(vendor)) {
      return "classpath:db/migration/postgresql";
    }
    return "classpath:db/migration/mysql";
  }

  /**
   * {@code db/seed/} 下某个模块脚本的 classpath 位置（按方言分文件）。
   *
   * <p>只有含方言差异的脚本才走这里 —— 目前只有仓建设的 {@code business-*}，
   * PG 版要写 {@code ::jsonb}。身份层与成员层是纯标准 SQL，三后端同构，
   * <strong>只维护一份</strong>，见下面三个固定方法。
   *
   * <p>H2 与 MySQL 共用 {@code -mysql} 那份：H2 以 {@code MODE=MySQL} 跑。
   */
  public static String dialectResource(String vendor, String name) {
    String dialect = POSTGRESQL.equals(vendor) ? "postgresql" : "mysql";
    return "db/seed/" + name + "-" + dialect + ".sql";
  }

  /**
   * 身份层：租户 / 账号 / 项目 / 授权码，以及「星河电商」这个单租户演示的全部内容。
   *
   * <p>三种后端逐字节同构，所以只有这一份。原来它是 dw-org 与 dw-model 各一份的
   * {@code demo-{mysql,postgresql}.sql}，靠人手保持同步 —— 已经漂移出过真实故障
   * （org 的 PG 版整份是从 model 抄的，DELETE 了 org 库里根本不存在的建模表，
   * 在 PostgreSQL 上第 4 行就报表不存在）。
   */
  public static String identityResource() {
    return "db/seed/identity.sql";
  }

  /** 第二个租户「启航科技」。只有 multi 模式执行。 */
  public static String identityMultiResource() {
    return "db/seed/identity-multi.sql";
  }

  /**
   * 项目成员。各模块一份、内容不同，是「服务角色差异」而非重复：
   * 组织平台每个项目写 {@code warehouse} + {@code metadata} 两行，
   * 仓建设只写 {@code warehouse}（本进程只服务仓建设）。
   */
  public static String membersResource() {
    return "db/seed/members.sql";
  }
}
