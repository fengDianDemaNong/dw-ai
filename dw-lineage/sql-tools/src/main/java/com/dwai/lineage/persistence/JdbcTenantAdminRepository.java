package com.dwai.lineage.persistence;

import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的租户/项目管理面存取。
 *
 * <p>写法与 {@link JdbcMetadataSourceRepository} 一致：原生 SQL、显式 {@code updated_at}
 * （H2 与 PostgreSQL 不会自动维护）、按列名取自增主键，三种数据库共用一份 SQL。
 *
 * <p><b>本文件被 {@code TenantIsolationArchTest} 整体豁免</b> —— {@code tenant} 表自身
 * 没有 {@code tenant_id} 列。豁免按文件名生效，见该测试类的说明。
 */
@Repository
public class JdbcTenantAdminRepository implements TenantAdminRepository {

    private static final String TENANT_COLUMNS = "id, code, name, status, created_at, updated_at";

    private static final String PROJECT_COLUMNS =
            "id, tenant_id, code, name, description, status, created_at, updated_at";

    /** 参与「有没有数据」判定的业务表。metadata_source 不在其中 —— 它是租户级的，不属于项目。 */
    private static final List<String> BUSINESS_TABLES = List.of(
            "lineage_table", "lineage_column", "lineage_version", "lineage_edge",
            "meta_table", "meta_column");

    private final JdbcTemplate jdbc;

    public JdbcTenantAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 租户 ----------

    @Override
    public List<TenantRow> listTenants() {
        return jdbc.query("select " + TENANT_COLUMNS + " from tenant order by id asc", TENANT_MAPPER);
    }

    @Override
    public Optional<TenantRow> findTenant(long id) {
        return jdbc.query("select " + TENANT_COLUMNS + " from tenant where id = ?", TENANT_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public Optional<TenantRow> findTenantByCode(String code) {
        return jdbc.query("select " + TENANT_COLUMNS + " from tenant where code = ?", TENANT_MAPPER, code)
                .stream().findFirst();
    }

    @Override
    public long createTenant(String code, String name) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into tenant(code, name, status) values(?,?,1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            return ps;
        }, keys);
        return JdbcLineageRepository.requireKey(keys);
    }

    @Override
    public boolean updateTenant(long id, String name, int status) {
        return jdbc.update("update tenant set name = ?, status = ?, updated_at = ? where id = ?",
                name, status, now(), id) > 0;
    }

    @Override
    public boolean deleteTenant(long id) {
        return jdbc.update("delete from tenant where id = ?", id) > 0;
    }

    // ---------- 项目 ----------

    @Override
    public List<ProjectRow> listProjects(long tenantId) {
        return jdbc.query("select " + PROJECT_COLUMNS + " from project "
                + "where tenant_id = ? order by id asc", PROJECT_MAPPER, tenantId);
    }

    @Override
    public List<ProjectRow> listAllProjects() {
        return jdbc.query("select " + PROJECT_COLUMNS + " from project order by tenant_id asc, id asc",
                PROJECT_MAPPER);
    }

    @Override
    public Optional<ProjectRow> findProject(long tenantId, long projectId) {
        return jdbc.query("select " + PROJECT_COLUMNS + " from project "
                        + "where tenant_id = ? and id = ?", PROJECT_MAPPER, tenantId, projectId)
                .stream().findFirst();
    }

    @Override
    public Optional<ProjectRow> findProjectByCode(long tenantId, String code) {
        return jdbc.query("select " + PROJECT_COLUMNS + " from project "
                        + "where tenant_id = ? and code = ?", PROJECT_MAPPER, tenantId, code)
                .stream().findFirst();
    }

    @Override
    public long createProject(long tenantId, String code, String name, String description) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into project(tenant_id, code, name, description, status) values(?,?,?,?,1)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, tenantId);
            ps.setString(2, code);
            ps.setString(3, name);
            ps.setString(4, description);
            return ps;
        }, keys);
        return JdbcLineageRepository.requireKey(keys);
    }

    @Override
    public boolean updateProject(long tenantId, long id, String name, String description, int status) {
        return jdbc.update("update project set name = ?, description = ?, status = ?, updated_at = ? "
                + "where tenant_id = ? and id = ?", name, description, status, now(), tenantId, id) > 0;
    }

    @Override
    public boolean deleteProject(long tenantId, long id) {
        return jdbc.update("delete from project where tenant_id = ? and id = ?", tenantId, id) > 0;
    }

    @Override
    public long countBusinessRows(LineageContext ctx) {
        long total = 0;
        for (String table : BUSINESS_TABLES) {
            Long n = jdbc.queryForObject(
                    "select count(*) from " + table + " where tenant_id = ? and project_id = ?",
                    Long.class, ctx.tenantId(), ctx.projectId());
            total += n == null ? 0 : n;
        }
        // 不算 metadata_source：它是租户级的，不属于任何项目，
        // 把它算进去会让「租户下唯一的项目」永远删不掉
        return total;
    }

    private static Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }

    private static final RowMapper<TenantRow> TENANT_MAPPER = (rs, i) -> new TenantRow(
            rs.getLong("id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getInt("status"),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("updated_at")));

    private static final RowMapper<ProjectRow> PROJECT_MAPPER = (rs, i) -> new ProjectRow(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getInt("status"),
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("updated_at")));

    private static java.time.LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
