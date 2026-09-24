package com.dwai.platform.auth;

import com.dwai.platform.meta.mapper.TenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;

/**
 * 演示数据灌入器。
 *
 * 启动时不再自动灌演示数据，空库只保留 BootstrapAdminRunner 建的默认 admin。
 * 需要演示租户 / 张三等数据时执行 bin/seed-demo.sh（调 dw-common 的
 * com.dwai.platform.meta.seed.SeedMain）。SQL 分两层：身份层 db/seed/identity*.sql
 * 在 dw-common（与仓建设共用），本模块只补自己那份 db/seed/members.sql。
 *
 * 保留此类骨架是为兼容已有引用与后续按需启用；逻辑入口已移至 SeedMain。
 */
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
    // 启动不灌演示数据：默认只有 admin。演示数据走 bin/seed-demo.sh。
    log.debug("DemoSeedRunner 已禁用自动灌数据，需要演示数据请执行 bin/seed-demo.sh");
  }
}
