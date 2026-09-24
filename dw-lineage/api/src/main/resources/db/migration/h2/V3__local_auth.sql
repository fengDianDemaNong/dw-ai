-- V3：本地账号与刷新令牌。
--
-- standard 模式（独立部署、自带账号）用这两张表；standalone 没有认证不写；
-- multi 下身份由组织签发，users 始终是空的。
--
-- 两张表都**不带租户维度**，这是刻意的：登录发生在「知道租户之前」——
-- 拿用户名查账号时还没有任何租户上下文，加 tenant_id 就只能靠猜。
-- 租户归属由每个请求的 X-Tenant-Code / 默认租户决定，账号本身是全局的。

CREATE TABLE users (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    username       VARCHAR(64)  NOT NULL,
    display_name   VARCHAR(128) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    status         TINYINT      NOT NULL DEFAULT 1,
    is_admin       TINYINT      NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE refresh_tokens (
    id          VARCHAR(32) NOT NULL,
    user_id     BIGINT      NOT NULL,
    token_hash  CHAR(64)    NOT NULL,
    expires_at  TIMESTAMP   NOT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT uk_refresh_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_user ON refresh_tokens (user_id);

COMMENT ON TABLE users IS '本地账号。standard 模式的认证主体';
COMMENT ON COLUMN users.id IS '主键';
COMMENT ON COLUMN users.username IS '登录名，全局唯一';
COMMENT ON COLUMN users.display_name IS '展示名';
COMMENT ON COLUMN users.password_hash IS 'BCrypt 哈希。不存明文，也不用可逆加密 —— 凭据一旦可解密就等于明文';
COMMENT ON COLUMN users.status IS '1-启用 0-停用。停用后拒绝登录，不做物理删除';
COMMENT ON COLUMN users.is_admin IS '1-管理员 0-普通用户。管理员可管理账号，普通用户只能改自己的密码与显示名';
COMMENT ON COLUMN users.created_at IS '创建时间';
COMMENT ON COLUMN users.updated_at IS '更新时间';

COMMENT ON TABLE refresh_tokens IS '刷新令牌。必须落库才撤得掉 —— 无状态 JWT 做不到登出即失效';
COMMENT ON COLUMN refresh_tokens.id IS '主键，rt- 前缀';
COMMENT ON COLUMN refresh_tokens.user_id IS '所属本地账号';
COMMENT ON COLUMN refresh_tokens.token_hash IS '令牌的 SHA-256 十六进制。明文只在响应里出现一次，不落库';
COMMENT ON COLUMN refresh_tokens.expires_at IS '过期时间';
COMMENT ON COLUMN refresh_tokens.created_at IS '创建时间';

-- 本迁移不改动存量数据，也不写初始数据：账号由 LocalAdminSeedRunner 在 standard 模式下种，
-- 而且只在「一个账号都没有」时种。
