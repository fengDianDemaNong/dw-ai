package com.dwai.lineage.persistence;

import com.dwai.lineage.dto.CatalogSearchHit;
import com.dwai.lineage.dto.CatalogStats;
import com.dwai.lineage.service.metadata.provider.ColumnDescriptor;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 血缘目录的 JdbcTemplate 实现。
 *
 * <p>upsert 采用「先查已有、再插缺失、最后回读」的写法，而不是各方言的
 * {@code ON DUPLICATE KEY} / {@code ON CONFLICT} / {@code MERGE} ——
 * 三种语法互不兼容，写成方言分支就失去了「三后端共用一份 SQL」的好处。
 * 回读也顺便解决了批量插入拿不到自增 id 的问题（与 {@code JdbcLineageRepository} 一致）。
 */
@Repository
public class JdbcLineageCatalogRepository implements LineageCatalogRepository {

    /** 批量写入的分批大小，与 JdbcLineageRepository 保持一致。 */
    private static final int BATCH_SIZE = 500;

    /** IN 子句的分批大小：Oracle 上限 1000，这里统一保守取值，避免超长 SQL。 */
    private static final int IN_CHUNK = 500;

    private static final String TABLE_COLUMNS =
            "id, tenant_id, project_id, catalog_name, schema_name, table_name, full_name, "
                    + "db_type, is_temp, table_type, comment, remark, created_at, updated_at";

    private static final String COLUMN_COLUMNS =
            "id, tenant_id, project_id, table_id, column_name, full_name, ordinal, is_partition, "
                    + "data_type, comment, remark, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcLineageCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================================================================
    // upsert
    // ==================================================================

    @Override
    @Transactional
    public Map<String, Long> upsertTables(LineageContext ctx, String dbType,
                                          Collection<String> tableFullNames) {
        return upsertTables(ctx, dbType, tableFullNames, name -> null);
    }

    @Override
    @Transactional
    public Map<String, Long> upsertTables(LineageContext ctx, String dbType,
                                          Collection<String> tableFullNames,
                                          Function<String, TableDescriptor> descriptors) {
        Set<String> wanted = new LinkedHashSet<>(tableFullNames == null ? List.of() : tableFullNames);
        if (wanted.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> existing = loadTableIds(ctx, wanted);
        List<String> missing = wanted.stream().filter(n -> !existing.containsKey(n)).toList();

        for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
            List<String> batch = missing.subList(start, Math.min(start + BATCH_SIZE, missing.size()));
            jdbc.batchUpdate(
                    "insert into lineage_table(tenant_id, project_id, catalog_name, schema_name, "
                            + "table_name, full_name, db_type, table_type, comment, remark) "
                            + "values(?,?,?,?,?,?,?,?,?,?)",
                    batch, batch.size(), (ps, fullName) -> {
                        TableDescriptor d = descriptors.apply(fullName);
                        ps.setLong(1, ctx.tenantId());
                        ps.setLong(2, ctx.projectId());
                        ps.setString(3, catalogOf(fullName));
                        ps.setString(4, schemaOf(fullName));
                        ps.setString(5, simpleNameOf(fullName));
                        ps.setString(6, fullName);
                        ps.setString(7, dbType);
                        ps.setString(8, d == null ? null : d.tableType());
                        ps.setString(9, d == null ? null : d.comment());
                        ps.setString(10, d == null ? null : d.remark());
                    });
        }

        refreshTableDescriptions(ctx, existing.keySet(), descriptors);
        return missing.isEmpty() ? existing : loadTableIds(ctx, wanted);
    }

