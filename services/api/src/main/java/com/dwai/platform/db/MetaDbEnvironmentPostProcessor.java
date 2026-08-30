package com.dwai.platform.db;

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
    String vendor = MetaDb.detect(type, urlMissing ? "" : url);

    Map<String, Object> map = new HashMap<>();
    if (urlMissing && MetaDb.H2.equals(vendor)) {
      String home = firstNonBlank(env.getProperty("DW_AI_HOME"), System.getProperty("user.dir"));
      url = MetaDb.h2FileUrl(home);
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
    map.put("spring.flyway.locations", MetaDb.flywayLocation(vendor));
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
