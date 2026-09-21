package com.dwai.lineage.service;

import com.dwai.lineage.dto.MetaSyncRequest;
import com.dwai.lineage.dto.SyncJobResponse;
import com.dwai.lineage.service.metadata.dbx.DbxConnectionSummary;
import com.dwai.lineage.dto.RemoteTableDetail;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;

/**
 * 把外部元数据服务（Gravitino / dbx）里的表结构同步进本地元数据目录。
 *
 * <p>同步只写 {@code meta_*}，<b>绝不碰血缘表</b> —— 两者隔离存储的意义就在这里：
 * 元数据是外部的客观事实，血缘是从 SQL 推导出来的，互不污染。
 */
public interface MetaSyncService {

    /**
     * 逐级浏览外部源的层级结构。
     *
     * <p>Gravitino 是 metalake → catalog → schema → table 四级，
     * dbx 是 database → schema → table 三级。前端按已填的参数决定当前在问哪一级：
     * 传得越全，返回的层级越深。
     *
     * @param connectionId dbx 专用。用户在弹窗里现选的连接 —— 这一步必须能传进来，
     *                     否则「先选连接再列库」的级联就断了。为空时回落到来源配置里的默认值
     * @return 当前这一级的名字列表
     */
    List<String> browse(LineageContext ctx, long sourceId, String metalake, String catalog,
                        String database, String schema, String connectionId);

    /**
     * 提交一次导入，<b>立刻返回任务 id</b>，导入在后台执行。
     *
     * <p>不做成同步的原因：按数据目录导入可能是上万张表，每张都要往外部服务打一次请求，
     * 放在 HTTP 请求里必然超时，失败后也说不清前面导入了多少。
     */
    /**
     * 只读取远程一张表的结构，<b>不落库</b>。
     *
     * <p>供「元数据」页切到某个数据服务后浏览用：{@link #browse} 只给得出名字，
     * 光看名字判断不了要不要导入。
     *
     * <p>与导入走同一份字段映射，所以这里看到的就是导进来的样子。
     *
     * @param table 表名（不含库名），必填；其余参数与 {@link #browse} 同义
     */
    RemoteTableDetail loadRemoteTable(LineageContext ctx, long sourceId, String metalake,
                                      String catalog, String database, String schema,
                                      String table, String connectionId);

    long submitSync(LineageContext ctx, MetaSyncRequest request);

    /** 查任务进度与结果，供页面轮询。 */
    SyncJobResponse getJob(LineageContext ctx, long jobId);

    /** 最近的导入任务。 */
    List<SyncJobResponse> listJobs(LineageContext ctx, int limit);

    /**
     * dbx 中已保存的连接，供导入弹窗的「连接」下拉。
     *
     * <p>有了它就不必再让用户手写 {@code extraConfig.connectionId} 了。
     */
    List<DbxConnectionSummary> listDbxConnections(LineageContext ctx, long sourceId);
}
