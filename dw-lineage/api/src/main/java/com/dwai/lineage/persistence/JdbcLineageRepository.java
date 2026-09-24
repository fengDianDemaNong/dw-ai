package com.dwai.lineage.persistence;

import com.dwai.lineage.dto.LineageGraph;
import com.dwai.lineage.service.metadata.provider.TableDescriptor;
import com.dwai.lineage.tenant.LineageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.TreeMap;

/**
 * 基于 JdbcTemplate 的血缘版本与边的持久化。
 *
 * <p>选择原生 SQL 而非 JPA 的原因：图遍历必须用递归 CTE，JPA 表达不了；
 * 而写入是大批量的，批处理也比逐个实体 persist 快得多。
 *
 * <p>递归 CTE 的语法在 H2 / MySQL 8 / PostgreSQL 上一致，故三种后端共用同一份 SQL。
 */
@Repository
public class JdbcLineageRepository implements LineageRepository {

    private static final Logger logger = LoggerFactory.getLogger(JdbcLineageRepository.class);

    /** 批量写入的分批大小。 */
    private static final int BATCH_SIZE = 500;

    /** 遍历深度硬上限，防止异常数据导致递归失控。 */
    public static final int MAX_DEPTH = 50;

    private final JdbcTemplate jdbc;
    private final LineageCatalogRepository catalog;

