ALTER TABLE tenant_grants ADD COLUMN IF NOT EXISTS project_roles JSON NULL;
UPDATE tenant_grants SET project_roles = '{}' WHERE project_roles IS NULL;
