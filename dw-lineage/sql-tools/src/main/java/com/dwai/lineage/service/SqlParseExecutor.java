package com.dwai.lineage.service;

import jakarta.annotation.PreDestroy;
import com.dwai.lineage.exception.SqlParseException;
import com.dwai.lineage.tenant.TenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 在受控线程中执行 SQL 解析，提供两层保护。
 *
 * <p><b>超时</b>：ANTLR 在病态 SQL 上可能长时间回溯，占住 web 线程直到客户端超时；
 * 这里给解析设上限，超时即放弃。
 *
 * <p><b>栈隔离</b>：深嵌套 SQL 会触发 {@code StackOverflowError}。
 * 它是 {@link Error} 而非 {@link Exception}，若发生在 web 线程上后果不可控。
 * 放到专用线程执行后，栈溢出只会终止该线程，并被转换成一个正常的业务异常返回；
 * 同时这些线程配置了更大的栈，能容纳合理范围内的嵌套深度。
 */
@Component
public class SqlParseExecutor {

    private static final Logger logger = LoggerFactory.getLogger(SqlParseExecutor.class);

    /** 解析线程栈大小，默认 16MB，用于容纳较深的 SQL 嵌套。 */
    @Value("${sql.parse.stack-size-mb:16}")
    private int stackSizeMb = 16;

    /** 单次解析超时（秒）。 */
    @Value("${sql.parse.timeout-seconds:60}")
    private long timeoutSeconds = 60;

    /** 并发解析上限，超出即拒绝，避免大量重 SQL 同时到达打爆内存。 */
    @Value("${sql.parse.max-concurrent:16}")
    private int maxConcurrent = 16;

    private volatile ExecutorService pool;

    /**
     * 执行解析任务。
     *
     * @param description 用于日志与错误提示的任务描述
     */
    public <T> T call(String description, Supplier<T> task) {
        // 关键：解析在独立线程执行，ThreadLocal 不会自动传递。
        // 这里统一包装，把调用方的租户上下文带过去，避免子线程取不到租户信息。
        Supplier<T> contextAware = TenantContextHolder.wrap(task);

        Future<T> future;
        try {
            future = pool().submit(contextAware::get);
        } catch (RejectedExecutionException e) {
            throw new SqlParseException("解析请求过多，请稍后重试", e);
        }

        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            logger.warn("{} 解析超时（{}s）", description, timeoutSeconds);
            throw new SqlParseException(
                    "SQL 解析超时（" + timeoutSeconds + " 秒），SQL 可能过于复杂，请尝试拆分", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SqlParseException("解析被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StackOverflowError) {
                logger.warn("{} 触发栈溢出，SQL 嵌套过深", description);
                throw new SqlParseException("SQL 嵌套层级过深，无法解析，请简化后重试");
            }
            if (cause instanceof OutOfMemoryError) {
                logger.error("{} 触发内存溢出", description);
                throw new SqlParseException("SQL 过于复杂导致内存不足，请拆分后重试");
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new SqlParseException("SQL解析失败: " + (cause == null ? e.getMessage() : cause.getMessage()), e);
        }
    }

    private ExecutorService pool() {
        ExecutorService local = pool;
        if (local == null) {
            synchronized (this) {
                local = pool;
                if (local == null) {
                    local = createPool();
                    pool = local;
                }
            }
        }
        return local;
    }

    private ExecutorService createPool() {
        AtomicInteger counter = new AtomicInteger();
        long stackBytes = (long) Math.max(1, stackSizeMb) * 1024 * 1024;

        // SynchronousQueue + CallerRuns 之外的拒绝策略：直接拒绝，让上层返回「请稍后重试」
        return new ThreadPoolExecutor(
                1, Math.max(1, maxConcurrent),
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(null, r, "sql-parse-" + counter.incrementAndGet(), stackBytes);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        ExecutorService local = pool;
        if (local != null) {
            local.shutdownNow();
        }
    }
}
