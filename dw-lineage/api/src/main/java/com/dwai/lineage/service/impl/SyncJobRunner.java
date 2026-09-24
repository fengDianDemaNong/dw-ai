package com.dwai.lineage.service.impl;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import com.dwai.lineage.enums.SyncStatus;
import com.dwai.lineage.persistence.SyncJobRepository;
import com.dwai.lineage.persistence.SyncJobRow;
import com.dwai.lineage.tenant.LineageContext;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 导入任务的执行器。
 *
 * <p>为什么要异步：按数据目录导入可能是上万张表，每张表都要往 Gravitino / dbx 打一次请求，
 * 放在 HTTP 请求里同步跑必然超时，而且失败后前面导入的进度也说不清楚。
 *
 * <p><b>并发上限是必须的</b>：同时提交几个大任务会把外部元数据服务打爆 ——
 * 那是别人的服务，压垮它比自己慢一点严重得多。超出上限的任务在队列里等着，
 * 状态仍是 {@code PENDING}，页面上看得到。
 */
@Component
@DependsOn("flywayInitializer")
public class SyncJobRunner {

    private static final Logger logger = LoggerFactory.getLogger(SyncJobRunner.class);

    /** 同时执行的导入任务数。默认 2 —— 导入是 IO 密集且打的是别人的服务，不宜放开。 */
    @Value("${metadata.sync.max-concurrent-jobs:2}")
    private int maxConcurrentJobs = 2;

    private final SyncJobRepository repository;
    private volatile ExecutorService pool;

    public SyncJobRunner(SyncJobRepository repository) {
        this.repository = repository;
    }

    /**
     * 启动时清理上次残留的任务。
     *
     * <p>服务重启后，之前 {@code RUNNING} 的任务永远不会再动 —— 执行它的线程已经没了。
     * 不清理的话页面会一直转圈等一个不可能完成的任务。
     *
     * <p>类上的 {@code @DependsOn} 保证它排在 Flyway 迁移之后：
     * 两者都是启动阶段动作，Bean 创建顺序不保证，谁先跑全看运气。
     * 这一句先跑的话，空库启动看到的是 H2 的
     * 「Table "SYNC_JOB" not found」加一整屏堆栈，而不是 Flyway
     * 明确的迁移失败信息 —— 后者才是用户需要的。
     */
    @PostConstruct
    public void failInterruptedJobs() {
        int n = repository.failInterruptedJobs("服务重启，任务被中断。请重新提交");
        if (n > 0) {
            logger.warn("启动清理：{} 个导入任务因服务重启被标记为失败", n);
        }
    }

    /**
     * 提交任务。
     *
     * <p><b>必须用 {@link TenantContextHolder#wrap} 把租户上下文带进任务线程</b> ——
     * 上下文存在 ThreadLocal 里，不透传的话任务线程里所有仓储调用都会抛
     * 「缺少租户上下文」。这是本项目里最容易踩的坑，{@code SqlParseExecutor} 也是这么做的。
     */
    public void submit(LineageContext ctx, long jobId, Runnable task) {
        ExecutorService executor = pool();
        Runnable wrapped = TenantContextHolder.wrap(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                // 接 Throwable 而不是 Exception：导入上万张表时 OutOfMemoryError 与
                // StackOverflowError 都可能发生，而它们是 Error 不是 Exception。
                // 漏掉那一半，任务就停在 RUNNING，页面一直转圈等一个不会回来的结果 ——
                // 正是本类开头说要避免的那种情况
                logger.error("导入任务执行失败, {}, jobId={}", ctx, jobId, t);
                safelyMarkFailed(ctx, jobId, t);
                if (t instanceof Error error) {
                    // 标记完再抛：Error 说明 JVM 层面出了问题，不该被悄悄咽掉，
                    // 交给线程池的未捕获异常处理去记录
                    throw error;
                }
            }
            return null;
        })::get;
        executor.submit(wrapped);
    }

    /** 任务体自己没能收尾时的兜底，避免任务永远停在 RUNNING。 */
    private void safelyMarkFailed(LineageContext ctx, long jobId, Throwable cause) {
        try {
            repository.finish(ctx, jobId, SyncStatus.FAILED,
                    cause.getMessage() == null ? cause.toString() : cause.getMessage(), null);
        } catch (Exception e) {
            // 连收尾都失败就只能记日志了，再抛也没人接
            logger.error("标记任务失败时又出错, jobId={}", jobId, e);
        }
    }

    private ExecutorService pool() {
        ExecutorService current = pool;
        if (current == null) {
            synchronized (this) {
                current = pool;
                if (current == null) {
                    current = Executors.newFixedThreadPool(Math.max(1, maxConcurrentJobs),
                            namedThreadFactory());
                    pool = current;
                }
            }
        }
        return current;
    }

    private static ThreadFactory namedThreadFactory() {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, "meta-sync-" + seq.incrementAndGet());
            // 守护线程：导入没跑完也不该拖住进程退出，重启后会被标成失败并可重新提交
            t.setDaemon(true);
            return t;
        };
    }

    @PreDestroy
    public void shutdown() {
        ExecutorService current = pool;
        if (current != null) {
            current.shutdownNow();
            try {
                current.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
