ALTER TABLE appearance_prefs
  ADD COLUMN IF NOT EXISTS menu_color VARCHAR(16) NOT NULL DEFAULT 'ink';

ALTER TABLE appearance_prefs
  ALTER COLUMN menu_pos SET DEFAULT 'drawer';

UPDATE tenant_licenses
SET modules = '["warehouse","metadata"]'
WHERE tenant_id IN ('t-xinghe', 't-qihang')
  AND modules::text NOT LIKE '%metadata%';
