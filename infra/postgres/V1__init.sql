-- 智仓 DW-AI 元数据（PostgreSQL）
-- 对齐 packages/engine/src/types.ts；审批与目录为生产增量。

CREATE TABLE tenants (
  id            VARCHAR(64) PRIMARY KEY,
  code          VARCHAR(64) NOT NULL UNIQUE,
  name          VARCHAR(128) NOT NULL,
  owner         VARCHAR(64) NOT NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE users (
  id            VARCHAR(64) PRIMARY KEY,
  tenant_id     VARCHAR(64) NOT NULL REFERENCES tenants (id),
  username      VARCHAR(64) NOT NULL,
  display_name  VARCHAR(128) NOT NULL,
  password_hash VARCHAR(255),
  status        VARCHAR(16) NOT NULL DEFAULT 'active',
  UNIQUE (tenant_id, username)
);

CREATE TABLE projects (
  id            VARCHAR(64) PRIMARY KEY,
  tenant_id     VARCHAR(64) NOT NULL REFERENCES tenants (id),
  code          VARCHAR(64) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  description   TEXT,
  owner         VARCHAR(64) NOT NULL,
  created_at    DATE NOT NULL DEFAULT CURRENT_DATE,
  UNIQUE (tenant_id, code)
);

CREATE TABLE project_members (
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  user_id       VARCHAR(64) NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  role          VARCHAR(32) NOT NULL,
  PRIMARY KEY (project_id, user_id)
);

CREATE TABLE domains (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  code          VARCHAR(16) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  definition    TEXT,
  biz_owner     VARCHAR(64),
  tech_owner    VARCHAR(64),
  data_owner    VARCHAR(64),
  related       JSONB NOT NULL DEFAULT '[]',
  core_entities JSONB NOT NULL DEFAULT '[]',
  UNIQUE (project_id, code)
);

CREATE TABLE layer_rules (
  id            BIGSERIAL PRIMARY KEY,
  project_id    VARCHAR(64) REFERENCES projects (id) ON DELETE CASCADE,
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
  UNIQUE (project_id, layer)
);

CREATE TABLE data_grades (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  code          VARCHAR(16) NOT NULL,
  name          VARCHAR(64) NOT NULL,
  level         INT NOT NULL,
  color         VARCHAR(16),
  query_policy  VARCHAR(16) NOT NULL,
  export_policy VARCHAR(16) NOT NULL,
  note          TEXT,
  examples      TEXT,
  UNIQUE (project_id, code)
);

CREATE TABLE word_roots (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  kind          VARCHAR(16) NOT NULL,
  code          VARCHAR(64) NOT NULL,
  zh            VARCHAR(64) NOT NULL,
  en            VARCHAR(64),
  domain        VARCHAR(16),
  formula       TEXT,
  data_type     VARCHAR(64),
  format        VARCHAR(64),
  UNIQUE (project_id, code)
);

CREATE TABLE warehouse_tables (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  layer         VARCHAR(32) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  comment       TEXT,
  domain        VARCHAR(16),
  source_system VARCHAR(64),
  grain         VARCHAR(64),
  period        VARCHAR(16),
  partition_col VARCHAR(64),
  stored_as     VARCHAR(32),
  status        VARCHAR(16) NOT NULL,
  created_from  VARCHAR(64),
  grade         VARCHAR(16),
  UNIQUE (project_id, name)
);

CREATE TABLE table_columns (
  table_id      VARCHAR(64) NOT NULL REFERENCES warehouse_tables (id) ON DELETE CASCADE,
  name          VARCHAR(128) NOT NULL,
  type          VARCHAR(64) NOT NULL,
  comment       TEXT,
  nullable      BOOLEAN,
  sensitive     BOOLEAN,
  grade         VARCHAR(16),
  enum_values   JSONB,
  pos           INT NOT NULL DEFAULT 0,
  PRIMARY KEY (table_id, name)
);

CREATE TABLE modeling_drafts (
  id                 VARCHAR(64) PRIMARY KEY,
  project_id         VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  source_table_id    VARCHAR(64),
  target_layer       VARCHAR(16) NOT NULL,
  domain_code        VARCHAR(16),
  domain_confidence  NUMERIC(4, 2),
  grain              VARCHAR(64),
  primary_keys       JSONB NOT NULL DEFAULT '[]',
  field_tags         JSONB NOT NULL DEFAULT '[]',
  ddl                TEXT,
  etl_sql            TEXT,
  quality_rules      JSONB NOT NULL DEFAULT '[]',
  spec_issues        JSONB NOT NULL DEFAULT '[]',
  status             VARCHAR(32) NOT NULL,
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE jobs (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  name          VARCHAR(256) NOT NULL,
  type          VARCHAR(32) NOT NULL,
  engine        VARCHAR(64),
  depends_on    JSONB NOT NULL DEFAULT '[]',
  status        VARCHAR(16) NOT NULL,
  last_run      TIMESTAMPTZ,
  duration_ms   INT,
  table_name    VARCHAR(128),
  ds_process_code VARCHAR(128)
);

CREATE TABLE quality_rules (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  table_name    VARCHAR(128) NOT NULL,
  type          VARCHAR(32) NOT NULL,
  field         VARCHAR(128),
  logic         TEXT,
  threshold     VARCHAR(64),
  status        VARCHAR(16) NOT NULL,
  last_value    VARCHAR(64)
);

CREATE TABLE metrics (
  id                 VARCHAR(64) PRIMARY KEY,
  project_id         VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  name               VARCHAR(128) NOT NULL,
  type               VARCHAR(16) NOT NULL,
  business_process   VARCHAR(64),
  measure            VARCHAR(64),
  aggregation        VARCHAR(32),
  data_type          VARCHAR(32),
  unit               VARCHAR(16),
  definition         TEXT,
  calculation_logic  TEXT NOT NULL,
  source_table       VARCHAR(128),
  dimensions         JSONB NOT NULL DEFAULT '[]',
  modifiers          JSONB,
  time_period        VARCHAR(32),
  formula            TEXT,
  owner              VARCHAR(64),
  status             VARCHAR(16) NOT NULL,
  version            VARCHAR(16),
  changelog          JSONB NOT NULL DEFAULT '[]',
  sensitivity        VARCHAR(16),
  score              NUMERIC(4, 1) DEFAULT 0,
  favorites          INT DEFAULT 0
);

CREATE UNIQUE INDEX uk_metrics_name_logic ON metrics (project_id, name, md5(calculation_logic));

CREATE TABLE serve_folders (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  scope         VARCHAR(16) NOT NULL,
  name          VARCHAR(128) NOT NULL,
  owner         VARCHAR(64)
);

CREATE TABLE serve_metrics (
  id                 VARCHAR(64) PRIMARY KEY,
  project_id         VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  name               VARCHAR(128) NOT NULL,
  definition         TEXT,
  calculation_logic  TEXT NOT NULL,
  source_table       VARCHAR(128),
  dimensions         JSONB NOT NULL DEFAULT '[]',
  measure            VARCHAR(64),
  aggregation        VARCHAR(32),
  unit               VARCHAR(16),
  owner              VARCHAR(64) NOT NULL,
  scope              VARCHAR(16) NOT NULL,
  folder_id          VARCHAR(64) REFERENCES serve_folders (id),
  status             VARCHAR(16) NOT NULL,
  cloned_from        VARCHAR(64)
);

CREATE TABLE approvals (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  kind          VARCHAR(32) NOT NULL,
  target_id     VARCHAR(64) NOT NULL,
  title         VARCHAR(256) NOT NULL,
  status        VARCHAR(16) NOT NULL,
  applicant     VARCHAR(64) NOT NULL,
  reviewer      VARCHAR(64),
  comment       TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at    TIMESTAMPTZ
);

CREATE TABLE query_logs (
  id              VARCHAR(64) PRIMARY KEY,
  project_id      VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  user_id         VARCHAR(64),
  ts              TIMESTAMPTZ NOT NULL DEFAULT now(),
  datasource      VARCHAR(128),
  dimensions      JSONB,
  measures        JSONB,
  filters         JSONB,
  sql_fingerprint TEXT,
  execution_ms    INT,
  scan_rows       BIGINT,
  cost_score      NUMERIC(8, 2)
);

CREATE TABLE query_clusters (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  query_ids     JSONB NOT NULL DEFAULT '[]',
  dimensions    JSONB,
  measures      JSONB,
  monthly_count INT,
  avg_ms        INT,
  suggestion    TEXT
);

CREATE TABLE materialize_recs (
  id             VARCHAR(64) PRIMARY KEY,
  project_id     VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  cluster_id     VARCHAR(64),
  action         VARCHAR(16),
  target_table   VARCHAR(128),
  ddl            TEXT,
  precompute_sql TEXT,
  scores         JSONB,
  status         VARCHAR(16) NOT NULL,
  gray_percent   INT NOT NULL DEFAULT 0,
  roi            JSONB
);

CREATE TABLE api_calls (
  id            VARCHAR(64) PRIMARY KEY,
  project_id    VARCHAR(64) NOT NULL REFERENCES projects (id) ON DELETE CASCADE,
  app_key       VARCHAR(64),
  metric_id     VARCHAR(64),
  user_id       VARCHAR(64),
  return_rows   INT,
  cost_ms       INT,
  cache_hit     BOOLEAN,
  ts            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_tables_project_layer ON warehouse_tables (project_id, layer);
CREATE INDEX idx_serve_metrics_scope ON serve_metrics (project_id, scope, status);
CREATE INDEX idx_approvals_status ON approvals (project_id, status);
CREATE INDEX idx_query_logs_project_ts ON query_logs (project_id, ts DESC);
