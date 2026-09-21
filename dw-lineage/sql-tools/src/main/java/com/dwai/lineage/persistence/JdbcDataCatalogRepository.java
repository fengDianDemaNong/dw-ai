package com.dwai.lineage.persistence;

import com.dwai.lineage.enums.MatchType;
import com.dwai.lineage.enums.TempRuleTarget;
import com.dwai.lineage.tenant.LineageContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** {@link DataCatalogRepository} 的 JDBC 实现。 */
@Repository
public class JdbcDataCatalogRepository implements DataCatalogRepository {

    private static final String CATALOG_COLUMNS =
            "id, tenant_id, project_id, name, is_default, description, created_at, updated_at";

    private static final String RULE_COLUMNS =
            "id, tenant_id, project_id, catalog_name, target, match_type, pattern, "
                    + "enabled, description, created_at, updated_at";

    private static final RowMapper<DataCatalogRow> CATALOG_MAPPER = (rs, i) -> new DataCatalogRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("project_id"),
            rs.getString("name"), rs.getInt("is_default") == 1, rs.getString("description"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());

    private static final RowMapper<TempRuleRow> RULE_MAPPER = (rs, i) -> new TempRuleRow(
            rs.getLong("id"), rs.getLong("tenant_id"), rs.getLong("project_id"),
            rs.getString("catalog_name"),
            TempRuleTarget.valueOf(rs.getString("target")),
            MatchType.valueOf(rs.getString("match_type")),
            rs.getString("pattern"), rs.getInt("enabled") == 1, rs.getString("description"),
            rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbc;

    public JdbcDataCatalogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ==================== 数据目录 ====================

    @Override
    public List<DataCatalogRow> listCatalogs(LineageContext ctx) {
        return jdbc.query("select " + CATALOG_COLUMNS + " from data_catalog "
                        + "where tenant_id = ? and project_id = ? order by is_default desc, name",
                CATALOG_MAPPER, ctx.tenantId(), ctx.projectId());
    }

    @Override
    public Optional<DataCatalogRow> findCatalog(LineageContext ctx, long id) {
        return jdbc.query("select " + CATALOG_COLUMNS + " from data_catalog "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                CATALOG_MAPPER, ctx.tenantId(), ctx.projectId(), id).stream().findFirst();
    }

    @Override
    public Optional<DataCatalogRow> findCatalogByName(LineageContext ctx, String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return jdbc.query("select " + CATALOG_COLUMNS + " from data_catalog "
                        + "where tenant_id = ? and project_id = ? and lower(name) = ?",
                CATALOG_MAPPER, ctx.tenantId(), ctx.projectId(),
                name.trim().toLowerCase(Locale.ROOT)).stream().findFirst();
    }

    @Override
    public Optional<DataCatalogRow> findDefaultCatalog(LineageContext ctx) {
        return jdbc.query("select " + CATALOG_COLUMNS + " from data_catalog "
                        + "where tenant_id = ? and project_id = ? and is_default = 1 order by id",
                CATALOG_MAPPER, ctx.tenantId(), ctx.projectId()).stream().findFirst();
    }

    @Override
    public long createCatalog(LineageContext ctx, DataCatalogRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into data_catalog(tenant_id, project_id, name, is_default, description) "
                            + "values(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ctx.tenantId());
            ps.setLong(2, ctx.projectId());
            ps.setString(3, row.name());
            ps.setInt(4, row.isDefault() ? 1 : 0);
            ps.setString(5, row.description());
            return ps;
        }, keys);
        return JdbcLineageRepository.requireKey(keys);
    }

    @Override
    public int updateCatalog(LineageContext ctx, long id, String name, String description) {
        return jdbc.update("update data_catalog set name = ?, description = ?, "
                        + "updated_at = current_timestamp "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                name, description, ctx.tenantId(), ctx.projectId(), id);
    }

    /**
     * 清旧默认 + 设新默认必须在同一个事务里。
     *
     * <p>中途失败会留下「一个项目零个默认目录」的状态，而导入元数据时找不到默认目录
     * 就直接失败了 —— 这是个能把功能整个打瘫的中间态。
     */
    @Override
    @Transactional
    public void setDefaultCatalog(LineageContext ctx, long id) {
        jdbc.update("update data_catalog set is_default = 0, updated_at = current_timestamp "
                        + "where tenant_id = ? and project_id = ? and is_default = 1 and id <> ?",
                ctx.tenantId(), ctx.projectId(), id);
        jdbc.update("update data_catalog set is_default = 1, updated_at = current_timestamp "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), id);
    }

    @Override
    public int deleteCatalog(LineageContext ctx, long id) {
        return jdbc.update("delete from data_catalog where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), id);
    }

    @Override
    public int countTables(LineageContext ctx, String catalogName) {
        if (catalogName == null || catalogName.isBlank()) {
            return 0;
        }
        Integer n = jdbc.queryForObject(
                "select count(*) from meta_table where tenant_id = ? and project_id = ? "
                        + "and lower(catalog_name) = ?",
                Integer.class, ctx.tenantId(), ctx.projectId(),
                catalogName.trim().toLowerCase(Locale.ROOT));
        return n == null ? 0 : n;
    }

    // ==================== 临时库表规则 ====================

    @Override
    public List<TempRuleRow> listRules(LineageContext ctx) {
        return jdbc.query("select " + RULE_COLUMNS + " from temp_rule "
                        + "where tenant_id = ? and project_id = ? order by id",
                RULE_MAPPER, ctx.tenantId(), ctx.projectId());
    }

    @Override
    public List<TempRuleRow> listEnabledRules(LineageContext ctx) {
        return jdbc.query("select " + RULE_COLUMNS + " from temp_rule "
                        + "where tenant_id = ? and project_id = ? and enabled = 1 order by id",
                RULE_MAPPER, ctx.tenantId(), ctx.projectId());
    }

    @Override
    public Optional<TempRuleRow> findRule(LineageContext ctx, long id) {
        return jdbc.query("select " + RULE_COLUMNS + " from temp_rule "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                RULE_MAPPER, ctx.tenantId(), ctx.projectId(), id).stream().findFirst();
    }

    @Override
    public long createRule(LineageContext ctx, TempRuleRow row) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into temp_rule(tenant_id, project_id, catalog_name, target, "
                            + "match_type, pattern, enabled, description) values(?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, ctx.tenantId());
            ps.setLong(2, ctx.projectId());
            ps.setString(3, row.catalogName());
            ps.setString(4, row.target().name());
            ps.setString(5, row.matchType().name());
            ps.setString(6, row.pattern());
            ps.setInt(7, row.enabled() ? 1 : 0);
            ps.setString(8, row.description());
            return ps;
        }, keys);
        return JdbcLineageRepository.requireKey(keys);
    }

    @Override
    public int updateRule(LineageContext ctx, long id, TempRuleRow row) {
        return jdbc.update("update temp_rule set catalog_name = ?, target = ?, match_type = ?, "
                        + "pattern = ?, enabled = ?, description = ?, updated_at = current_timestamp "
                        + "where tenant_id = ? and project_id = ? and id = ?",
                row.catalogName(), row.target().name(), row.matchType().name(), row.pattern(),
                row.enabled() ? 1 : 0, row.description(),
                ctx.tenantId(), ctx.projectId(), id);
    }

    @Override
    public int deleteRule(LineageContext ctx, long id) {
        return jdbc.update("delete from temp_rule where tenant_id = ? and project_id = ? and id = ?",
                ctx.tenantId(), ctx.projectId(), id);
    }
}
