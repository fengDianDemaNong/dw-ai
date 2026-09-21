-- 租户管理库：人 / 租户 / 项目 / 开通。不含建模表（那些在智仓库）。

CREATE TABLE tenants (
  id            VARCHAR(64) PRIMARY KEY,
  code          VARCHAR(64) NOT NULL UNIQUE,
  name          VARCHAR(128) NOT NULL,
  owner         VARCHAR(64) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
  id             VARCHAR(64) PRIMARY KEY,
  tenant_id      VARCHAR(64),
  username       VARCHAR(64) NOT NULL,
  display_name   VARCHAR(128) NOT NULL,
  password_hash  VARCHAR(255),
  status         VARCHAR(16) NOT NULL DEFAULT 'active',
  platform_admin BOOLEAN NOT NULL DEFAULT FALSE,
  casdoor_id     VARCHAR(128),
  CONSTRAINT uk_users_username UNIQUE (username)
);

CREATE TABLE user_tenants (
  user_id     VARCHAR(64) NOT NULL,
  tenant_id   VARCHAR(64) NOT NULL,
  tenant_role VARCHAR(16) NOT NULL DEFAULT 'member',
  PRIMARY KEY (user_id, tenant_id),
  CONSTRAINT fk_ut_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_ut_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE TABLE tenant_licenses (
  tenant_id VARCHAR(64) PRIMARY KEY,
  modules   JSONB NOT NULL,
  CONSTRAINT fk_lic_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE TABLE projects (
  id            VARCHAR(64) PRIMARY KEY,
  tenant_id     VARCHAR(64) NOT NULL,
  code          VARCHAR(64) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  description   TEXT,
  owner         VARCHAR(64) NOT NULL,
  created_at    DATE NOT NULL DEFAULT CURRENT_DATE,
  status        VARCHAR(16) NOT NULL DEFAULT 'active',
  CONSTRAINT uk_projects_tenant_code UNIQUE (tenant_id, code),
  CONSTRAINT fk_proj_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE project_members (
  project_id VARCHAR(64) NOT NULL,
  user_id    VARCHAR(64) NOT NULL,
  role       VARCHAR(32) NOT NULL,
  PRIMARY KEY (project_id, user_id),
  CONSTRAINT fk_pm_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
  CONSTRAINT fk_pm_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE tenant_grants (
  id          VARCHAR(64) PRIMARY KEY,
  tenant_id   VARCHAR(64) NOT NULL,
  code        VARCHAR(64) NOT NULL UNIQUE,
  kind        VARCHAR(16) NOT NULL,
  expires_at  TIMESTAMPTZ,
  revoked_at  TIMESTAMPTZ,
  created_by  VARCHAR(64),
  created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  modules     JSONB NOT NULL,
  project_ids JSONB NOT NULL,
  project_roles JSONB NOT NULL DEFAULT '{}'::jsonb,
  CONSTRAINT fk_grant_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE TABLE platform_access (
  user_id   VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL,
  grant_id  VARCHAR(64) NOT NULL,
  PRIMARY KEY (user_id, tenant_id),
  CONSTRAINT fk_pa_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
  CONSTRAINT fk_pa_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE,
  CONSTRAINT fk_pa_grant FOREIGN KEY (grant_id) REFERENCES tenant_grants (id) ON DELETE CASCADE
);

CREATE TABLE appearance_prefs (
  scope     VARCHAR(16) NOT NULL,
  tenant_id VARCHAR(64) NOT NULL DEFAULT '',
  theme     VARCHAR(32) NOT NULL DEFAULT 'cyan',
  menu_pos  VARCHAR(16) NOT NULL DEFAULT 'left',
  PRIMARY KEY (scope, tenant_id)
);

CREATE TABLE tenant_llm (
  tenant_id   VARCHAR(64) PRIMARY KEY,
  enabled     BOOLEAN NOT NULL DEFAULT FALSE,
  provider    VARCHAR(32),
  base_url    VARCHAR(512),
  model       VARCHAR(128),
  api_key_enc BYTEA,
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_llm_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE INDEX idx_projects_tenant ON projects (tenant_id);
CREATE INDEX idx_members_user ON project_members (user_id);
