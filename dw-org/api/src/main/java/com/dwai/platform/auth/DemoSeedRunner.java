package com.dwai.platform.auth;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.dwai.platform.db.MetaDb;
import com.dwai.platform.meta.mapper.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/** 空库灌演示租户 / 张三。已有租户则跳过。 */
@Order(1)
@Component
public class DemoSeedRunner implements ApplicationRunner {
  private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
  private final TenantMapper tenants;
  private final DataSource dataSource;

  public DemoSeedRunner(TenantMapper tenants, DataSource dataSource) {
    this.tenants = tenants;
    this.dataSource = dataSource;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    if (tenants.selectCount(Wrappers.emptyWrapper()) > 0) return;
    try (var c = dataSource.getConnection()) {
      String vendor = MetaDb.detect("", c.getMetaData().getURL());
      ScriptUtils.executeSqlScript(c, new ClassPathResource(MetaDb.seedResource(vendor)));
    }
    log.info("已写入演示数据");
  }
}
