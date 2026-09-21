-- 组织平台演示数据。密码：张三/李四/王五 = 123456；admin = admin123
DELETE FROM platform_access WHERE tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM tenant_grants WHERE id = 'g-xinghe-demo';
DELETE FROM appearance_prefs WHERE (scope = 'platform' AND tenant_id = '') OR tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM tenant_llm WHERE tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM user_tenants WHERE user_id IN ('张三','李四','王五','admin');
DELETE FROM tenant_licenses WHERE tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM project_members WHERE project_id IN ('p-trade','p-empty');
DELETE FROM projects WHERE id IN ('p-trade','p-empty');
DELETE FROM users WHERE id IN ('张三','李四','王五','admin');
DELETE FROM tenants WHERE id IN ('t-xinghe','t-qihang');

INSERT INTO tenants (id, code, name, owner, status) VALUES
  ('t-xinghe', 'xinghe', '星河电商', '张三', 'active'),
  ('t-qihang', 'qihang', '启航科技', '李四', 'active');

INSERT INTO tenant_licenses (tenant_id, modules) VALUES
  ('t-xinghe', '["warehouse","metadata"]'),
  ('t-qihang', '["warehouse","metadata"]');

INSERT INTO users (id, username, display_name, password_hash, status, platform_admin) VALUES
  ('admin', 'admin', '平台管理员', '$2y$10$cYrUk2/NqZSloOksxBg1Tu3x8PRLLsxZOvPeR3FfiWuuCgLXTNA1G', 'active', TRUE),
  ('张三', '张三', '张三', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE),
  ('李四', '李四', '李四', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE),
  ('王五', '王五', '王五', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE);

INSERT INTO user_tenants (user_id, tenant_id, tenant_role) VALUES
  ('张三', 't-xinghe', 'admin'),
  ('李四', 't-xinghe', 'member'),
  ('李四', 't-qihang', 'admin'),
  ('王五', 't-xinghe', 'member');

INSERT INTO projects (id, tenant_id, code, name, description, owner, created_at) VALUES
  ('p-trade', 't-xinghe', 'trade_dw', '交易数仓', '覆盖订单、支付、退款全链路的主题数仓与指标服务', '张三', '2026-01-12'),
  ('p-empty', 't-qihang', 'default', '默认项目', '新建租户的默认空项目，可在此开始建模', '李四', '2026-08-01');

INSERT INTO project_members (project_id, user_id, role) VALUES
  ('p-trade', '张三', 'admin'),
  ('p-trade', '李四', 'modeler'),
  ('p-empty', '李四', 'admin');

INSERT INTO appearance_prefs (scope, tenant_id, theme, menu_pos) VALUES
  ('platform', '', 'cyan', 'left'),
  ('tenant', 't-xinghe', 'cyan', 'left'),
  ('tenant', 't-qihang', 'cyan', 'left');

INSERT INTO tenant_grants (id, tenant_id, code, kind, expires_at, created_by, modules, project_ids, project_roles) VALUES
  ('g-xinghe-demo', 't-xinghe', 'XINGHE-DEMO', 'permanent', NULL, '张三', '[]', '[]', '{}');
