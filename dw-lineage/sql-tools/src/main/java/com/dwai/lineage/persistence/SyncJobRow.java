package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.SyncScope;
import com.dwai.lineage.enums.SyncStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一次元数据导入任务。
 *
 * @param tables         {@code scope=TABLE} 时要导入的表名；其余范围由任务自己展开
 * @param targetCatalog  导入到哪个数据目录；为空时沿用源端
 * @param total          展开完成后才有值，进度条的分母
 * @param failures       失败明细，形如 {@code ods.orders: 连接超时}
 */
public record SyncJobRow(long id,
                         long tenantId,
                         long projectId,
                         long sourceId,
                         SyncScope scope,
                         String sourceCatalog,
                         String sourceDatabase,
                         String sourceSchema,
                         String connectionId,
                         String targetCatalog,
                         List<String> tables,
                         boolean overwriteManual,
                         SyncStatus status,
                         int total,
                         int done,
                         int createdCnt,
                         int updatedCnt,
                         int skippedCnt,
                         int failedCnt,
                         String message,
                         List<String> failures,
                         LocalDateTime startedAt,
                         LocalDateTime finishedAt,
                         LocalDateTime createdAt,
                         LocalDateTime updatedAt) {

    /** 进度百分比，展开完成前返回 0（此时 total 还不知道）。 */
    public int progressPercent() {
        return total <= 0 ? 0 : Math.min(100, done * 100 / total);
    }
}
