package com.dwai.platform.db;

import java.nio.file.Path;
import java.util.Locale;

public final class MetaDb {
  public static final String H2 = "h2";
  public static final String MYSQL = "mysql";
  public static final String POSTGRESQL = "postgresql";

  private MetaDb() {}

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

  public static String h2FileUrl(String homeDir) {
    Path file = Path.of(homeDir == null || homeDir.isBlank() ? "." : homeDir, "data", "dwai").toAbsolutePath();
    return "jdbc:h2:file:" + file
        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;AUTO_SERVER=TRUE;DB_CLOSE_DELAY=-1";
  }

  public static String flywayLocation(String vendor) {
    if (POSTGRESQL.equals(vendor)) {
      return "classpath:db/migration/postgresql";
    }
    return "classpath:db/migration/mysql";
  }

  public static String seedResource(String vendor) {
    if (POSTGRESQL.equals(vendor)) {
      return "db/seed/demo-postgresql.sql";
    }
    return "db/seed/demo-mysql.sql";
  }

  public static boolean isPostgres(String productName) {
    return productName != null && productName.toLowerCase(Locale.ROOT).contains("postgresql");
  }
}
