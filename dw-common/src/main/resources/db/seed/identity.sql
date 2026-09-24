-- 演示身份层：租户 / 账号 / 项目 / 授权码。dw-org 与 dw-model 共用这一份。
-- 三种后端（H2 / MySQL / PostgreSQL）逐字节同构，所以不分方言 —— H2 以 MODE=MySQL 跑。
--
-- 口令：admin / 张三 / 李四 / 王五 的密码都是 123456。
--
-- 这里只写「单租户演示」需要的部分（星河电商 t-xinghe 与它的项目 trade_dw）。
-- 第二个租户「启航科技」在 identity-multi.sql 里，只有 multi 模式才执行。
--
-- 关于隐含租户 t-xinghe：服务空库首次启动时会由 WarehouseLocalSeedRunner 补一行
-- code='local' / name='本环境' 的同 id 租户作兜底。本脚本把它删掉重建为
-- code='xinghe' / name='星河电商'，许可也从 ["warehouse"] 换成 ["warehouse","metadata"]。
-- 同一个 id 的两种身份，中间态是刻意的：先保证服务能起来，再被演示数据覆盖。
-- 最终态只有本脚本这一套。

-- DELETE 的顺序不可交换：子表在前，被引用的表在后。
--
-- 注意 WHERE 同时覆盖 t-qihang，而下面只 INSERT t-xinghe —— 这不是漏插。
-- 为的是「从 multi 降回 standard 后重跑」时，qihang 的残留也能被清掉；
-- 否则会留下一批本模式下永远看不见、却仍占着唯一键的数据。
DELETE FROM platform_access WHERE tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM tenant_grants WHERE id = 'g-xinghe-demo';
DELETE FROM appearance_prefs WHERE (scope = 'platform' AND tenant_id = '') OR tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM tenant_llm WHERE tenant_id IN ('t-xinghe','t-qihang');
DELETE FROM user_tenants WHERE user_id IN ('张三','李四','王五','admin');
DELETE FROM tenant_licenses WHERE tenant_id IN ('t-xinghe','t-qihang');
-- project_members 只在这里 DELETE、不在任何地方 INSERT：
-- 它是「服务角色差异」—— 组织平台每个项目写 warehouse + metadata 两行，
-- 仓建设只写 warehouse。各模块的 members.sql 自己 INSERT，这里只负责先清干净。
DELETE FROM project_members WHERE project_id IN ('p-trade','p-empty');
DELETE FROM projects WHERE id IN ('p-trade','p-empty');
DELETE FROM users WHERE id IN ('张三','李四','王五','admin');
DELETE FROM tenants WHERE id IN ('t-xinghe','t-qihang');

INSERT INTO tenants (id, code, name, owner, status) VALUES
  ('t-xinghe', 'xinghe', '星河电商', '张三', 'active');

INSERT INTO tenant_licenses (tenant_id, modules) VALUES
  ('t-xinghe', '["warehouse","metadata"]');

INSERT INTO users (id, username, display_name, password_hash, status, platform_admin) VALUES
  ('admin', 'admin', '平台管理员', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', TRUE),
  ('张三', '张三', '张三', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE),
  ('李四', '李四', '李四', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE),
  ('王五', '王五', '王五', '$2y$10$vDfZnqbRbHbHuRWRmOj19uZeYE0yjfYaAALyWHW7VqgnbN/dmXTE2', 'active', FALSE);

INSERT INTO user_tenants (user_id, tenant_id, tenant_role) VALUES
  ('张三', 't-xinghe', 'admin'),
  ('李四', 't-xinghe', 'member'),
  ('王五', 't-xinghe', 'member');

INSERT INTO projects (id, tenant_id, code, name, description, owner, created_at) VALUES
  ('p-trade', 't-xinghe', 'trade_dw', '交易数仓', '覆盖订单、支付、退款全链路的主题数仓与指标服务', '张三', '2026-01-12');

INSERT INTO appearance_prefs (scope, tenant_id, theme, menu_pos) VALUES
  ('platform', '', 'cyan', 'left'),
  ('tenant', 't-xinghe', 'cyan', 'left');

INSERT INTO tenant_grants (id, tenant_id, code, kind, expires_at, created_by, modules, project_ids, project_roles) VALUES
  ('g-xinghe-demo', 't-xinghe', 'XINGHE-DEMO', 'permanent', NULL, '张三', '[]', '[]', '{}');