    /**
     * 已存在的表：把描述刷新成本次解析拿到的。
     *
     * <p>「重新解析保存一次就能刷新描述」是这套快照方案的唯一更新途径，
     * 所以 upsert 不能只插不更。
     *
     * <p><b>{@code coalesce(?, 列)} 是关键</b>：这次解析没拿到元数据时传进来的是 null，
     * 直接赋值会把上次存的中文名冲掉 —— 用户看到的就是「描述莫名其妙消失了」。
     * 元数据侧早有同款处理，见
     * {@code MetaCatalogRepositoryTest.syncWithEmptyCommentDoesNotWipeExistingOne}。
     */
    private void refreshTableDescriptions(LineageContext ctx, Collection<String> fullNames,
                                          Function<String, TableDescriptor> descriptors) {
        List<String> withDescription = fullNames.stream()
                .filter(n -> {
                    TableDescriptor d = descriptors.apply(n);
                    return d != null && !d.isEmpty();
                })
                .toList();
        if (withDescription.isEmpty()) {
            return;
        }
        for (int start = 0; start < withDescription.size(); start += BATCH_SIZE) {
            List<String> batch = withDescription.subList(
                    start, Math.min(start + BATCH_SIZE, withDescription.size()));
            jdbc.batchUpdate(
                    "update lineage_table set table_type = coalesce(?, table_type), "
                            + "comment = coalesce(?, comment), remark = coalesce(?, remark), "
                            + "updated_at = current_timestamp "
                            + "where tenant_id = ? and project_id = ? and full_name = ?",
                    batch, batch.size(), (ps, fullName) -> {
                        TableDescriptor d = descriptors.apply(fullName);
                        ps.setString(1, d.tableType());
                        ps.setString(2, d.comment());
                        ps.setString(3, d.remark());
                        ps.setLong(4, ctx.tenantId());
                        ps.setLong(5, ctx.projectId());
                        ps.setString(6, fullName);
                    });
        }
    }

    @Override
    @Transactional
    public Map<String, Long> upsertColumns(LineageContext ctx, Map<String, Long> tableIds,
                                           Collection<String> columnFullNames) {
        return upsertColumns(ctx, tableIds, columnFullNames, name -> null);
    }

    @Override
    @Transactional
    public Map<String, Long> upsertColumns(LineageContext ctx, Map<String, Long> tableIds,
                                           Collection<String> columnFullNames,
                                           Function<String, TableDescriptor> descriptors) {
        Set<String> wanted = new LinkedHashSet<>(columnFullNames == null ? List.of() : columnFullNames);
        if (wanted.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> existing = loadColumnIds(ctx, wanted);
        List<String> missing = wanted.stream().filter(n -> !existing.containsKey(n)).toList();

        for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
            List<String> batch = missing.subList(start, Math.min(start + BATCH_SIZE, missing.size()));
            jdbc.batchUpdate(
                    "insert into lineage_column(tenant_id, project_id, table_id, column_name, "
                            + "full_name, ordinal, data_type, comment, remark) "
                            + "values(?,?,?,?,?,?,?,?,?)",
                    batch, batch.size(), (ps, fullName) -> {
                        Long tableId = tableIds.get(tableOf(fullName));
                        if (tableId == null) {
                            // 调用方漏传了这个字段所属的表，早失败好过写出一条挂空表的字段
                            throw new IllegalStateException(
                                    "字段 " + fullName + " 找不到所属表 " + tableOf(fullName));
                        }
                        ColumnDescriptor d = columnDescriptorOf(fullName, descriptors);
                        ps.setLong(1, ctx.tenantId());
                        ps.setLong(2, ctx.projectId());
                        ps.setLong(3, tableId);
                        ps.setString(4, columnOf(fullName));
                        ps.setString(5, fullName);
                        ps.setInt(6, 0);
                        ps.setString(7, d == null ? null : d.dataType());
                        ps.setString(8, d == null ? null : d.comment());
                        ps.setString(9, d == null ? null : d.remark());
                    });
        }

        refreshColumnDescriptions(ctx, existing.keySet(), descriptors);
        return missing.isEmpty() ? existing : loadColumnIds(ctx, wanted);
    }

    /** 字段全名 → 它所属表的描述 → 该列的描述。查不到返回 null（派生列就是这种情况）。 */
    private static ColumnDescriptor columnDescriptorOf(
            String columnFullName, Function<String, TableDescriptor> descriptors) {
        TableDescriptor table = descriptors.apply(tableOf(columnFullName));
        return table == null ? null : table.columnOf(columnOf(columnFullName));
    }

