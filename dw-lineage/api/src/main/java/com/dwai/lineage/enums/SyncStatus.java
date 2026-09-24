package com.dwai.lineage.enums;

/** 导入任务的状态。 */
public enum SyncStatus {

    /** 已提交，等待执行器取走。 */
    PENDING,

    /** 执行中。服务重启会把残留的 RUNNING 标成 FAILED，否则页面会一直转圈。 */
    RUNNING,

    /** 全部成功。 */
    SUCCESS,

    /** 部分表失败，其余已导入。这种情况必须与 FAILED 区分开 —— 用户需要知道哪些成了。 */
    PARTIAL,

    /** 整体失败（连不上源、展开阶段就出错等），一张表都没导入。 */
    FAILED;

    public boolean isFinished() {
        return this == SUCCESS || this == PARTIAL || this == FAILED;
    }
}
