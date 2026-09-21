package com.dwai.lineage.persistence;

import com.dwai.lineage.tenant.LineageContext;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 元数据目录的读写。
 *
 * <p><b>租户约束</b>：每个方法第一个参数固定为 {@link LineageContext}，
 * 实现里所有 SQL 必须带 {@code tenant_id} 与 {@code project_id}，
 * 由 {@code TenantIsolationArchTest} 静态检查兜底。
 */
public interface MetaCatalogRepository {

    /** upsert 一张表的结果。 */
    record UpsertResult(long tableId, boolean created, boolean skipped) {

        public static UpsertResult skipped(long tableId) {
            return new UpsertResult(tableId, false, true);
        }
    }

    /**
     * 写入或更新一张表及其字段。
     *
     * <p>保护规则：既有表的 {@code source} 为 {@code MANUAL} 且 {@code overwriteManual=false} 时
     * <b>整张表跳过不动</b>；否则更新表，并对字段做增量合并 ——
     * 既有字段中 {@code source=MANUAL} 的保留其中文名与备注，
     * 上游已不存在且非手工添加的字段会被删除。
     *
     * @return 表 id，以及本次是新增还是被跳过
     */
    UpsertResult upsert(LineageContext ctx, MetaTableRow table, List<MetaColumnRow> columns,
                        boolean overwriteManual);

    List<String> listSchemas(LineageContext ctx);

    /** 出现过的数据目录列表，供页面上的「数据目录」下拉。不含 null。 */
    List<String> listCatalogs(LineageContext ctx);

    /** 分页查询；schema / keyword 为空表示不过滤。keyword 匹配表名与中文名。 */
    List<MetaTableRow> list(LineageContext ctx, String catalog, String schema, String keyword, int offset, int limit);

    long count(LineageContext ctx, String catalog, String schema, String keyword);

    Optional<MetaTableRow> findById(LineageContext ctx, long id);

    Optional<MetaTableRow> findByFullName(LineageContext ctx, String fullName);

    /** 按表全名批量取，供 {@code CatalogMetadataProvider} 解析时点查。 */
    List<MetaTableRow> findByFullNames(LineageContext ctx, Collection<String> fullNames);

    /**
     * 按「不带数据目录的 {@code schema.table}」找表，用于 SQL 里没写数据目录时的回退查找。
     *
     * <p>匹配 {@code full_name} 恰好等于它，或者以 {@code .schema.table} 结尾
     * （即某个数据目录下的同名表）。可能返回多行 —— 多个数据目录下有同名表时，
     * 调用方要把它当作「有歧义」处理，而不是随便挑一个。
     */
    List<MetaTableRow> findBySchemaAndTable(LineageContext ctx, Collection<String> schemaTables);

    List<MetaColumnRow> listColumns(LineageContext ctx, long tableId);

    /** 一次取多张表的字段，避免 provider 逐表往返。 */
    Map<Long, List<MetaColumnRow>> listColumnsByTableIds(LineageContext ctx, Collection<Long> tableIds);

    Optional<MetaColumnRow> findColumnById(LineageContext ctx, long id);

    /** 手工修改表的描述属性，会把 source 置为 MANUAL。 */
    boolean updateTableInfo(LineageContext ctx, long id, String tableType, String comment, String remark);

    /** 手工修改字段的描述属性，会把 source 置为 MANUAL。 */
    boolean updateColumnInfo(LineageContext ctx, long id, String dataType, String comment, String remark);

    boolean deleteTable(LineageContext ctx, long id);

    boolean deleteColumn(LineageContext ctx, long id);
}
