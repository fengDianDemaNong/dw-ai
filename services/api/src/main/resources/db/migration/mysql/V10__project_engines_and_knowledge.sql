ALTER TABLE projects ADD COLUMN engines JSON NULL;
UPDATE projects SET engines = '[]' WHERE engines IS NULL;

CREATE TABLE tenant_knowledge_articles (
  tenant_id    VARCHAR(64)  NOT NULL,
  engine       VARCHAR(32)  NOT NULL,
  article_id   VARCHAR(128) NOT NULL,
  title        VARCHAR(256) NOT NULL,
  summary      TEXT,
  body         TEXT,
  source_url   TEXT,
  source_label VARCHAR(256),
  sections     JSON,
  notes        JSON,
  imported_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  imported_by  VARCHAR(64),
  PRIMARY KEY (tenant_id, engine, article_id),
  CONSTRAINT fk_tka_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);
