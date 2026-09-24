package com.dwai.lineage.persistence;

import com.dwai.lineage.dto.CatalogSearchHit;
import com.dwai.lineage.dto.CatalogStats;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;
import com.dwai.lineage.tenant.LineageContext;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.Optional;

/**
 * 血缘侧的表/字段目录。
 *
 * <p>与 {@link LineageRepository}（版本与边）分开：目录是累积的、和版本无关，
 * 版本只挂在目标表上。拆开后「保存一次解析」这件事就清楚了 ——
 * 先把涉及的表和字段 upsert 进目录拿到 id，再按目标表建版本、写边。
 *
 * <p><b>租户约束</b>：每个方法第一个参数固定为 {@link LineageContext}，
 * 实现里所有 SQL 必须带 {@code tenant_id} 与 {@code project_id}。
 */
public interface LineageCatalogRepository {

    /**
     * 批量 upsert 表，已存在的不动。
     *
     * @param tableFullNames 形如 {@code schema.table}
     * @return fullName -> id，包含新增与已存在的全部
     */
    Map<String, Long> upsertTables(LineageContext ctx, String dbType, Collection<String> tableFullNames);

    /**
     * 同上，并把描述属性（中文名、表类型、备注）一并写入 —— 保存血缘时用这个。
     *
     * <p>已存在的行<b>会更新描述</b>：重新解析保存是刷新快照的唯一途径。
     * 但传进来是 null 时不覆盖旧值，见实现里的 {@code coalesce}。
     *
     * @param descriptors 表全名 → 该表的描述；没有就返回 null。
     *                    通常直接传 {@code AnalyzedLineage::descriptorOf}
     */
    Map<String, Long> upsertTables(LineageContext ctx, String dbType,
                                   Collection<String> tableFullNames,
                                   Function<String, TableDescriptor> descriptors);

    /**
     * 批量 upsert 字段，已存在的不动。
     *
     * @param tableIds          由 {@link #upsertTables} 返回，用于定位字段归属
     * @param columnFullNames   形如 {@code schema.table.column}
     * @return fullName -> id
     */
    Map<String, Long> upsertColumns(LineageContext ctx, Map<String, Long> tableIds,
                                    Collection<String> columnFullNames);

    /**
     * 同上，并把字段的类型与中文名一并写入。
     *
     * @param descriptors 表全名 → 该<b>表</b>的描述；字段描述从里面按列名取。
     *                    与 {@link #upsertTables} 传同一个函数即可
     */
    Map<String, Long> upsertColumns(LineageContext ctx, Map<String, Long> tableIds,
                                    Collection<String> columnFullNames,
                                    Function<String, TableDescriptor> descriptors);

    /**
     * 把某张表的描述同步到血缘侧（按<b>完整全名</b>精确匹配，可能一行都不命中）。
     *
     * <p>用户在页面上手工改了中文名时用它写穿。描述是快照，正常靠重新解析保存刷新；
     * 但「刚在这张表上点了保存」是个明确的意图，页面必须当场看到变化 ——
     * 不写穿的话那个保存按钮看起来就像坏了。
     *
     * @return 影响的行数；0 表示血缘里还没有这张表
     */
    int updateTableDescription(LineageContext ctx, String fullName,
                               String tableType, String comment, String remark);

    /** 同上，字段级。 */
    int updateColumnDescription(LineageContext ctx, String fullName,
                                String dataType, String comment, String remark);

    /** 目录中出现过的全部库名（对 schema_name 去重），升序。 */
    List<String> listSchemas(LineageContext ctx);

    /** 出现过的数据目录列表，供页面上的「数据目录」下拉。不含 null。 */
    List<String> listCatalogs(LineageContext ctx);

    /** 某个库下的表；schema 为空则返回全部。 */
    List<LineageTableRow> listTables(LineageContext ctx, String catalog, String schema);

    Optional<LineageTableRow> findTableByFullName(LineageContext ctx, String fullName);

    /**
     * 按全名批量取表，结果按 {@code fullName} 升序。
     *
     * <p>给「已知一批全名、要拿到对应行」的场景用（上游表列表、删除前的引用检查）。
     * 那些地方原先是 {@code listTables(ctx, null, null)} 全量加载再在 Java 里过滤 ——
     * 为找几张表把整个项目的表都读进内存，表一多就线性变慢。
     */
    List<LineageTableRow> findTablesByFullNames(LineageContext ctx, Collection<String> fullNames);

    /**
     * 按全名批量取字段，结果按 {@code fullName} 升序。
     *
     * <p>替代「全量加载表 + 逐表查字段再过滤」那种 N+1 写法。
     */
    List<LineageColumnRow> findColumnsByFullNames(LineageContext ctx, Collection<String> fullNames);

    Optional<LineageTableRow> findTableById(LineageContext ctx, long id);

    /** 某张表的字段，按 ordinal 再按字段名排序。 */
    List<LineageColumnRow> listColumns(LineageContext ctx, long tableId);

    Optional<LineageColumnRow> findColumnById(LineageContext ctx, long id);

    /** 删除一张表及其字段。调用方负责先确认没有下游引用。 */
    boolean deleteTable(LineageContext ctx, long tableId);

    boolean deleteColumn(LineageContext ctx, long columnId);

    /**
     * 全局搜索。
     *
     * <p>搜索范围<b>跨两侧</b>：表名/字段名来自血缘目录，中文名/备注/表类型来自元数据目录，
     * 按 {@code full_name} 关联（血缘侧的全名保留原始大小写，元数据侧恒为小写，
     * 所以关联时对血缘侧加 {@code lower()}）。
     *
     * <p>多关键字是 <b>AND</b> 语义、词内跨字段 <b>OR</b>、统一大小写不敏感，
     * 且<b>全部下推到 SQL</b>。参照项目 mdm 只把第一个词下推、其余在 Java 里用
     * {@code String.contains()} 过滤，结果是多词搜索区分大小写而单词搜索不区分，
     * 行为不自洽。
     *
     * <p>命中表信息时返回一行（字段为空）；命中字段时每个匹配字段一行。
     * 表级已经命中的表，不再重复列出它的每个字段 —— 否则搜一个表名会刷出它两百个字段。
     *
     * @param keywords  已拆分好的关键字，空列表表示不按关键字过滤
     * @param tableType 可选，按数仓表类型过滤
     */
    List<CatalogSearchHit> search(LineageContext ctx, List<String> keywords,
                                  String tableType, int limit);

    /**
     * 概览首页的统计。
     *
     * <p>放在仓储层是因为这些数字只能靠聚合查询拿：让服务层把表列出来再逐个数，
     * 就退化成 N+1，而这正是要避的东西。
     *
     * @param recentLimit 最近解析记录的条数上限
     * @param listLimit   两个「无血缘」清单各自的条数上限
     */
    CatalogStats stats(LineageContext ctx, int recentLimit, int listLimit);
}
