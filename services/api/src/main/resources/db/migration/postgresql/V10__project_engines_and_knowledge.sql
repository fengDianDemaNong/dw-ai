ALTER TABLE projects ADD COLUMN IF NOT EXISTS engines JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS tenant_knowledge_articles (
  tenant_id    VARCHAR(64)  NOT NULL,
  engine       VARCHAR(32)  NOT NULL,
  article_id   VARCHAR(128) NOT NULL,
  title        VARCHAR(256) NOT NULL,
  summary      TEXT,
  body         TEXT,
  source_url   TEXT,
  source_label VARCHAR(256),
  sections     JSONB,
  notes        JSONB,
  imported_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  imported_by  VARCHAR(64),
  PRIMARY KEY (tenant_id, engine, article_id),
  CONSTRAINT fk_tka_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);
