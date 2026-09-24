-- 这里的两条 ALTER 都是必需的：mysql/V1__schema.sql:90 只有 menu_pos，没有 menu_color。
-- 但不要顺手加 IF NOT EXISTS —— MySQL 8 不认这个扩展语法（H2 认，测试查不出来），
-- 详见 V8__grant_project_roles.sql 顶部注释。
ALTER TABLE appearance_prefs
  ADD COLUMN menu_color VARCHAR(16) NOT NULL DEFAULT 'ink';

ALTER TABLE appearance_prefs
  ALTER COLUMN menu_pos SET DEFAULT 'drawer';

UPDATE tenant_licenses
SET modules = '["warehouse","metadata"]'
WHERE tenant_id IN ('t-xinghe', 't-qihang')
  AND (modules IS NULL OR CAST(modules AS CHAR) NOT LIKE '%metadata%');
