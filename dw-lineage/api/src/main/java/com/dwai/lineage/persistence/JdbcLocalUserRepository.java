package com.dwai.lineage.persistence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JdbcTemplate 的本地账号/刷新令牌存取（standard 模式认证）。
 *
 * <p>写法与 {@link JdbcTenantAdminRepository} 一致：原生 SQL、按列名取自增主键、
 * 三种数据库共用一份 SQL。与业务仓储唯一的差别是<b>这些 SQL 不带 {@code tenant_id}</b> ——
 * users / refresh_tokens 两张表刻意不带租户维度（登录发生在知道租户之前），
 * 已在 {@code TenantIsolationArchTest} 里按表豁免。
 *
 * <p>时间列一律由 SQL 显式赋 {@code current_timestamp}，不依赖 MySQL 的
 * {@code ON UPDATE CURRENT_TIMESTAMP} —— PostgreSQL 与 H2 都没有那个语义，
 * 依赖它会让三种方言在「updated_at 有没有更新」这件事上表现不一致。
 */
@Repository
public class JdbcLocalUserRepository implements LocalUserRepository {

    private static final String USER_COLUMNS =
            "id, username, display_name, password_hash, status, is_admin, created_at, updated_at";

    private static final String REFRESH_COLUMNS = "id, user_id, token_hash, expires_at, created_at";

    private final JdbcTemplate jdbc;

    public JdbcLocalUserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------- 账号 ----------

    @Override
    public Optional<LocalUserRow> findByUsername(String username) {
        return jdbc.query("select " + USER_COLUMNS + " from users where username = ?",
                        USER_MAPPER, username)
                .stream().findFirst();
    }

    @Override
    public Optional<LocalUserRow> findById(long id) {
        return jdbc.query("select " + USER_COLUMNS + " from users where id = ?",
                        USER_MAPPER, id)
                .stream().findFirst();
    }

    @Override
    public List<LocalUserRow> listUsers() {
        return jdbc.query("select " + USER_COLUMNS + " from users order by id",
                USER_MAPPER);
    }

    @Override
    public long countUsers() {
        Long n = jdbc.queryForObject("select count(*) from users", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public long countEnabledAdmins() {
        Long n = jdbc.queryForObject(
                "select count(*) from users where is_admin = 1 and status = 1", Long.class);
        return n == null ? 0 : n;
    }

    @Override
    public long insertUser(String username, String displayName, String passwordHash, boolean admin) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "insert into users(username, display_name, password_hash, status, is_admin) "
                            + "values(?,?,?,1,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, username);
            ps.setString(2, displayName);
            ps.setString(3, passwordHash);
            ps.setInt(4, admin ? 1 : 0);
            return ps;
        }, keys);
        return JdbcLineageRepository.requireKey(keys);
    }

    @Override
    public LocalUserRow rename(long id, String displayName) {
        jdbc.update("update users set display_name = ?, updated_at = current_timestamp where id = ?",
                displayName, id);
        return findById(id).orElse(null);
    }

    @Override
    public boolean changePassword(long id, String passwordHash) {
        return jdbc.update("update users set password_hash = ?, updated_at = current_timestamp where id = ?",
                passwordHash, id) > 0;
    }

    @Override
    public boolean updateStatus(long id, int status) {
        return jdbc.update("update users set status = ?, updated_at = current_timestamp where id = ?",
                status, id) > 0;
    }

    @Override
    public boolean updateAdmin(long id, boolean admin) {
        return jdbc.update("update users set is_admin = ?, updated_at = current_timestamp where id = ?",
                admin ? 1 : 0, id) > 0;
    }

    @Override
    public boolean deleteUser(long id) {
        return jdbc.update("delete from users where id = ?", id) > 0;
    }

    // ---------- 刷新令牌 ----------

    @Override
    public void insertRefreshToken(String id, long userId, String tokenHash, LocalDateTime expiresAt) {
        jdbc.update("insert into refresh_tokens(id, user_id, token_hash, expires_at) values(?,?,?,?)",
                id, userId, tokenHash, Timestamp.valueOf(expiresAt));
    }

    @Override
    public Optional<RefreshTokenRow> findRefreshTokenByHash(String tokenHash) {
        return jdbc.query("select " + REFRESH_COLUMNS + " from refresh_tokens where token_hash = ?",
                        REFRESH_MAPPER, tokenHash)
                .stream().findFirst();
    }

    @Override
    public boolean extendRefreshTokenExpiry(String id, LocalDateTime expiresAt) {
        return jdbc.update("update refresh_tokens set expires_at = ? where id = ?",
                Timestamp.valueOf(expiresAt), id) > 0;
    }

    @Override
    public boolean deleteRefreshTokenByHash(String tokenHash) {
        return jdbc.update("delete from refresh_tokens where token_hash = ?", tokenHash) > 0;
    }

    @Override
    public void deleteRefreshTokensByUser(long userId) {
        jdbc.update("delete from refresh_tokens where user_id = ?", userId);
    }

    @Override
    public int deleteExpiredRefreshTokens(LocalDateTime now) {
        return jdbc.update("delete from refresh_tokens where expires_at < ?", Timestamp.valueOf(now));
    }

    // ---------- 映射 ----------

    private static final RowMapper<LocalUserRow> USER_MAPPER = (rs, i) -> new LocalUserRow(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("display_name"),
            rs.getString("password_hash"),
            rs.getInt("status"),
            rs.getInt("is_admin") == 1,
            toLocal(rs.getTimestamp("created_at")),
            toLocal(rs.getTimestamp("updated_at")));

    private static final RowMapper<RefreshTokenRow> REFRESH_MAPPER = (rs, i) -> new RefreshTokenRow(
            rs.getString("id"),
            rs.getLong("user_id"),
            rs.getString("token_hash"),
            toLocal(rs.getTimestamp("expires_at")),
            toLocal(rs.getTimestamp("created_at")));

    private static LocalDateTime toLocal(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
