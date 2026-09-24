package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.SyncStatus;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;
import java.util.Optional;

/**
 * 导入任务的存取。
 *
 * <p>与本包其它仓储一样，方法首参强制 {@link LineageContext}，
 * 唯一的例外是 {@link #failInterruptedJobs(String)} —— 它是启动时的一次性清理，
 * 那时还没有任何请求上下文。
 */
public interface SyncJobRepository {

    long create(LineageContext ctx, SyncJobRow job);

    Optional<SyncJobRow> findById(LineageContext ctx, long id);

    List<SyncJobRow> listRecent(LineageContext ctx, int limit);

    void markRunning(LineageContext ctx, long id);

    /** 展开完成后写入总数，进度条从这一刻起才有分母。 */
    void updateTotal(LineageContext ctx, long id, int total);

    void updateProgress(LineageContext ctx, long id, int done, int created, int updated,
                        int skipped, int failed);

    void finish(LineageContext ctx, long id, SyncStatus status, String message,
                List<String> failures);

    /** 服务重启后把残留的 RUNNING / PENDING 标成 FAILED，返回处理条数。 */
    int failInterruptedJobs(String reason);
}
