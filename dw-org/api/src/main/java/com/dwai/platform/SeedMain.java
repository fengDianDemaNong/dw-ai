package com.dwai.platform;

import com.dwai.platform.db.MetaDb;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/** 手动灌演示数据。不启动 Web。须已完成 Flyway 建表。 */
public final class SeedMain {
  public static void main(String[] args) throws Exception {
    String home = env("DW_AI_HOME", System.getProperty("user.dir"));
    String url = env("DB_URL", "");
    String vendor = MetaDb.detect(env("DB_TYPE", ""), url);
    String user;
    String pass;
    if (url.isBlank() && MetaDb.H2.equals(vendor)) {
      url = MetaDb.h2FileUrl(home);
      user = env("DB_USER", "sa");
      pass = env("DB_PASSWORD", "");
      if (isBlank(System.getenv("DB_USER"))) {
        user = "sa";
        pass = env("DB_PASSWORD", "");
      }
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
      ScriptUtils.executeSqlScript(c, new ClassPathResource(MetaDb.seedResource(vendor)));
    }
    System.out.println("演示数据已写入（" + vendor + "）");
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

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
