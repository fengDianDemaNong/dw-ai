-- 智仓库：规范 / 模型表。本地也有租户与项目镜像（独立库，不与租户管理共库）。

CREATE TABLE tenants (
  id            VARCHAR(64) PRIMARY KEY,
  code          VARCHAR(64) NOT NULL UNIQUE,
  name          VARCHAR(128) NOT NULL,
  owner         VARCHAR(64) NOT NULL,
  status        VARCHAR(16) NOT NULL DEFAULT 'active',
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
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
  modules   JSON NOT NULL,
  CONSTRAINT fk_lic_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE TABLE projects (
  id            VARCHAR(64) PRIMARY KEY,
  tenant_id     VARCHAR(64) NOT NULL,
  code          VARCHAR(64) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  description   TEXT,
  owner         VARCHAR(64) NOT NULL,
  created_at    DATE NOT NULL,
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

CREATE TABLE domains (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL,
  code          VARCHAR(16) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  definition    TEXT,
  biz_owner     VARCHAR(64),
  tech_owner    VARCHAR(64),
  data_owner    VARCHAR(64),
  related       JSON NOT NULL,
  core_entities JSON NOT NULL,
  CONSTRAINT uk_domains_proj_code UNIQUE (project_id, code),
  CONSTRAINT fk_dom_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE layer_rules (
  id            BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  project_id    VARCHAR(64),
  layer         VARCHAR(32) NOT NULL,
  naming        TEXT NOT NULL,
  retention     VARCHAR(64),
  serve         VARCHAR(16) NOT NULL,
  note          TEXT,
  field_format  TEXT,
  time_format   TEXT,
  masking       VARCHAR(16),
  masking_note  TEXT,
  null_handling VARCHAR(16),
  null_fill     TEXT,
  CONSTRAINT fk_layer_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE data_grades (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL,
  code          VARCHAR(16) NOT NULL,
  name          VARCHAR(64) NOT NULL,
  level         INT NOT NULL,
  color         VARCHAR(16),
  query_policy  VARCHAR(16) NOT NULL,
  export_policy VARCHAR(16) NOT NULL,
  note          TEXT,
  examples      TEXT,
  CONSTRAINT uk_grades_proj_code UNIQUE (project_id, code),
  CONSTRAINT fk_grade_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE word_roots (
  id         VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  kind       VARCHAR(16) NOT NULL,
  code       VARCHAR(64) NOT NULL,
  zh         VARCHAR(64) NOT NULL,
  en         VARCHAR(64),
  domain     VARCHAR(16),
  formula    TEXT,
  data_type  VARCHAR(64),
  format     VARCHAR(64),
  CONSTRAINT uk_roots_proj_code UNIQUE (project_id, code),
  CONSTRAINT fk_root_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE warehouse_tables (
  id             VARCHAR(64) PRIMARY KEY,
  project_id     VARCHAR(64) NOT NULL,
  layer          VARCHAR(32) NOT NULL,
  name           VARCHAR(128) NOT NULL,
  comment        TEXT,
  domain         VARCHAR(16),
  source_system  VARCHAR(64),
  grain          VARCHAR(64),
  period         VARCHAR(16),
  partition_col  VARCHAR(64),
  stored_as      VARCHAR(32),
  status         VARCHAR(16) NOT NULL,
  created_from   VARCHAR(64),
  grade          VARCHAR(16),
  current_version INT,
  CONSTRAINT uk_tables_proj_name UNIQUE (project_id, name),
  CONSTRAINT fk_tbl_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE table_columns (
  table_id      VARCHAR(64) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  type          VARCHAR(64) NOT NULL,
  comment       TEXT,
  nullable      BOOLEAN,
  -- `sensitive` 是 MySQL 8 的保留字，不加反引号这条 CREATE TABLE 直接语法错。
  -- H2（MODE=MySQL）同样认反引号，所以这一个写法两边通用。
  `sensitive`   BOOLEAN,
  grade         VARCHAR(16),
  enum_values   JSON,
  pos           INT NOT NULL DEFAULT 0,
  default_value TEXT,
  PRIMARY KEY (table_id, name),
  CONSTRAINT fk_col_tbl FOREIGN KEY (table_id) REFERENCES warehouse_tables (id) ON DELETE CASCADE
);

CREATE TABLE modeling_drafts (
  id                 VARCHAR(64) PRIMARY KEY,
  project_id         VARCHAR(64) NOT NULL,
  source_table_id    VARCHAR(64),
  target_layer       VARCHAR(16) NOT NULL,
  domain_code        VARCHAR(16),
  domain_confidence  DECIMAL(4, 2),
  grain              VARCHAR(64),
  primary_keys       JSON NOT NULL,
  field_tags         JSON NOT NULL,
  ddl                TEXT,
  etl_sql            TEXT,
  quality_rules      JSON NOT NULL,
  spec_issues        JSON NOT NULL,
  status             VARCHAR(32) NOT NULL,
  created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_draft_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE table_versions (
  id            VARCHAR(64) PRIMARY KEY,
  table_id      VARCHAR(64) NOT NULL,
  version       INT NOT NULL,
  note          TEXT,
  actor_user_id VARCHAR(64),
  created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  snapshot      JSON NOT NULL,
  CONSTRAINT uk_tver UNIQUE (table_id, version),
  CONSTRAINT fk_tver_tbl FOREIGN KEY (table_id) REFERENCES warehouse_tables (id) ON DELETE CASCADE
);

CREATE TABLE tenant_grants (
  id          VARCHAR(64) PRIMARY KEY,
  tenant_id   VARCHAR(64) NOT NULL,
  code        VARCHAR(64) NOT NULL UNIQUE,
  kind        VARCHAR(16) NOT NULL,
  expires_at  TIMESTAMP NULL,
  revoked_at  TIMESTAMP NULL,
  created_by  VARCHAR(64),
  created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  modules     JSON NOT NULL,
  project_ids JSON NOT NULL,
  project_roles JSON NOT NULL,
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
  api_key_enc BLOB,
  updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_llm_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

CREATE TABLE jobs (
  id              VARCHAR(64) PRIMARY KEY,
  project_id      VARCHAR(64) NOT NULL,
  name            VARCHAR(256) NOT NULL,
  type            VARCHAR(32) NOT NULL,
  engine          VARCHAR(64),
  depends_on      JSON NOT NULL,
  status          VARCHAR(16) NOT NULL,
  last_run        TIMESTAMP NULL,
  duration_ms     INT,
  table_name      VARCHAR(128),
  ds_process_code VARCHAR(128),
  CONSTRAINT fk_job_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE quality_rules (
  id         VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  type       VARCHAR(32) NOT NULL,
  field      VARCHAR(128),
  logic      TEXT,
  threshold  VARCHAR(64),
  status     VARCHAR(16) NOT NULL,
  -- 同上，last_value 也是 MySQL 8 保留字（窗口函数用）。
  `last_value` VARCHAR(64),
  CONSTRAINT fk_qr_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE metrics (
  id                VARCHAR(64) PRIMARY KEY,
  project_id        VARCHAR(64) NOT NULL,
  name              VARCHAR(128) NOT NULL,
  type              VARCHAR(16) NOT NULL,
  business_process  VARCHAR(64),
  measure           VARCHAR(64),
  aggregation       VARCHAR(32),
  data_type         VARCHAR(32),
  unit              VARCHAR(16),
  definition        TEXT,
  calculation_logic TEXT NOT NULL,
  source_table      VARCHAR(128),
  dimensions        JSON NOT NULL,
  modifiers         JSON,
  time_period       VARCHAR(32),
  formula           TEXT,
  owner             VARCHAR(64),
  status            VARCHAR(16) NOT NULL,
  version           VARCHAR(16),
  changelog         JSON NOT NULL,
  sensitivity       VARCHAR(16),
  score             DECIMAL(4, 1) DEFAULT 0,
  favorites         INT DEFAULT 0,
  CONSTRAINT fk_met_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE serve_folders (
  id         VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  scope      VARCHAR(16) NOT NULL,
  name       VARCHAR(128) NOT NULL,
  owner      VARCHAR(64),
  CONSTRAINT fk_sf_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE serve_metrics (
  id                VARCHAR(64) PRIMARY KEY,
  project_id        VARCHAR(64) NOT NULL,
  name              VARCHAR(128) NOT NULL,
  definition        TEXT,
  calculation_logic TEXT NOT NULL,
  source_table      VARCHAR(128),
  dimensions        JSON NOT NULL,
  measure           VARCHAR(64),
  aggregation       VARCHAR(32),
  unit              VARCHAR(16),
  owner             VARCHAR(64) NOT NULL,
  scope             VARCHAR(16) NOT NULL,
  folder_id         VARCHAR(64),
  status            VARCHAR(16) NOT NULL,
  cloned_from       VARCHAR(64),
  CONSTRAINT fk_sm_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE,
  CONSTRAINT fk_sm_folder FOREIGN KEY (folder_id) REFERENCES serve_folders (id)
);

CREATE TABLE approvals (
  id         VARCHAR(64) PRIMARY KEY,
  project_id VARCHAR(64) NOT NULL,
  kind       VARCHAR(32) NOT NULL,
  target_id  VARCHAR(64) NOT NULL,
  title      VARCHAR(256) NOT NULL,
  status     VARCHAR(16) NOT NULL,
  applicant  VARCHAR(64) NOT NULL,
  reviewer   VARCHAR(64),
  comment    TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  decided_at TIMESTAMP NULL,
  CONSTRAINT fk_appr_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE query_logs (
  id              VARCHAR(64) PRIMARY KEY,
  project_id      VARCHAR(64) NOT NULL,
  user_id         VARCHAR(64),
  ts              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  datasource      VARCHAR(128),
  dimensions      JSON,
  measures        JSON,
  filters         JSON,
  sql_fingerprint TEXT,
  execution_ms    INT,
  scan_rows       BIGINT,
  cost_score      DECIMAL(8, 2),
  CONSTRAINT fk_ql_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE query_clusters (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL,
  query_ids     JSON NOT NULL,
  dimensions    JSON,
  measures      JSON,
  monthly_count INT,
  avg_ms        INT,
  suggestion    TEXT,
  CONSTRAINT fk_qc_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE materialize_recs (
  id             VARCHAR(64) PRIMARY KEY,
  project_id     VARCHAR(64) NOT NULL,
  cluster_id     VARCHAR(64),
  action         VARCHAR(16),
  target_table   VARCHAR(128),
  ddl            TEXT,
  precompute_sql TEXT,
  scores         JSON,
  status         VARCHAR(16) NOT NULL,
  gray_percent   INT NOT NULL DEFAULT 0,
  roi            JSON,
  CONSTRAINT fk_mr_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE TABLE api_calls (
  id          VARCHAR(64) PRIMARY KEY,
  project_id  VARCHAR(64) NOT NULL,
  app_key     VARCHAR(64),
  metric_id   VARCHAR(64),
  user_id     VARCHAR(64),
  return_rows INT,
  cost_ms     INT,
  cache_hit   BOOLEAN,
  ts          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_ac_proj FOREIGN KEY (project_id) REFERENCES projects (id) ON DELETE CASCADE
);

CREATE INDEX idx_tables_project_layer ON warehouse_tables (project_id, layer);
CREATE INDEX idx_serve_metrics_scope ON serve_metrics (project_id, scope, status);
CREATE INDEX idx_approvals_status ON approvals (project_id, status);
CREATE INDEX idx_query_logs_project_ts ON query_logs (project_id, ts);
CREATE INDEX idx_projects_tenant ON projects (tenant_id);
CREATE INDEX idx_members_user ON project_members (user_id);
CREATE INDEX idx_domains_project ON domains (project_id);
CREATE INDEX idx_layers_project ON layer_rules (project_id);
CREATE INDEX idx_grades_project ON data_grades (project_id);
CREATE INDEX idx_roots_project ON word_roots (project_id);
CREATE INDEX idx_columns_table ON table_columns (table_id);
CREATE INDEX idx_drafts_project ON modeling_drafts (project_id);
