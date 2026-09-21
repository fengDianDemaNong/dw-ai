ALTER TABLE tenant_licenses ADD COLUMN ai_caps JSON NULL;
UPDATE tenant_licenses SET ai_caps = '[]' WHERE ai_caps IS NULL;
ALTER TABLE tenant_grants ADD COLUMN ai_caps JSON NULL;
UPDATE tenant_grants SET ai_caps = '[]' WHERE ai_caps IS NULL;

CREATE TABLE tenant_ai_prompts (
  tenant_id  VARCHAR(64) NOT NULL,
  slot       VARCHAR(64) NOT NULL,
  body       TEXT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (tenant_id, slot),
  CONSTRAINT fk_aip_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);
