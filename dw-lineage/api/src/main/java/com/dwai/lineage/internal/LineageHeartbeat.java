package com.dwai.lineage.internal;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LineageHeartbeat implements ApplicationRunner {
    private final OrgClient org;

    public LineageHeartbeat(OrgClient org) {
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
