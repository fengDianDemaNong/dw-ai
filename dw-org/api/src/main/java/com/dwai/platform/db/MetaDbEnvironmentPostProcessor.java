package com.dwai.platform.db;

import com.dwai.platform.meta.support.SeedDb;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

public class MetaDbEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
  @Override
  public int getOrder() {
    return Ordered.LOWEST_PRECEDENCE;
  }
  @Override
  public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
    String url = firstNonBlank(env.getProperty("DB_URL"), env.getProperty("spring.datasource.url"));
    String type = env.getProperty("DB_TYPE");
    boolean urlMissing = url == null || url.isBlank() || url.equals("jdbc:");
    String vendor = SeedDb.detect(type, urlMissing ? "" : url);

    Map<String, Object> map = new HashMap<>();
    if (urlMissing && SeedDb.H2.equals(vendor)) {
      String home = firstNonBlank(env.getProperty("DW_AI_HOME"), System.getProperty("user.dir"));
      // 只认 dwai.db-file。环境变量 DWAI_DB_FILE 会被 Spring 的宽松绑定映射到同名属性，
      // 所以 conf/env.sh 里那一行是现场真正生效的来源；本文件里的 dw_org 只是
      // 「不走 env.sh、直接 mvn spring-boot:run」时的兜底。
      //
      // 这里曾经回落到 dwai.product —— 那是「进程角色」（org / warehouse / lineage），
      // 与「库文件名」是两个维度的东西。一旦 db-file 缺失，org 进程的 H2 会悄悄变成
      // data/org.mv.db，跟 dwai.db-file 声明的库串味，而且不报错、只有翻文件系统才发现。
      //
      // seed 进程（bin/seed-demo.sh → SeedMain）读的是同一个 DWAI_DB_FILE，
      // 两边必须同库，否则建表在 A、数据在 B。
      url = SeedDb.h2FileUrl(home, firstNonBlank(env.getProperty("dwai.db-file"), "dw_org"));
      map.put("DB_URL", url);
      map.put("spring.datasource.url", url);
      if (isBlank(env.getProperty("DB_USER"))) {
        map.put("spring.datasource.username", "sa");
        map.put("spring.datasource.password", "");
      }
    } else if (!urlMissing) {
      map.put("spring.datasource.url", url);
    }
    map.put("dwai.db-type", vendor);
    map.put("spring.flyway.locations", SeedDb.flywayLocation(vendor));
    env.getPropertySources().addFirst(new MapPropertySource("dwai-db", map));
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  private static String firstNonBlank(String a, String b) {
    if (!isBlank(a)) {
      return a;
    }
    return isBlank(b) ? "" : b;
  }
}
