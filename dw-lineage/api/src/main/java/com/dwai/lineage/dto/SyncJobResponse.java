package com.dwai.lineage.dto;

import com.dwai.lineage.persistence.SyncJobRow;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 导入任务的进度与结果，供页面轮询。
 *
 * @param total    展开完成前为 0 —— 此时还不知道要导多少张表，进度条应显示「正在统计」
 * @param percent  进度百分比，同样在展开完成前为 0
 * @param finished 已结束（成功/部分失败/失败），前端据此停止轮询
 */
public record SyncJobResponse(long id,
                              long sourceId,
                              String scope,
                              String sourceCatalog,
                              String sourceDatabase,
                              String sourceSchema,
                              String targetCatalog,
                              String status,
                              boolean finished,
                              int total,
                              int done,
                              int percent,
                              int createdCnt,
                              int updatedCnt,
                              int skippedCnt,
                              int failedCnt,
                              String message,
                              List<String> failures,
                              LocalDateTime startedAt,
                              LocalDateTime finishedAt,
                              LocalDateTime createdAt) {

    public static SyncJobResponse from(SyncJobRow row) {
        return new SyncJobResponse(
                row.id(), row.sourceId(), row.scope().name(),
                row.sourceCatalog(), row.sourceDatabase(), row.sourceSchema(),
                row.targetCatalog(), row.status().name(), row.status().isFinished(),
                row.total(), row.done(), row.progressPercent(),
                row.createdCnt(), row.updatedCnt(), row.skippedCnt(), row.failedCnt(),
                row.message(), row.failures(),
                row.startedAt(), row.finishedAt(), row.createdAt());
    }
}
