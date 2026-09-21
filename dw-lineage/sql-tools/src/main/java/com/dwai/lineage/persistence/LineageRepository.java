package com.dwai.lineage.persistence;

import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;
import com.dwai.lineage.tenant.LineageContext;

import java.util.List;
import java.util.function.Function;
import java.util.Optional;

/**
 * 血缘版本与边的持久化。
 *
 * <p>表/字段目录由 {@link LineageCatalogRepository} 管，这里只管版本和边。
 *
 * <p><b>租户约束</b>：每个方法的第一个参数固定为 {@link LineageContext}，
 * 从签名上强制调用方传入租户与项目；实现里所有 SQL 都必须带
 * {@code tenant_id} 与 {@code project_id} 条件。
 * 这一约束由 {@code TenantIsolationArchTest} 做静态检查兜底。
 */
public interface LineageRepository {

    /**
     * 保存一次解析结果。
     *
     * <p>一段 SQL 里可能有多条 INSERT，产出多张目标表 —— <b>每张目标表各建一个版本</b>，
     * 各自的版本号独立递增，各自成为自己那张表的当前版本。
     * 涉及的表与字段会先 upsert 进目录（累积，不会覆盖别的表）。
     *
     * @return 本次新建的版本，按目标表全名排序；SQL 里没有任何血缘时返回空列表
     */
    List<LineageVersionRow> saveVersions(LineageContext ctx, String dbType, String sqlText, LineageGraph graph);

    /**
     * 同上，并把各表的<b>描述属性快照</b>一并落库。
     *
     * @param descriptors 表全名 → 描述；直接传 {@code AnalyzedLineage::descriptorOf}。
     *                    传 {@code n -> null} 等同于上面那个重载
     */
    List<LineageVersionRow> saveVersions(LineageContext ctx, String dbType, String sqlText,
                                         LineageGraph graph,
                                         Function<String, TableDescriptor> descriptors);

    /** 某张目标表的版本列表，按版本号倒序。 */
    List<LineageVersionRow> listVersions(LineageContext ctx, long targetTableId);

    /** 某张目标表的当前版本：优先取标记为 current 的，否则取最新的。 */
    Optional<LineageVersionRow> currentVersion(LineageContext ctx, long targetTableId);

    Optional<LineageVersionRow> findVersion(LineageContext ctx, long versionId);

    /** 按 SQL 内容哈希查已有版本，用于幂等。 */
    List<LineageVersionRow> findBySqlHash(LineageContext ctx, String sqlHash);

    /** 把某个版本设为当前版本，<b>在它自己的目标表内</b>互斥。 */
    void markCurrent(LineageContext ctx, long versionId);

    /**
     * 删除版本及其全部边。目录中的表与字段不动 —— 它们可能还被别的版本引用。
     * 若删除的是当前版本，自动把该表剩余的最新版本设为当前。
     *
     * @return 是否确实删除了
     */
    boolean deleteVersion(LineageContext ctx, long versionId);

    /**
     * 沿上游追溯。
     *
     * <p>默认沿<b>各表的当前版本</b>的边走。
     *
     * @param columnFullName 起点字段，形如 {@code schema.table.column}
     * @param depth          最大深度
     * @param rootVersionId  可选。指定后，<b>起点表</b>改用该版本的边，其余表仍走当前版本
     */
    List<LineageEdgeRow> upstream(LineageContext ctx, String columnFullName, int depth, Long rootVersionId);

    /** 沿下游追溯，用于影响分析。参数含义同 {@link #upstream}。 */
    List<LineageEdgeRow> downstream(LineageContext ctx, String columnFullName, int depth, Long rootVersionId);

    /**
     * 该表全部字段的<b>直接</b>上游边（一跳，沿各表 current 版本）。
     *
     * <p>表级血缘是列级边的投影：这里返回原始的列级边，由调用方按表聚合。
     * 单独给一个方法而不是逐列调用 {@link #upstream}，是为了避免一张宽表打出几百次查询。
     */
    List<LineageEdgeRow> directUpstreamOfTable(LineageContext ctx, long tableId);

    /** 该表全部字段的直接下游边。删表前查引用用。 */
    List<LineageEdgeRow> directDownstreamOfTable(LineageContext ctx, long tableId);
}
