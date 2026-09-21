package com.dwai.platform.internal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class WarehouseHeartbeat implements ApplicationRunner {
  private final OrgClient org;

  public WarehouseHeartbeat(OrgClient org) {
    this.org = org;
  }

  @Override
  public void run(ApplicationArguments args) {
    org.heartbeat();
  }

  @Scheduled(fixedDelay = 30000)
  public void beat() {
    org.heartbeat();
  }
}