    public JdbcLineageRepository(JdbcTemplate jdbc, LineageCatalogRepository catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    // ==================================================================
    // 写入
    // ==================================================================

    @Override
    @Transactional
    public List<LineageVersionRow> saveVersions(LineageContext ctx, String dbType,
                                                String sqlText, LineageGraph graph) {
        return saveVersions(ctx, dbType, sqlText, graph, name -> null);
    }

    @Override
    @Transactional
    public List<LineageVersionRow> saveVersions(LineageContext ctx, String dbType,
                                                String sqlText, LineageGraph graph,
                                                Function<String, TableDescriptor> descriptors) {
        Map<String, Set<String>> edges = collectEdges(graph);
        if (edges.isEmpty()) {
            logger.info("解析结果里没有任何血缘边，不产生版本, {}", ctx);
            return List.of();
        }

        Set<String> fields = collectFields(graph);
        Set<String> cyclicFields = collectCyclicFields(graph);

        // 先把涉及的表与字段 upsert 进目录（累积，不影响别的表）
        Set<String> tables = new LinkedHashSet<>();
        fields.forEach(f -> tables.add(tableOf(f)));
        Map<String, Long> tableIds = catalog.upsertTables(ctx, dbType, tables, descriptors);
        Map<String, Long> columnIds = catalog.upsertColumns(ctx, tableIds, fields, descriptors);

        // 按目标表把边分组：一段 SQL 里的多条 INSERT 会产出多张目标表，各建各的版本
        Map<String, Map<String, Set<String>>> edgesByTargetTable = new TreeMap<>();
        for (Map.Entry<String, Set<String>> entry : edges.entrySet()) {
            edgesByTargetTable
                    .computeIfAbsent(tableOf(entry.getKey()), k -> new LinkedHashMap<>())
                    .put(entry.getKey(), entry.getValue());
        }

        String sqlHash = sha256(sqlText);
        List<LineageVersionRow> created = new ArrayList<>();

        for (Map.Entry<String, Map<String, Set<String>>> entry : edgesByTargetTable.entrySet()) {
            String targetTable = entry.getKey();
            Map<String, Set<String>> tableEdges = entry.getValue();
            long targetTableId = tableIds.get(targetTable);

            Set<String> involvedTables = new LinkedHashSet<>();
            Set<String> involvedColumns = new LinkedHashSet<>();
            int edgeCount = 0;
            for (Map.Entry<String, Set<String>> e : tableEdges.entrySet()) {
                involvedColumns.add(e.getKey());
                involvedTables.add(tableOf(e.getKey()));
                for (String source : e.getValue()) {
                    involvedColumns.add(source);
                    involvedTables.add(tableOf(source));
                    edgeCount++;
                }
            }

            int versionNo = nextVersionNo(ctx, targetTableId);
            long versionId = insertVersion(ctx, targetTableId, versionNo, dbType, sqlText, sqlHash,
                    involvedTables.size(), involvedColumns.size(), edgeCount);
            insertEdges(ctx, versionId, tableEdges, columnIds, cyclicFields);

            // 新版本即该目标表的当前版本（后面的操作覆盖前面的）
            markCurrent(ctx, versionId);
            created.add(findVersion(ctx, versionId).orElseThrow());

            logger.info("血缘已保存, {}, 目标表={}, versionNo={}, 表={}, 列={}, 边={}",
                    ctx, targetTable, versionNo, involvedTables.size(), involvedColumns.size(), edgeCount);
        }

        return created;
    }

    /** 版本号在同一目标表内递增。 */
    private int nextVersionNo(LineageContext ctx, long targetTableId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(version_no), 0) from lineage_version "
                        + "where tenant_id = ? and project_id = ? and target_table_id = ?",
                Integer.class, ctx.tenantId(), ctx.projectId(), targetTableId);
        return (max == null ? 0 : max) + 1;
    }

    private long insertVersion(LineageContext ctx, long targetTableId, int versionNo, String dbType,
                               String sqlText, String sqlHash, int tables, int columns, int edges) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into lineage_version(tenant_id, project_id, target_table_id, version_no, "
                            + "db_type, sql_hash, sql_text, is_current, stat_tables, stat_columns, stat_edges) "
                            + "values(?,?,?,?,?,?,?,0,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ctx.tenantId());
            ps.setLong(2, ctx.projectId());
            ps.setLong(3, targetTableId);
            ps.setInt(4, versionNo);
            ps.setString(5, dbType);
            ps.setString(6, sqlHash);
            ps.setString(7, sqlText);
            ps.setInt(8, tables);
            ps.setInt(9, columns);
            ps.setInt(10, edges);
            return ps;
        }, keys);
        return requireKey(keys);
    }

    private void insertEdges(LineageContext ctx, long versionId, Map<String, Set<String>> edges,
                             Map<String, Long> columnIds, Set<String> cyclicFields) {
        List<String[]> pairs = new ArrayList<>();
        edges.forEach((target, sources) ->
                sources.forEach(source -> pairs.add(new String[]{target, source})));

        for (int start = 0; start < pairs.size(); start += BATCH_SIZE) {
            List<String[]> batch = pairs.subList(start, Math.min(start + BATCH_SIZE, pairs.size()));
            jdbc.batchUpdate(
                    "insert into lineage_edge(tenant_id, project_id, version_id, "
                            + "target_col_id, source_col_id, is_cyclic) values(?,?,?,?,?,?)",
                    batch, batch.size(), (ps, pair) -> {
                        ps.setLong(1, ctx.tenantId());
                        ps.setLong(2, ctx.projectId());
                        ps.setLong(3, versionId);
                        ps.setLong(4, columnIds.get(pair[0]));
                        ps.setLong(5, columnIds.get(pair[1]));
                        ps.setInt(6, cyclicFields.contains(pair[0]) && cyclicFields.contains(pair[1]) ? 1 : 0);
                    });
        }
    }

    // ==================================================================
    // 版本管理
    // ==================================================================

    @Override
    public List<LineageVersionRow> listVersions(LineageContext ctx, long targetTableId) {
        return jdbc.query("select * from lineage_version "
                        + "where tenant_id = ? and project_id = ? and target_table_id = ? "
                        + "order by version_no desc",
                VERSION_MAPPER, ctx.tenantId(), ctx.projectId(), targetTableId);
    }

    @Override
    public Optional<LineageVersionRow> currentVersion(LineageContext ctx, long targetTableId) {
        // 优先取显式标记的当前版本，没有则退回最新版本
        List<LineageVersionRow> rows = jdbc.query(
                "select * from lineage_version where tenant_id = ? and project_id = ? "
                        + "and target_table_id = ? order by is_current desc, version_no desc",
                VERSION_MAPPER, ctx.tenantId(), ctx.projectId(), targetTableId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public Optional<LineageVersionRow> findVersion(LineageContext ctx, long versionId) {
        List<LineageVersionRow> rows = jdbc.query(
                "select * from lineage_version where tenant_id = ? and project_id = ? and id = ?",
                VERSION_MAPPER, ctx.tenantId(), ctx.projectId(), versionId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public List<LineageVersionRow> findBySqlHash(LineageContext ctx, String sqlHash) {
        return jdbc.query(
                "select * from lineage_version where tenant_id = ? and project_id = ? and sql_hash = ? "
                        + "order by version_no desc",
                VERSION_MAPPER, ctx.tenantId(), ctx.projectId(), sqlHash);
    }

    /**
     * 设为当前版本。
     *
     * <p>互斥范围是<b>同一目标表</b>，不是整个项目 —— 否则把 A 表设为当前会把 B 表的当前版本清掉。
     */
    @Override
    @Transactional
    public void markCurrent(LineageContext ctx, long versionId) {
        Long targetTableId = jdbc.query(
                "select target_table_id from lineage_version "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                rs -> rs.next() ? rs.getLong(1) : null,
                ctx.tenantId(), ctx.projectId(), versionId);
        if (targetTableId == null) {
            throw new IllegalArgumentException("血缘版本不存在: " + versionId);
        }

        jdbc.update("update lineage_version set is_current = 0 "
                        + "where tenant_id = ? and project_id = ? and target_table_id = ?",
                ctx.tenantId(), ctx.projectId(), targetTableId);
        jdbc.update("update lineage_version set is_current = 1 "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), versionId);
    }

    @Override
    @Transactional
    public boolean deleteVersion(LineageContext ctx, long versionId) {
        Optional<LineageVersionRow> version = findVersion(ctx, versionId);
        if (version.isEmpty()) {
            return false;
        }

        jdbc.update("delete from lineage_edge where tenant_id = ? and project_id = ? and version_id = ?",
                ctx.tenantId(), ctx.projectId(), versionId);
        jdbc.update("delete from lineage_version where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), versionId);

        // 目录里的表与字段不删：它们可能还被同一张表的其它版本、或别的表的血缘引用着。

        // 删掉的是当前版本时，把该表剩余的最新版本设为当前，避免出现「没有当前版本」的状态
        if (version.get().isCurrent()) {
            listVersions(ctx, version.get().targetTableId()).stream().findFirst()
                    .ifPresent(latest -> markCurrent(ctx, latest.id()));
        }
        return true;
    }

    // ==================================================================
    // 图遍历
    // ==================================================================

    @Override
    public List<LineageEdgeRow> upstream(LineageContext ctx, String columnFullName,
                                         int depth, Long rootVersionId) {
        return traverse(ctx, columnFullName, depth, rootVersionId, true);
    }

    @Override
    public List<LineageEdgeRow> downstream(LineageContext ctx, String columnFullName,
                                           int depth, Long rootVersionId) {
        return traverse(ctx, columnFullName, depth, rootVersionId, false);
    }

    /**
     * 递归 CTE 遍历。上游与下游只是边的方向相反，共用一份 SQL 模板。
     *
     * <p>沿<b>各表当前版本</b>的边走，因此每次取边都要 join {@code lineage_version}。
     * 指定 {@code rootVersionId} 时，起点表改用该版本，其余表仍走当前版本 ——
     * 上游表的历史版本与本表的历史版本没有对应关系，全都按历史拼会得到一张从未真实存在过的图。
     *
     * <p>三种后端语法一致，故不做方言分支。{@code depth} 有硬上限兜底，
     * 即使数据里存在未被标记的环也不会失控。
     */
    private List<LineageEdgeRow> traverse(LineageContext ctx, String columnFullName,
                                          int depth, Long rootVersionId, boolean upstream) {
        int limit = Math.min(Math.max(depth, 1), MAX_DEPTH);
        String from = upstream ? "target_col_id" : "source_col_id";
        String to = upstream ? "source_col_id" : "target_col_id";

        // 版本过滤：默认只认当前版本；指定了起点版本时，该目标表用指定版本、其余表仍用当前版本
        long rootTableId = rootVersionId == null ? -1L
                : findVersion(ctx, rootVersionId).map(LineageVersionRow::targetTableId).orElse(-1L);
        String versionFilter = rootVersionId == null
                ? "v.is_current = 1"
                : "((v.target_table_id = ? AND v.id = ?) OR (v.target_table_id <> ? AND v.is_current = 1))";

        String sql = """
                WITH RECURSIVE walk(col_id, depth) AS (
                    SELECT c.id, 0
                      FROM lineage_column c
                     WHERE c.tenant_id = ? AND c.project_id = ?
                       AND c.full_name = ?
                    UNION ALL
                    SELECT e.%s, w.depth + 1
                      FROM lineage_edge e
                      JOIN lineage_version v ON v.id = e.version_id AND %s
                      JOIN walk w ON e.%s = w.col_id
                     WHERE e.tenant_id = ? AND e.project_id = ?
                       AND w.depth < ?
                )
                SELECT tc.full_name AS target_field,
                       sc.full_name AS source_field,
                       w.depth      AS depth,
                       e.transform  AS transform,
                       e.is_cyclic  AS is_cyclic
                  FROM walk w
                  JOIN lineage_edge e ON e.%s = w.col_id
                  JOIN lineage_version v ON v.id = e.version_id AND %s
                  JOIN lineage_column tc ON tc.id = e.target_col_id
                  JOIN lineage_column sc ON sc.id = e.source_col_id
                 WHERE e.tenant_id = ? AND e.project_id = ?
                   AND w.depth < ?
                """.formatted(to, versionFilter, from, from, versionFilter);

        List<Object> args = new ArrayList<>();
        args.add(ctx.tenantId());
        args.add(ctx.projectId());
        args.add(columnFullName);
        addVersionFilterArgs(args, rootVersionId, rootTableId);
        args.add(ctx.tenantId());
        args.add(ctx.projectId());
        args.add(limit);
        addVersionFilterArgs(args, rootVersionId, rootTableId);
        args.add(ctx.tenantId());
        args.add(ctx.projectId());
        args.add(limit);

        // 最后一次 join 也要受 depth 约束：否则处于最深层的节点会再展开一跳，
        // 实际返回的层数比请求的多一层。
        return jdbc.query(sql, EDGE_MAPPER, args.toArray());
    }

    private static void addVersionFilterArgs(List<Object> args, Long rootVersionId, long rootTableId) {
        if (rootVersionId != null) {
            args.add(rootTableId);
            args.add(rootVersionId);
            args.add(rootTableId);
        }
    }


    /**
     * 表级的一跳边。沿 current 版本，与递归遍历保持同一口径。
     *
     * @param targetSide true 取「该表字段作为目标」的边（即上游），false 取下游
     */
    private List<LineageEdgeRow> directEdgesOfTable(LineageContext ctx, long tableId,
                                                    boolean targetSide) {
        String side = targetSide ? "tc.table_id" : "sc.table_id";
        String sql = """
                SELECT tc.full_name AS target_field,
                       sc.full_name AS source_field,
                       1            AS depth,
                       e.transform  AS transform,
                       e.is_cyclic  AS is_cyclic
                  FROM lineage_edge e
                  JOIN lineage_version v ON v.id = e.version_id AND v.is_current = 1
                  JOIN lineage_column tc ON tc.id = e.target_col_id
                  JOIN lineage_column sc ON sc.id = e.source_col_id
                 WHERE e.tenant_id = ? AND e.project_id = ? AND %s = ?
                """.formatted(side);
        return jdbc.query(sql, EDGE_MAPPER, ctx.tenantId(), ctx.projectId(), tableId);
    }

    @Override
    public List<LineageEdgeRow> directUpstreamOfTable(LineageContext ctx, long tableId) {
        return directEdgesOfTable(ctx, tableId, true);
    }

    @Override
    public List<LineageEdgeRow> directDownstreamOfTable(LineageContext ctx, long tableId) {
        return directEdgesOfTable(ctx, tableId, false);
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private static final RowMapper<LineageVersionRow> VERSION_MAPPER = (rs, i) -> new LineageVersionRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("project_id"),
            rs.getLong("target_table_id"), rs.getInt("version_no"), rs.getString("name"),
            rs.getString("db_type"), rs.getString("sql_hash"), rs.getInt("is_current") == 1,
            rs.getInt("stat_tables"), rs.getInt("stat_columns"), rs.getInt("stat_edges"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime());

    private static final RowMapper<LineageEdgeRow> EDGE_MAPPER = (rs, i) -> new LineageEdgeRow(
            rs.getString("target_field"), rs.getString("source_field"),
            rs.getInt("depth"), rs.getString("transform"), rs.getInt("is_cyclic") == 1);

    /**
     * 取自增主键。
     *
     * <p>不能直接用 {@code keys.getKey()}：部分数据库（实测 H2）会把
     * {@code created_at} 等带默认值的列一并作为 generated keys 返回，
     * 此时 {@code getKey()} 会抛「multiple keys」异常。因此优先按列名取 id。
     *
     * <p>包内可见，供本包其它仓储复用，避免每个仓储各抄一份这段坑。
     */
    static long requireKey(KeyHolder keys) {
        Map<String, Object> map = keys.getKeys();
        if (map != null) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                if ("id".equalsIgnoreCase(e.getKey()) && e.getValue() instanceof Number n) {
                    return n.longValue();
                }
            }
            if (map.size() == 1 && map.values().iterator().next() instanceof Number n) {
                return n.longValue();
            }
        }
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("未能获取自增主键");
        }
        return key.longValue();
    }

    private static Set<String> collectFields(LineageGraph graph) {
        Set<String> fields = new LinkedHashSet<>();
        if (graph.data() == null || graph.data().withProcessData() == null) {
            return fields;
        }
        for (LineageGraph.Item item : graph.data().withProcessData().data()) {
            fields.add(item.targetField().fieldName());
            item.refFields().forEach(f -> fields.add(f.fieldName()));
        }
        return fields;
    }

    private static Map<String, Set<String>> collectEdges(LineageGraph graph) {
        Map<String, Set<String>> edges = new LinkedHashMap<>();
        if (graph.data() == null || graph.data().withProcessData() == null) {
            return edges;
        }
        for (LineageGraph.Item item : graph.data().withProcessData().data()) {
            String target = item.targetField().fieldName();
            for (LineageGraph.Field ref : item.refFields()) {
                edges.computeIfAbsent(target, k -> new LinkedHashSet<>()).add(ref.fieldName());
            }
        }
        return edges;
    }

    private static Set<String> collectCyclicFields(LineageGraph graph) {
        Set<String> cyclic = new LinkedHashSet<>();
        if (graph.data() == null || graph.data().withProcessData() == null) {
            return cyclic;
        }
        for (LineageGraph.Item item : graph.data().withProcessData().data()) {
            for (LineageGraph.Field ref : item.refFields()) {
                if (Boolean.TRUE.equals(ref.cyclic())) {
                    cyclic.add(item.targetField().fieldName());
                    cyclic.add(ref.fieldName());
                }
            }
        }
        return cyclic;
    }

    /** 字段名形如 schema.table.column，取到表为止。 */
    private static String tableOf(String field) {
        int i = field.lastIndexOf('.');
        return i < 0 ? field : field.substring(0, i);
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(
                    (text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("计算 SQL 哈希失败", e);
        }
    }
}
