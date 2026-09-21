package com.dwai.lineage.tenant;

import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 当前请求的租户上下文。
 *
 * <p><b>跨线程注意</b>：{@link ThreadLocal} 不会自动传递到其它线程。
 * 本项目的 SQL 解析会被放到独立线程执行（见 {@code SqlParseExecutor}，
 * 目的是隔离深嵌套 SQL 的栈溢出），因此在向其它线程提交任务时
 * <b>必须</b>用 {@link #wrap(Supplier)} 或 {@link #wrapCallable(Callable)} 包装，
 * 否则子线程里取不到租户信息，会直接抛异常而不是静默按空租户查询。
 */
public final class TenantContextHolder {

    private static final ThreadLocal<LineageContext> HOLDER = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static void set(LineageContext context) {
        HOLDER.set(context);
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static Optional<LineageContext> find() {
        return Optional.ofNullable(HOLDER.get());
    }

    /**
     * 取当前上下文，缺失即抛异常。
     *
     * <p>刻意不提供「默认租户」兜底：静默降级到某个默认值会造成跨租户串数据，
     * 宁可失败。
     */
    public static LineageContext require() {
        LineageContext ctx = HOLDER.get();
        if (ctx == null) {
            throw new IllegalStateException(
                    "缺少租户上下文。若在子线程中执行，请用 TenantContextHolder.wrap(...) 包装任务");
        }
        return ctx;
    }

    /** 包装成可在其它线程执行的任务，自动携带当前上下文。 */
    public static <T> Supplier<T> wrap(Supplier<T> task) {
        LineageContext captured = HOLDER.get();
        return () -> {
            LineageContext previous = HOLDER.get();
            if (captured != null) {
                HOLDER.set(captured);
            }
            try {
                return task.get();
            } finally {
                if (previous == null) {
                    HOLDER.remove();
                } else {
                    HOLDER.set(previous);
                }
            }
        };
    }

    /** {@link Callable} 版本。 */
    public static <T> Callable<T> wrapCallable(Callable<T> task) {
        LineageContext captured = HOLDER.get();
        return () -> {
            LineageContext previous = HOLDER.get();
            if (captured != null) {
                HOLDER.set(captured);
            }
            try {
                return task.call();
            } finally {
                if (previous == null) {
                    HOLDER.remove();
                } else {
                    HOLDER.set(previous);
                }
            }
        };
    }

    /** 在指定上下文中执行一段逻辑，执行完恢复原上下文。测试与后台任务常用。 */
    public static <T> T runAs(LineageContext context, Supplier<T> task) {
        LineageContext previous = HOLDER.get();
        HOLDER.set(context);
        try {
            return task.get();
        } finally {
            if (previous == null) {
                HOLDER.remove();
            } else {
                HOLDER.set(previous);
            }
        }
    }
}
