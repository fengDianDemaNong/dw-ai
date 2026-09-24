package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.MetadataSourceType;
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
 * 基于 JdbcTemplate 的元数据服务配置存取。
 *
 * <p>与 {@link JdbcLineageRepository} 保持同一套写法（原生 SQL + 显式租户过滤 + 按列名取自增主键），
 * 三种数据库共用一份 SQL。
 *
 * <p><b>这张表只按租户隔离，不按项目。</b> 元数据服务是 Gravitino / dbx 的连接配置，
 * 同一租户下所有项目共用一份 —— 每个项目各配一遍是多余的负担。
 * 因此这里的 SQL 只带 {@code tenant_id}，方法仍收 {@link LineageContext}
 * （签名统一，也留着将来改回项目级的余地），但只用它的 {@code tenantId()}。
 */
@Repository
public class JdbcMetadataSourceRepository implements MetadataSourceRepository {

    private static final String COLUMNS =
            "id, tenant_id, name, type, base_url, credential, extra_config, "
                    + "priority, enabled, created_at, updated_at";

    private final JdbcTemplate jdbc;

    public JdbcMetadataSourceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MetadataSourceRow> list(LineageContext ctx) {
        return jdbc.query("select " + COLUMNS + " from metadata_source "
                        + "where tenant_id = ? order by priority asc, id asc",
                MAPPER, ctx.tenantId());
    }

    @Override
    public List<MetadataSourceRow> listEnabled(LineageContext ctx) {
        return jdbc.query("select " + COLUMNS + " from metadata_source "
                        + "where tenant_id = ? and enabled = 1 "
                        + "order by priority asc, id asc",
                MAPPER, ctx.tenantId());
    }

    @Override
    public Optional<MetadataSourceRow> findById(LineageContext ctx, long id) {
        List<MetadataSourceRow> rows = jdbc.query("select " + COLUMNS + " from metadata_source "
                        + "where tenant_id = ? and id = ?",
                MAPPER, ctx.tenantId(), id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Override
    public long insert(LineageContext ctx, MetadataSourceRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into metadata_source(tenant_id, name, type, base_url, "
                            + "credential, extra_config, priority, enabled) values(?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ctx.tenantId());
            ps.setString(2, row.name());
            ps.setString(3, row.type().name());
            ps.setString(4, row.baseUrl());
            ps.setString(5, row.credential());
            ps.setString(6, row.extraConfig());
            ps.setInt(7, row.priority());
            ps.setInt(8, row.enabled() ? 1 : 0);
            return ps;
        }, keys);
        return JdbcLineageRepository.requireKey(keys);
    }

    @Override
    public boolean update(LineageContext ctx, long id, MetadataSourceRow row, boolean keepCredential) {
        // updated_at 显式赋值：只有 MySQL 的 ON UPDATE CURRENT_TIMESTAMP 会自动维护，
        // H2 与 PostgreSQL 不会，不写就永远停在创建时间
        String sql = "update metadata_source set name = ?, type = ?, base_url = ?, "
                + (keepCredential ? "" : "credential = ?, ")
                + "extra_config = ?, priority = ?, enabled = ?, updated_at = ? "
                + "where tenant_id = ? and id = ?";

        Object[] args = keepCredential
                ? new Object[]{row.name(), row.type().name(), row.baseUrl(),
                row.extraConfig(), row.priority(), row.enabled() ? 1 : 0,
                new Timestamp(System.currentTimeMillis()),
                ctx.tenantId(), id}
                : new Object[]{row.name(), row.type().name(), row.baseUrl(), row.credential(),
                row.extraConfig(), row.priority(), row.enabled() ? 1 : 0,
                new Timestamp(System.currentTimeMillis()),
                ctx.tenantId(), id};

        return jdbc.update(sql, args) > 0;
    }

    @Override
    public boolean delete(LineageContext ctx, long id) {
        return jdbc.update("delete from metadata_source where tenant_id = ? and id = ?",
                ctx.tenantId(), id) > 0;
    }

    private static final RowMapper<MetadataSourceRow> MAPPER = (rs, i) -> new MetadataSourceRow(
            rs.getLong("id"),
            rs.getLong("tenant_id"),
            rs.getString("name"),
            MetadataSourceType.fromString(rs.getString("type")),
            rs.getString("base_url"),
            rs.getString("credential"),
            rs.getString("extra_config"),
            rs.getInt("priority"),
            rs.getInt("enabled") == 1,
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());
}
