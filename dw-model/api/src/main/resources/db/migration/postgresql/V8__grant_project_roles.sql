ALTER TABLE tenant_grants ADD COLUMN IF NOT EXISTS project_roles JSONB NOT NULL DEFAULT '{}'::jsonb;
