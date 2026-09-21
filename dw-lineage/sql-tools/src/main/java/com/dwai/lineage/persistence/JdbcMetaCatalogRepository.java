package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.MetaSource;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 元数据目录的 JdbcTemplate 实现。
 *
 * <p>三方言共用一份 SQL：不用各家的 upsert 语法（{@code ON DUPLICATE KEY} /
 * {@code ON CONFLICT} / {@code MERGE} 互不兼容），改成「先查后写」。
 */
@Repository
public class JdbcMetaCatalogRepository implements MetaCatalogRepository {

    private static final int IN_CHUNK = 500;

    private static final String TABLE_COLUMNS =
            "id, tenant_id, project_id, catalog_name, schema_name, table_name, full_name, "
                    + "table_type, comment, remark, db_type, source, source_id, synced_at, "
                    + "created_at, updated_at";

    private static final String COLUMN_COLUMNS =
            "id, tenant_id, project_id, table_id, column_name, full_name, data_type, comment, "
                    + "remark, ordinal, is_partition, nullable, is_primary, source, "
                    + "created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcMetaCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================================================================
    // upsert
    // ==================================================================

    @Override
    @Transactional
    public UpsertResult upsert(LineageContext ctx, MetaTableRow table, List<MetaColumnRow> columns,
                               boolean overwriteManual) {
        Optional<MetaTableRow> existing = findByFullName(ctx, table.fullName());

        if (existing.isPresent()) {
            MetaTableRow old = existing.get();
            // 人工维护过的表：整张跳过，连字段都不动。否则用户刚补好的中文名会被下一次同步冲掉
            if (old.source().isManual() && !overwriteManual) {
                return UpsertResult.skipped(old.id());
            }
            updateTableRow(ctx, old.id(), table);
            mergeColumns(ctx, old.id(), table.fullName(), columns, overwriteManual);
            return new UpsertResult(old.id(), false, false);
        }

        long tableId = insertTableRow(ctx, table);
        mergeColumns(ctx, tableId, table.fullName(), columns, overwriteManual);
        return new UpsertResult(tableId, true, false);
    }

