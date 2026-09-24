package com.dwai.lineage.service.metadata;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link com.dwai.lineage.service.metadata.provider.GravitinoMetadataProvider} 逐表并发加载用的线程池。
 *
 * <p>从 {@link MetadataServiceFactory} 里抽出来，是因为元数据源改成可配置之后，
 * 构造 provider 的地方不止一处（解析主流程、按配置构造的来源），
 * 留在原地会导致每处各建一个池子。
 *
 * <p>懒加载：不用 Gravitino 的部署（只解析 DDL）不该白白占着线程。
 */
@Component
public class GravitinoExecutor {

    /** 逐表加载的并发度，避免一条 SQL 涉及几十张表时串行往返。 */
    @Value("${metadata.gravitino.concurrency:8}")
    private int concurrency = 8;

    /** 单张表的加载超时（秒），超时按「结构未知」降级处理。 */
    @Value("${metadata.gravitino.per-table-timeout-seconds:10}")
    private long perTableTimeoutSeconds = 10;

    private volatile ExecutorService executor;

    public long perTableTimeoutSeconds() {
        return perTableTimeoutSeconds;
    }

    public ExecutorService executor() {
        ExecutorService local = executor;
        if (local == null) {
            synchronized (this) {
                local = executor;
                if (local == null) {
                    local = Executors.newFixedThreadPool(Math.max(1, concurrency), namedThreadFactory());
                    executor = local;
                }
            }
        }
        return local;
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "gravitino-meta-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    public void shutdown() {
        ExecutorService local = executor;
        if (local != null) {
            local.shutdownNow();
        }
    }
}
