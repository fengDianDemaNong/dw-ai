package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.SyncScope;
import com.dwai.lineage.enums.SyncStatus;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * 导入任务的存取。
 *
 * <p>与本包其它仓储同一套写法：原生 SQL、显式 {@code updated_at}、每条 SQL 带租户过滤。
 *
 * <p>{@code tables} 与 {@code failures} 存成换行分隔的文本而不是 JSON ——
 * 它们只在本类里读写、内容是纯粹的字符串列表，引入一个 JSON 依赖只为了存一个数组不划算。
 * 表名里不会有换行符。
 */
@Repository
public class JdbcSyncJobRepository implements SyncJobRepository {

    private static final String COLUMNS =
            "id, tenant_id, project_id, source_id, scope, source_catalog, source_database, "
                    + "source_schema, connection_id, target_catalog, tables, overwrite_manual, "
                    + "status, total, done, created_cnt, updated_cnt, skipped_cnt, failed_cnt, "
                    + "message, failures, started_at, finished_at, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcSyncJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long create(LineageContext ctx, SyncJobRow job) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into sync_job(tenant_id, project_id, source_id, scope, source_catalog, "
                            + "source_database, source_schema, connection_id, target_catalog, tables, "
                            + "overwrite_manual, status) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ctx.tenantId());
            ps.setLong(2, ctx.projectId());
            ps.setLong(3, job.sourceId());
            ps.setString(4, job.scope().name());
            ps.setString(5, job.sourceCatalog());
            ps.setString(6, job.sourceDatabase());
            ps.setString(7, job.sourceSchema());
            ps.setString(8, job.connectionId());
            ps.setString(9, job.targetCatalog());
            ps.setString(10, join(job.tables()));
            ps.setInt(11, job.overwriteManual() ? 1 : 0);
            ps.setString(12, SyncStatus.PENDING.name());
            return ps;
        }, keys);
        return JdbcLineageRepository.requireKey(keys);
    }

    @Override
    public Optional<SyncJobRow> findById(LineageContext ctx, long id) {
        return jdbc.query("select " + COLUMNS + " from sync_job "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                MAPPER, ctx.tenantId(), ctx.projectId(), id).stream().findFirst();
    }

    @Override
    public List<SyncJobRow> listRecent(LineageContext ctx, int limit) {
        return jdbc.query("select " + COLUMNS + " from sync_job "
                        + "where tenant_id = ? and project_id = ? order by id desc limit ?",
                MAPPER, ctx.tenantId(), ctx.projectId(), limit);
    }

    @Override
    public void markRunning(LineageContext ctx, long id) {
        jdbc.update("update sync_job set status = ?, started_at = ?, updated_at = ? "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                SyncStatus.RUNNING.name(), now(), now(), ctx.tenantId(), ctx.projectId(), id);
    }

    @Override
    public void updateTotal(LineageContext ctx, long id, int total) {
        jdbc.update("update sync_job set total = ?, updated_at = ? "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                total, now(), ctx.tenantId(), ctx.projectId(), id);
    }

    @Override
    public void updateProgress(LineageContext ctx, long id, int done, int created, int updated,
                               int skipped, int failed) {
        jdbc.update("update sync_job set done = ?, created_cnt = ?, updated_cnt = ?, "
                        + "skipped_cnt = ?, failed_cnt = ?, updated_at = ? "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                done, created, updated, skipped, failed, now(),
                ctx.tenantId(), ctx.projectId(), id);
    }

    @Override
    public void finish(LineageContext ctx, long id, SyncStatus status, String message,
                       List<String> failures) {
        jdbc.update("update sync_job set status = ?, message = ?, failures = ?, "
                        + "finished_at = ?, updated_at = ? "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                status.name(), truncate(message), join(failures), now(), now(),
                ctx.tenantId(), ctx.projectId(), id);
    }

    /**
     * 服务重启后把残留的 RUNNING 标成 FAILED。
     *
     * <p>不带租户过滤是刻意的：这是启动时的一次性清理，要覆盖所有租户。
     * 它不接收 {@link LineageContext} —— 启动阶段还没有任何请求上下文可用。
     * {@code TenantIsolationArchTest} 对本条 SQL 的豁免见该测试类的说明。
     */
    @Override
    public int failInterruptedJobs(String reason) {
        return jdbc.update("update sync_job set status = ?, message = ?, "
                        + "finished_at = ?, updated_at = ? where status in (?, ?)",
                SyncStatus.FAILED.name(), reason, now(), now(),
                SyncStatus.RUNNING.name(), SyncStatus.PENDING.name());
    }

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000) + "…";
    }

    private static String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join("\n", values);
    }

    private static List<String> split(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.asList(value.split("\n")));
    }

    private static final RowMapper<SyncJobRow> MAPPER = (rs, i) -> new SyncJobRow(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getLong("project_id"),
            rs.getLong("source_id"),
            SyncScope.fromString(rs.getString("scope")),
            rs.getString("source_catalog"),
            rs.getString("source_database"),
            rs.getString("source_schema"),
            rs.getString("connection_id"),
            rs.getString("target_catalog"),
            split(rs.getString("tables")),
            rs.getInt("overwrite_manual") == 1,
            SyncStatus.valueOf(rs.getString("status")),
            rs.getInt("total"),
            rs.getInt("done"),
            rs.getInt("created_cnt"),
            rs.getInt("updated_cnt"),
            rs.getInt("skipped_cnt"),
            rs.getInt("failed_cnt"),
            rs.getString("message"),
            split(rs.getString("failures")),
            toLocal(rs.getTimestamp("started_at")),
            toLocal(rs.getTimestamp("finished_at")),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("updated_at")));

    private static java.time.LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