    /** 已存在的字段：刷新描述。{@code coalesce} 的理由同 {@link #refreshTableDescriptions}。 */
    private void refreshColumnDescriptions(LineageContext ctx, Collection<String> fullNames,
                                           Function<String, TableDescriptor> descriptors) {
        List<String> withDescription = fullNames.stream()
                .filter(n -> {
                    ColumnDescriptor d = columnDescriptorOf(n, descriptors);
                    return d != null && !d.isEmpty();
                })
                .toList();
        if (withDescription.isEmpty()) {
            return;
        }
        for (int start = 0; start < withDescription.size(); start += BATCH_SIZE) {
            List<String> batch = withDescription.subList(
                    start, Math.min(start + BATCH_SIZE, withDescription.size()));
            jdbc.batchUpdate(
                    "update lineage_column set data_type = coalesce(?, data_type), "
                            + "comment = coalesce(?, comment), remark = coalesce(?, remark), "
                            + "updated_at = current_timestamp "
                            + "where tenant_id = ? and project_id = ? and full_name = ?",
                    batch, batch.size(), (ps, fullName) -> {
                        ColumnDescriptor d = columnDescriptorOf(fullName, descriptors);
                        ps.setString(1, d.dataType());
                        ps.setString(2, d.comment());
                        ps.setString(3, d.remark());
                        ps.setLong(4, ctx.tenantId());
                        ps.setLong(5, ctx.projectId());
                        ps.setString(6, fullName);
                    });
        }
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public List<String> listSchemas(LineageContext ctx) {
        return jdbc.queryForList(
                "select distinct schema_name from lineage_table "
                        + "where tenant_id = ? and project_id = ? order by schema_name",
                String.class, ctx.tenantId(), ctx.projectId());
    }

    @Override
    public List<String> listCatalogs(LineageContext ctx) {
        return jdbc.queryForList(
                "select distinct catalog_name from lineage_table "
                        + "where tenant_id = ? and project_id = ? and catalog_name is not null "
                        + "order by catalog_name",
                String.class, ctx.tenantId(), ctx.projectId());
    }

    @Override
    public List<LineageTableRow> listTables(LineageContext ctx, String catalog, String schema) {
        List<Object> args = new java.util.ArrayList<>(List.of(ctx.tenantId(), ctx.projectId()));
        StringBuilder where = new StringBuilder();
        // null 与空串都表示「全部目录」。catalog_name 自 1.0.4 起为 NOT NULL，
        // 不再有「没有数据目录的表」这一类，原先空串代表的那一态已经没有意义
        if (catalog != null && !catalog.isBlank()) {
            where.append(" and catalog_name = ?");
            args.add(catalog);
        }
        if (schema != null && !schema.isBlank()) {
            where.append(" and schema_name = ?");
            args.add(schema);
        }
        return jdbc.query("select " + TABLE_COLUMNS + " from lineage_table "
                        + "where tenant_id = ? and project_id = ?" + where
                        + " order by schema_name, table_name",
                TABLE_MAPPER, args.toArray());
    }

    @Override
    public Optional<LineageTableRow> findTableByFullName(LineageContext ctx, String fullName) {
        return single(jdbc.query("select " + TABLE_COLUMNS + " from lineage_table "
                        + "where tenant_id = ? and project_id = ? and full_name = ?",
                TABLE_MAPPER, ctx.tenantId(), ctx.projectId(), fullName));
    }

    @Override
    public Optional<LineageTableRow> findTableById(LineageContext ctx, long id) {
        return single(jdbc.query("select " + TABLE_COLUMNS + " from lineage_table "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                TABLE_MAPPER, ctx.tenantId(), ctx.projectId(), id));
    }

    @Override
    public List<LineageColumnRow> listColumns(LineageContext ctx, long tableId) {
        return jdbc.query("select " + COLUMN_COLUMNS + " from lineage_column "
                        + "where tenant_id = ? and project_id = ? and table_id = ? "
                        + "order by ordinal, column_name",
                COLUMN_MAPPER, ctx.tenantId(), ctx.projectId(), tableId);
    }

    @Override
    public Optional<LineageColumnRow> findColumnById(LineageContext ctx, long id) {
        return single(jdbc.query("select " + COLUMN_COLUMNS + " from lineage_column "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                COLUMN_MAPPER, ctx.tenantId(), ctx.projectId(), id));
    }

    @Override
    public int updateTableDescription(LineageContext ctx, String fullName,
                                      String tableType, String comment, String remark) {
        // 这里是直接赋值而不是 coalesce：用户明确把某一项清空，就该清掉。
        // upsert 那边用 coalesce 是因为「解析没拿到」与「用户想清空」无法区分
        return jdbc.update(
                "update lineage_table set table_type = ?, comment = ?, remark = ?, "
                        + "updated_at = current_timestamp "
                        + "where tenant_id = ? and project_id = ? and lower(full_name) = ?",
                tableType, comment, remark,
                ctx.tenantId(), ctx.projectId(), fullName.toLowerCase(Locale.ROOT));
    }

    @Override
    public int updateColumnDescription(LineageContext ctx, String fullName,
                                       String dataType, String comment, String remark) {
        return jdbc.update(
                "update lineage_column set data_type = ?, comment = ?, remark = ?, "
                        + "updated_at = current_timestamp "
                        + "where tenant_id = ? and project_id = ? and lower(full_name) = ?",
                dataType, comment, remark,
                ctx.tenantId(), ctx.projectId(), fullName.toLowerCase(Locale.ROOT));
    }

    // ==================================================================
    // 删除
    // ==================================================================

    @Override
    @Transactional
    public boolean deleteTable(LineageContext ctx, long tableId) {
        jdbc.update("delete from lineage_column where tenant_id = ? and project_id = ? and table_id = ?",
                ctx.tenantId(), ctx.projectId(), tableId);
        return jdbc.update("delete from lineage_table where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), tableId) > 0;
    }

    @Override
    @Transactional
    public boolean deleteColumn(LineageContext ctx, long columnId) {
        return jdbc.update("delete from lineage_column where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), columnId) > 0;
    }


    // ==================================================================
    // 全局搜索
    // ==================================================================

    /** 表级可搜的字段。中文名/备注/表类型自 1.0.5 起就在血缘侧，不必再关联元数据。 */
    private static final List<String> TABLE_LEVEL_FIELDS = List.of(
            "t.table_name", "t.schema_name",
            "coalesce(t.comment, '')", "coalesce(t.remark, '')", "coalesce(t.table_type, '')");

    /** 字段级可搜的字段。 */
    private static final List<String> COLUMN_LEVEL_FIELDS = List.of(
            "c.column_name", "coalesce(c.comment, '')");

    /**
     * 这里原本 left join meta_table / meta_column 取中文名。
     *
     * <p>那个关联<b>本来就大面积失效</b>：血缘侧的全名按默认目录补全
     * （{@code default.ods.orders}），而元数据从 Gravitino / dbx 同步过来挂在源端 catalog 下
     * （{@code hive_prod.ods.orders}），两边 {@code full_name} 首段不同，永远匹配不上。
     *
     * <p>1.0.5 起描述属性在保存血缘时就快照到血缘侧，搜索直接查本表 ——
     * 结果对了，还省掉两个 join。
     */
    private static final String SEARCH_JOINS = """
            from lineage_table t
            """;

    private static final String COLUMN_JOINS = """
            from lineage_column c
            join lineage_table t
              on t.tenant_id = c.tenant_id and t.project_id = c.project_id and t.id = c.table_id
            """;

    @Override
    public List<CatalogSearchHit> search(LineageContext ctx, List<String> keywords,
                                         String tableType, int limit) {
        List<String> terms = keywords == null ? List.of()
                : keywords.stream().map(String::strip).filter(k -> !k.isEmpty()).toList();

        List<Object> args = new ArrayList<>();

        // 一、表级命中：整张表一行，字段列为空
        String tableMatch = keywordClause(terms, TABLE_LEVEL_FIELDS);
        String tableSql = "select t.id as table_id, t.catalog_name, t.schema_name, t.table_name, t.full_name, "
                + "t.table_type, t.comment as table_comment, t.remark, "
                + "cast(null as bigint) as column_id, cast(null as varchar(256)) as column_name, "
                + "cast(null as varchar(512)) as column_comment "
                + SEARCH_JOINS
                + " where t.tenant_id = ? and t.project_id = ?"
                + typeClause(tableType)
                + (tableMatch.isEmpty() ? "" : " and " + tableMatch);
        args.add(ctx.tenantId());
        args.add(ctx.projectId());
        addTypeArg(args, tableType);
        addKeywordArgs(args, terms, TABLE_LEVEL_FIELDS.size());

        // 二、字段级命中：每个关键字可由表级或该字段命中。
        //     已经在表级整体命中的表要排除掉，否则搜一个表名会把它两百个字段全刷出来。
        List<String> allFields = new ArrayList<>(TABLE_LEVEL_FIELDS);
        allFields.addAll(COLUMN_LEVEL_FIELDS);
        String rowMatch = keywordClause(terms, allFields);
        String columnSql = "select t.id as table_id, t.catalog_name, t.schema_name, t.table_name, t.full_name, "
                + "t.table_type, t.comment as table_comment, t.remark, "
                + "c.id as column_id, c.column_name, c.comment as column_comment "
                + COLUMN_JOINS
                + " where c.tenant_id = ? and c.project_id = ?"
                + typeClause(tableType)
                + (rowMatch.isEmpty() ? "" : " and " + rowMatch)
                + (tableMatch.isEmpty() ? "" : " and not (" + tableMatch + ")");
        args.add(ctx.tenantId());
        args.add(ctx.projectId());
        addTypeArg(args, tableType);
        addKeywordArgs(args, terms, allFields.size());
        addKeywordArgs(args, terms, TABLE_LEVEL_FIELDS.size());

        String sql = tableSql + " union all " + columnSql
                + " order by full_name, column_name limit ?";
        args.add(Math.max(1, limit));

        return jdbc.query(sql, SEARCH_MAPPER, args.toArray());
    }

    /** 每个关键字一组 OR，组间 AND。大小写不敏感靠两边都 lower()。 */
    private static String keywordClause(List<String> terms, List<String> fields) {
        if (terms.isEmpty()) {
            return "";
        }
        String perTerm = fields.stream()
                .map(f -> "lower(" + f + ") like ?")
                .collect(Collectors.joining(" or "));
        return terms.stream()
                .map(t -> "(" + perTerm + ")")
                .collect(Collectors.joining(" and "));
    }

    private static void addKeywordArgs(List<Object> args, List<String> terms, int fieldCount) {
        for (String term : terms) {
            String like = "%" + term.toLowerCase(Locale.ROOT) + "%";
            for (int i = 0; i < fieldCount; i++) {
                args.add(like);
            }
        }
    }

    private static String typeClause(String tableType) {
        return (tableType == null || tableType.isBlank()) ? "" : " and t.table_type = ?";
    }

    private static void addTypeArg(List<Object> args, String tableType) {
        if (tableType != null && !tableType.isBlank()) {
            args.add(tableType);
        }
    }

    private static final RowMapper<CatalogSearchHit> SEARCH_MAPPER = (rs, i) -> new CatalogSearchHit(
            rs.getLong("table_id"), rs.getString("catalog_name"),
            rs.getString("schema_name"), rs.getString("table_name"),
            rs.getString("full_name"), rs.getString("table_type"), rs.getString("table_comment"),
            rs.getString("remark"),
            rs.getObject("column_id") == null ? null : rs.getLong("column_id"),
            rs.getString("column_name"), rs.getString("column_comment"));

    // ==================================================================
    // 辅助
    // ==================================================================

    private Map<String, Long> loadTableIds(LineageContext ctx, Collection<String> fullNames) {
        return loadIdsByFullName(ctx, "lineage_table", fullNames);
    }

    private Map<String, Long> loadColumnIds(LineageContext ctx, Collection<String> fullNames) {
        return loadIdsByFullName(ctx, "lineage_column", fullNames);
    }

    @Override
    public List<LineageTableRow> findTablesByFullNames(LineageContext ctx,
                                                       Collection<String> fullNames) {
        return findByFullNames(ctx, "lineage_table", TABLE_COLUMNS, TABLE_MAPPER, fullNames);
    }

    @Override
    public List<LineageColumnRow> findColumnsByFullNames(LineageContext ctx,
                                                         Collection<String> fullNames) {
        return findByFullNames(ctx, "lineage_column", COLUMN_COLUMNS, COLUMN_MAPPER, fullNames);
    }

    /**
     * 按 full_name 批量取整行。
     *
     * <p>分批发 IN 的写法与 {@link #loadIdsByFullName} 一致 —— 一条 SQL 里塞上万个
     * 占位符在各家数据库上都会出问题。
     *
     * <p>结果按 {@code full_name} 排序，与调用方原先「全量加载后自己 sort」的顺序一致。
     */
    private <T> List<T> findByFullNames(LineageContext ctx, String table, String columns,
                                        RowMapper<T> mapper, Collection<String> fullNames) {
        if (fullNames == null || fullNames.isEmpty()) {
            return List.of();
        }
        List<T> result = new ArrayList<>();
        List<String> all = new ArrayList<>(new LinkedHashSet<>(fullNames));
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<String> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));

            Object[] args = new Object[chunk.size() + 2];
            args[0] = ctx.tenantId();
            args[1] = ctx.projectId();
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 2] = chunk.get(i);
            }
            result.addAll(jdbc.query("select " + columns + " from " + table
                            + " where tenant_id = ? and project_id = ? "
                            + "and full_name in (" + placeholders + ")",
                    mapper, args));
        }
        // 分批查回来的结果要整体再排一次，否则批次边界处顺序是乱的
        result.sort(java.util.Comparator.comparing(FULL_NAME_OF::apply));
        return result;
    }

    /** 两种行都有 fullName，但没有共同接口，只能靠这个取值函数统一排序。 */
    private static final java.util.function.Function<Object, String> FULL_NAME_OF = row -> {
        if (row instanceof LineageTableRow t) {
            return t.fullName();
        }
        if (row instanceof LineageColumnRow c) {
            return c.fullName();
        }
        return "";
    };

    /** 按 full_name 批量取 id。分批发 IN，避免一条 SQL 里塞进上万个占位符。 */
    private Map<String, Long> loadIdsByFullName(LineageContext ctx, String table,
                                                Collection<String> fullNames) {
        Map<String, Long> result = new HashMap<>();
        List<String> all = new ArrayList<>(fullNames);
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<String> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));

            Object[] args = new Object[chunk.size() + 2];
            args[0] = ctx.tenantId();
            args[1] = ctx.projectId();
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 2] = chunk.get(i);
            }

            jdbc.query("select id, full_name from " + table
                            + " where tenant_id = ? and project_id = ? and full_name in (" + placeholders + ")",
                    rs -> {
                        result.put(rs.getString("full_name"), rs.getLong("id"));
                    }, args);
        }
        return result;
    }

    private static <T> Optional<T> single(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static final RowMapper<LineageTableRow> TABLE_MAPPER = (rs, i) -> new LineageTableRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("project_id"),
            rs.getString("catalog_name"), rs.getString("schema_name"), rs.getString("table_name"),
            rs.getString("full_name"), rs.getString("db_type"), rs.getInt("is_temp") == 1,
            rs.getString("table_type"), rs.getString("comment"), rs.getString("remark"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private static final RowMapper<LineageColumnRow> COLUMN_MAPPER = (rs, i) -> new LineageColumnRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("project_id"), rs.getLong("table_id"),
            rs.getString("column_name"), rs.getString("full_name"),
            rs.getInt("ordinal"), rs.getInt("is_partition") == 1,
            rs.getString("data_type"), rs.getString("comment"), rs.getString("remark"),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    static java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /** 字段全名形如 schema.table.column，取到表为止。 */
    // ------------------------------------------------------------------
    // 概览统计
    // ------------------------------------------------------------------

    @Override
    public CatalogStats stats(LineageContext ctx, int recentLimit, int listLimit) {
        long t = ctx.tenantId();
        long p = ctx.projectId();

        int tables = count("select count(*) from lineage_table where tenant_id = ? and project_id = ?", t, p);
        int columns = count("select count(*) from lineage_column where tenant_id = ? and project_id = ?", t, p);
        int versions = count("select count(*) from lineage_version where tenant_id = ? and project_id = ?", t, p);
        int metaTables = count("select count(*) from meta_table where tenant_id = ? and project_id = ?", t, p);

        // 只数当前版本的边。历史版本的边还躺在库里，但那不是用户在图上看到的东西，
        // 一起数进来的话首页的「边数」会比任何一张图上能数出来的都大
        int edges = count(
                "select count(*) from lineage_edge e "
                        + "join lineage_version v on v.id = e.version_id "
                        + "where e.tenant_id = ? and e.project_id = ? and v.is_current = 1",
                t, p);

        return new CatalogStats(tables, columns, edges, versions, metaTables,
                recentParses(ctx, recentLimit),
                metaOnlyTables(ctx, listLimit),
                isolatedTables(ctx, listLimit));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** 最近若干次解析。一次解析可能产生多个版本（多张目标表），这里按版本逐条列。 */
    private List<CatalogStats.RecentParse> recentParses(LineageContext ctx, int limit) {
        return jdbc.query(
                "select v.id, v.target_table_id, tb.full_name, v.version_no, v.db_type, v.is_current, "
                        + "v.stat_tables, v.stat_columns, v.stat_edges, v.created_at "
                        + "from lineage_version v "
                        + "join lineage_table tb on tb.id = v.target_table_id "
                        + "where v.tenant_id = ? and v.project_id = ? "
                        + "order by v.created_at desc, v.id desc "
                        + "limit ?",
                (rs, i) -> new CatalogStats.RecentParse(
                        rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4),
                        rs.getString(5), rs.getBoolean(6),
                        rs.getInt(7), rs.getInt(8), rs.getInt(9),
                        toLocalDateTime(rs.getTimestamp(10))),
                ctx.tenantId(), ctx.projectId(), limit);
    }

    /**
     * 元数据里有结构、血缘里从没出现过的表。
     *
     * <p>关联时对血缘侧的全名加 {@code lower()}：血缘侧保留 SQL 里的原始大小写，
     * 元数据侧恒为小写，不统一的话大小写不同的同一张表会被当成两张。
     */
    private List<CatalogStats.TableBrief> metaOnlyTables(LineageContext ctx, int limit) {
        return jdbc.query(
                "select m.full_name, m.comment from meta_table m "
                        + "where m.tenant_id = ? and m.project_id = ? "
                        + "and not exists (select 1 from lineage_table lt "
                        + "  where lt.tenant_id = m.tenant_id and lt.project_id = m.project_id "
                        + "    and lower(lt.full_name) = m.full_name) "
                        + "order by m.full_name limit ?",
                (rs, i) -> new CatalogStats.TableBrief(null, rs.getString(1), rs.getString(2)),
                ctx.tenantId(), ctx.projectId(), limit);
    }

    /**
     * 血缘里有、但当前版本下既无上游也无下游的孤立表。
     *
     * <p>通常意味着解析漏了：一张表被登记进目录，却没有任何一条边连上它。
     * 临时表不算 —— 它们本来就会被穿透掉，列出来只是噪音。
     */
    private List<CatalogStats.TableBrief> isolatedTables(LineageContext ctx, int limit) {
        // 中文名直接取血缘侧自己的列。这里原先 left join meta_table 按 full_name 关联，
        // 而那个关联是失效的：血缘侧按默认目录补全（default.ods.orders），
        // 元数据挂在源端 catalog 下（hive_prod.ods.orders），首段不同永远匹配不上，
        // 结果这份清单的中文名恒为空。1.0.5 起描述已经快照在 lineage_table 上
        return jdbc.query(
                "select lt.id, lt.full_name, lt.comment from lineage_table lt "
                        + "where lt.tenant_id = ? and lt.project_id = ? and lt.is_temp = 0 "
                        // 上下游各写一个 exists，而不是 `on ... or ...` 合成一个。
                        // OR 连在 join 条件上，两侧的索引（target_col_id / source_col_id）
                        // 谁都用不上，lineage_edge 一大就退化成每张表扫一遍全表。
                        // 拆开之后两个 exists 各走各的索引，且任一命中就能短路
                        + "and not exists ("
                        + "  select 1 from lineage_column c "
                        + "  join lineage_edge e on e.target_col_id = c.id "
                        + "  join lineage_version v on v.id = e.version_id and v.is_current = 1 "
                        + "  where c.tenant_id = lt.tenant_id and c.project_id = lt.project_id "
                        + "    and c.table_id = lt.id) "
                        + "and not exists ("
                        + "  select 1 from lineage_column c "
                        + "  join lineage_edge e on e.source_col_id = c.id "
                        + "  join lineage_version v on v.id = e.version_id and v.is_current = 1 "
                        + "  where c.tenant_id = lt.tenant_id and c.project_id = lt.project_id "
                        + "    and c.table_id = lt.id) "
                        + "order by lt.full_name limit ?",
                (rs, i) -> new CatalogStats.TableBrief(rs.getLong(1), rs.getString(2), rs.getString(3)),
                ctx.tenantId(), ctx.projectId(), limit);
    }

    static String tableOf(String columnFullName) {
        int i = columnFullName.lastIndexOf('.');
        return i < 0 ? columnFullName : columnFullName.substring(0, i);
    }

    static String columnOf(String columnFullName) {
        int i = columnFullName.lastIndexOf('.');
        return i < 0 ? columnFullName : columnFullName.substring(i + 1);
    }

    /**
     * 表全名里的数据目录段：{@code catalog.schema.table} 取第一段。
     *
     * <p>这里<b>不再兜底</b>。两段名（{@code ods.orders}）说明调用方没有先过
     * {@link com.dwai.lineage.service.metadata.TableNameNormalizer} 补目录，
     * 那是个 bug，应当当场炸出来。
     *
     * <p>此前的写法是返回 null，代价是：{@code lineage_table.catalog_name} 一路写空，
     * 与元数据侧恒为三段的 {@code full_name} 关联不上（页面上取不到中文名、表类型），
     * 而且同名不同目录的表在目录筛选框里一个都筛不出来。
     * 这类错误静默发生、事后极难定位，所以宁可失败得响亮些。
     * 库里 {@code catalog_name} 自 1.0.4 起也是 NOT NULL，两道防线一致。
     */
    static String catalogOf(String tableFullName) {
        String[] parts = tableFullName.split("\\.");
        if (parts.length < 3) {
            throw new IllegalStateException(
                    "表全名缺少数据目录段，无法入库: " + tableFullName
                            + "。血缘解析结果应当先经 TableNameNormalizer 补全为 catalog.schema.table");
        }
        return parts[0];
    }

    /**
     * 库名：取<b>倒数第二段</b>。
     *
     * <p>原先取的是最后一个点之前的全部，三段全名 {@code cat.ods.orders} 会得到
     * {@code cat.ods} —— 库筛选框里于是冒出一堆 {@code cat.ods} 这样的假库名。
     */
    static String schemaOf(String tableFullName) {
        String[] parts = tableFullName.split("\\.");
        return parts.length < 2 ? "default" : parts[parts.length - 2];
    }

    static String simpleNameOf(String tableFullName) {
        int i = tableFullName.lastIndexOf('.');
        return i < 0 ? tableFullName : tableFullName.substring(i + 1);
    }
}
