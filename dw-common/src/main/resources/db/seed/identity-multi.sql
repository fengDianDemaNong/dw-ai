-- 第二个租户「启航科技」及其默认空项目。只有 multi 模式执行。
--
-- 组织平台恒为 multi，所以它总会跑到这里；仓建设只有配成 multi 时才会。
-- 单租户模式（standalone / standard）下不执行的理由见 identity.sql 顶部 ——
-- 第二个租户在那些模式下不可见，灌进去只是不可达数据。
--
-- 前置：identity.sql 已经跑过（它负责 DELETE 与第一个租户的所有行）。
-- 本文件只追加，不删除任何东西。

INSERT INTO tenants (id, code, name, owner, status) VALUES
  ('t-qihang', 'qihang', '启航科技', '李四', 'active');

INSERT INTO tenant_licenses (tenant_id, modules) VALUES
  ('t-qihang', '["warehouse","metadata"]');

INSERT INTO user_tenants (user_id, tenant_id, tenant_role) VALUES
  ('李四', 't-qihang', 'admin');

INSERT INTO projects (id, tenant_id, code, name, description, owner, created_at) VALUES
  ('p-empty', 't-qihang', 'default', '默认项目', '新建租户的默认空项目，可在此开始建模', '李四', '2026-08-01');

INSERT INTO appearance_prefs (scope, tenant_id, theme, menu_pos) VALUES
  ('tenant', 't-qihang', 'cyan', 'left');
