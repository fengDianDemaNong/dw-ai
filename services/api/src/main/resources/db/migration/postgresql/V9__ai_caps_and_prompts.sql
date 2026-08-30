ALTER TABLE tenant_licenses ADD COLUMN IF NOT EXISTS ai_caps JSONB NOT NULL DEFAULT '[]'::jsonb;
ALTER TABLE tenant_grants   ADD COLUMN IF NOT EXISTS ai_caps JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE IF NOT EXISTS tenant_ai_prompts (
  tenant_id  VARCHAR(64) NOT NULL,
  slot       VARCHAR(64) NOT NULL,
  body       TEXT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, slot),
  CONSTRAINT fk_aip_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);