    private long insertTableRow(LineageContext ctx, MetaTableRow t) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into meta_table(tenant_id, project_id, catalog_name, schema_name, "
                            + "table_name, full_name, table_type, comment, remark, db_type, "
                            + "source, source_id, synced_at) values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ctx.tenantId());
            ps.setLong(2, ctx.projectId());
            ps.setString(3, t.catalogName());
            ps.setString(4, t.schemaName());
            ps.setString(5, t.tableName());
            ps.setString(6, t.fullName());
            ps.setString(7, t.tableType());
            ps.setString(8, t.comment());
            ps.setString(9, t.remark());
            ps.setString(10, t.dbType());
            ps.setString(11, t.source().name());
            if (t.sourceId() == null) {
                ps.setNull(12, java.sql.Types.BIGINT);
            } else {
                ps.setLong(12, t.sourceId());
            }
            ps.setTimestamp(13, syncedAt(t));
            return ps;
        }, keys);
        return JdbcLineageRepository.requireKey(keys);
    }

    private void updateTableRow(LineageContext ctx, long id, MetaTableRow t) {
        // 中文名与备注：同步来的值为空时不要把已有的清掉 —— 外部服务不一定填了注释，
        // 而这两个字段往往是人补上的，用 coalesce 保留旧值
        jdbc.update("update meta_table set table_type = ?, "
                        + "comment = coalesce(?, comment), remark = coalesce(?, remark), "
                        + "db_type = ?, source = ?, source_id = ?, synced_at = ?, updated_at = ? "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                t.tableType(), t.comment(), t.remark(), t.dbType(), t.source().name(), t.sourceId(),
                syncedAt(t), now(), ctx.tenantId(), ctx.projectId(), id);
    }

    private static Timestamp syncedAt(MetaTableRow t) {
        // DDL 导入与手工录入不算「同步」，不打时间戳
        boolean synced = t.source() == MetaSource.GRAVITINO || t.source() == MetaSource.DBX;
        return synced ? now() : (t.syncedAt() == null ? null : Timestamp.valueOf(t.syncedAt()));
    }

    /**
     * 字段增量合并。
     *
     * <p>三种情况：新字段插入；已存在的更新结构（手工改过的保留其中文名与备注）；
     * 上游已不存在且非手工添加的删除 —— 手工加的字段不动，那是人为意图。
     */
    private void mergeColumns(LineageContext ctx, long tableId, String tableFullName,
                              List<MetaColumnRow> incoming, boolean overwriteManual) {
        if (incoming == null) {
            return;
        }
        Map<String, MetaColumnRow> existing = new LinkedHashMap<>();
        listColumns(ctx, tableId).forEach(c -> existing.put(c.columnName().toLowerCase(Locale.ROOT), c));

        Set<String> seen = new LinkedHashSet<>();
        for (MetaColumnRow c : incoming) {
            String key = c.columnName().toLowerCase(Locale.ROOT);
            seen.add(key);
            String fullName = com.dwai.lineage.service.metadata.MetaNames.columnFullName(
                    tableFullName, c.columnName());
            MetaColumnRow old = existing.get(key);

            if (old == null) {
                insertColumnRow(ctx, tableId, fullName, c);
            } else if (old.source().isManual() && !overwriteManual) {
                // 结构（类型/顺序/分区）仍以上游为准，但中文名与备注是人写的，保住
                jdbc.update("update meta_column set data_type = ?, ordinal = ?, is_partition = ?, "
                                + "nullable = ?, is_primary = ?, updated_at = ? "
                                + "where tenant_id = ? and project_id = ? and id = ?",
                        c.dataType(), c.ordinal(), c.partition() ? 1 : 0, c.nullable() ? 1 : 0,
                        c.primary() ? 1 : 0, now(), ctx.tenantId(), ctx.projectId(), old.id());
            } else {
                jdbc.update("update meta_column set data_type = ?, "
                                + "comment = coalesce(?, comment), ordinal = ?, is_partition = ?, "
                                + "nullable = ?, is_primary = ?, source = ?, updated_at = ? "
                                + "where tenant_id = ? and project_id = ? and id = ?",
                        c.dataType(), c.comment(), c.ordinal(), c.partition() ? 1 : 0,
                        c.nullable() ? 1 : 0, c.primary() ? 1 : 0, c.source().name(), now(),
                        ctx.tenantId(), ctx.projectId(), old.id());
            }
        }

        existing.forEach((key, old) -> {
            if (!seen.contains(key) && !old.source().isManual()) {
                jdbc.update("delete from meta_column "
                                + "where tenant_id = ? and project_id = ? and id = ?",
                        ctx.tenantId(), ctx.projectId(), old.id());
            }
        });
    }

    private void insertColumnRow(LineageContext ctx, long tableId, String fullName, MetaColumnRow c) {
        jdbc.update("insert into meta_column(tenant_id, project_id, table_id, column_name, full_name, "
                        + "data_type, comment, remark, ordinal, is_partition, nullable, is_primary, source) "
                        + "values(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                ctx.tenantId(), ctx.projectId(), tableId, c.columnName(), fullName,
                c.dataType(), c.comment(), c.remark(), c.ordinal(),
                c.partition() ? 1 : 0, c.nullable() ? 1 : 0, c.primary() ? 1 : 0, c.source().name());
    }

    // ==================================================================
    // 查询
    // ==================================================================

    @Override
    public List<String> listSchemas(LineageContext ctx) {
        return jdbc.queryForList("select distinct schema_name from meta_table "
                        + "where tenant_id = ? and project_id = ? order by schema_name",
                String.class, ctx.tenantId(), ctx.projectId());
    }

    @Override
    public List<String> listCatalogs(LineageContext ctx) {
        // 排除 NULL：没有数据目录的表不该在下拉框里凑出一个空选项，
        // 「只看无目录的表」由前端用单独的选项表达（传空串）
        return jdbc.queryForList("select distinct catalog_name from meta_table "
                        + "where tenant_id = ? and project_id = ? and catalog_name is not null "
                        + "order by catalog_name",
                String.class, ctx.tenantId(), ctx.projectId());
    }

    @Override
    public List<MetaTableRow> list(LineageContext ctx, String catalog, String schema, String keyword,
                                   int offset, int limit) {
        List<Object> args = new ArrayList<>(List.of(ctx.tenantId(), ctx.projectId()));
        String sql = "select " + TABLE_COLUMNS + " from meta_table "
                + "where tenant_id = ? and project_id = ?" + filterClause(catalog, schema, keyword, args)
                + " order by schema_name, table_name limit ? offset ?";
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql, TABLE_MAPPER, args.toArray());
    }

    @Override
    public long count(LineageContext ctx, String catalog, String schema, String keyword) {
        List<Object> args = new ArrayList<>(List.of(ctx.tenantId(), ctx.projectId()));
        String sql = "select count(*) from meta_table where tenant_id = ? and project_id = ?"
                + filterClause(catalog, schema, keyword, args);
        Long n = jdbc.queryForObject(sql, Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /**
     * 拼筛选条件并把参数按顺序塞进 args。keyword 大小写不敏感。
     *
     * <p>catalog 传 null 或空串都表示「全部目录」。1.0.4 之前空串另有含义
     * （只看没有数据目录的表），现在 {@code catalog_name} 已是 NOT NULL，
     * 不存在这种表，那一态就没有意义了。
     */
    private static String filterClause(String catalog, String schema, String keyword,
                                       List<Object> args) {
        StringBuilder clause = new StringBuilder();
        if (catalog != null && !catalog.isBlank()) {
            clause.append(" and catalog_name = ?");
            args.add(catalog);
        }
        if (schema != null && !schema.isBlank()) {
            clause.append(" and schema_name = ?");
            args.add(schema);
        }
        if (keyword != null && !keyword.isBlank()) {
            clause.append(" and (lower(table_name) like ? or lower(coalesce(comment, '')) like ?)");
            String like = "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
            args.add(like);
            args.add(like);
        }
        return clause.toString();
    }

    @Override
    public Optional<MetaTableRow> findById(LineageContext ctx, long id) {
        return single(jdbc.query("select " + TABLE_COLUMNS + " from meta_table "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                TABLE_MAPPER, ctx.tenantId(), ctx.projectId(), id));
    }

    @Override
    public Optional<MetaTableRow> findByFullName(LineageContext ctx, String fullName) {
        return single(jdbc.query("select " + TABLE_COLUMNS + " from meta_table "
                        + "where tenant_id = ? and project_id = ? and full_name = ?",
                TABLE_MAPPER, ctx.tenantId(), ctx.projectId(), fullName));
    }

    @Override
    public List<MetaTableRow> findBySchemaAndTable(LineageContext ctx, Collection<String> schemaTables) {
        List<MetaTableRow> result = new ArrayList<>();
        List<String> all = new ArrayList<>(new LinkedHashSet<>(schemaTables));
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<String> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            // 每个 key 两个条件：恰好等于（无目录的表），或以 .key 结尾（某个目录下的同名表）
            StringBuilder where = new StringBuilder();
            List<Object> args = new ArrayList<>(List.of(ctx.tenantId(), ctx.projectId()));
            for (String key : chunk) {
                if (!where.isEmpty()) {
                    where.append(" or ");
                }
                where.append("full_name = ? or full_name like ?");
                args.add(key);
                args.add("%." + key);
            }
            result.addAll(jdbc.query("select " + TABLE_COLUMNS + " from meta_table "
                            + "where tenant_id = ? and project_id = ? and (" + where + ")",
                    TABLE_MAPPER, args.toArray()));
        }
        return result;
    }

    @Override
    public List<MetaTableRow> findByFullNames(LineageContext ctx, Collection<String> fullNames) {
        List<MetaTableRow> result = new ArrayList<>();
        List<String> all = new ArrayList<>(new LinkedHashSet<>(fullNames));
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<String> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            Object[] args = new Object[chunk.size() + 2];
            args[0] = ctx.tenantId();
            args[1] = ctx.projectId();
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 2] = chunk.get(i);
            }
            result.addAll(jdbc.query("select " + TABLE_COLUMNS + " from meta_table "
                            + "where tenant_id = ? and project_id = ? and full_name in (" + placeholders + ")",
                    TABLE_MAPPER, args));
        }
        return result;
    }

    @Override
    public List<MetaColumnRow> listColumns(LineageContext ctx, long tableId) {
        return jdbc.query("select " + COLUMN_COLUMNS + " from meta_column "
                        + "where tenant_id = ? and project_id = ? and table_id = ? "
                        + "order by is_partition, ordinal, column_name",
                COLUMN_MAPPER, ctx.tenantId(), ctx.projectId(), tableId);
    }

    @Override
    public Map<Long, List<MetaColumnRow>> listColumnsByTableIds(LineageContext ctx,
                                                                Collection<Long> tableIds) {
        Map<Long, List<MetaColumnRow>> result = new HashMap<>();
        List<Long> all = new ArrayList<>(new LinkedHashSet<>(tableIds));
        for (int start = 0; start < all.size(); start += IN_CHUNK) {
            List<Long> chunk = all.subList(start, Math.min(start + IN_CHUNK, all.size()));
            String placeholders = String.join(",", Collections.nCopies(chunk.size(), "?"));
            Object[] args = new Object[chunk.size() + 2];
            args[0] = ctx.tenantId();
            args[1] = ctx.projectId();
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 2] = chunk.get(i);
            }
            jdbc.query("select " + COLUMN_COLUMNS + " from meta_column "
                            + "where tenant_id = ? and project_id = ? and table_id in (" + placeholders + ") "
                            + "order by is_partition, ordinal, column_name",
                    rs -> {
                        MetaColumnRow row = COLUMN_MAPPER.mapRow(rs, 0);
                        result.computeIfAbsent(row.tableId(), k -> new ArrayList<>()).add(row);
                    }, args);
        }
        return result;
    }

    @Override
    public Optional<MetaColumnRow> findColumnById(LineageContext ctx, long id) {
        return single(jdbc.query("select " + COLUMN_COLUMNS + " from meta_column "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                COLUMN_MAPPER, ctx.tenantId(), ctx.projectId(), id));
    }

    // ==================================================================
    // 手工修改与删除
    // ==================================================================

    @Override
    public boolean updateTableInfo(LineageContext ctx, long id, String tableType,
                                   String comment, String remark) {
        // 改过就打上 MANUAL：后续同步会绕开它，不再覆盖人写的内容
        return jdbc.update("update meta_table set table_type = ?, comment = ?, remark = ?, "
                        + "source = 'MANUAL', updated_at = ? "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                tableType, comment, remark, now(), ctx.tenantId(), ctx.projectId(), id) > 0;
    }

    @Override
    public boolean updateColumnInfo(LineageContext ctx, long id, String dataType,
                                    String comment, String remark) {
        return jdbc.update("update meta_column set data_type = ?, comment = ?, remark = ?, "
                        + "source = 'MANUAL', updated_at = ? "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                dataType, comment, remark, now(), ctx.tenantId(), ctx.projectId(), id) > 0;
    }

    @Override
    @Transactional
    public boolean deleteTable(LineageContext ctx, long id) {
        jdbc.update("delete from meta_column where tenant_id = ? and project_id = ? and table_id = ?",
                ctx.tenantId(), ctx.projectId(), id);
        return jdbc.update("delete from meta_table where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), id) > 0;
    }

    @Override
    public boolean deleteColumn(LineageContext ctx, long id) {
        return jdbc.update("delete from meta_column where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), id) > 0;
    }

    // ==================================================================

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private static <T> Optional<T> single(List<T> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static final RowMapper<MetaTableRow> TABLE_MAPPER = (rs, i) -> new MetaTableRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("project_id"),
            rs.getString("catalog_name"), rs.getString("schema_name"), rs.getString("table_name"),
            rs.getString("full_name"), rs.getString("table_type"), rs.getString("comment"),
            rs.getString("remark"), rs.getString("db_type"),
            MetaSource.fromString(rs.getString("source")),
            rs.getObject("source_id") == null ? null : rs.getLong("source_id"),
            toLocalDateTime(rs.getTimestamp("synced_at")),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private static final RowMapper<MetaColumnRow> COLUMN_MAPPER = (rs, i) -> new MetaColumnRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("project_id"), rs.getLong("table_id"),
            rs.getString("column_name"), rs.getString("full_name"), rs.getString("data_type"),
            rs.getString("comment"), rs.getString("remark"), rs.getInt("ordinal"),
            rs.getInt("is_partition") == 1, rs.getInt("nullable") == 1, rs.getInt("is_primary") == 1,
            MetaSource.fromString(rs.getString("source")),
            toLocalDateTime(rs.getTimestamp("created_at")),
            toLocalDateTime(rs.getTimestamp("updated_at")));

    private static java.time.LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
