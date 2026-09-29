-- 与 mysql/V28 逐字等价 —— 这条迁移只有 UPDATE + INSERT，没有任何方言特性，
-- 所以两份文件的 SQL 完全一样，只差这段头注释。
-- 这一份**没有测试覆盖**（测试跑的是 mysql 那份喂出来的 H2），改这里要人工核对两遍。
--
-- 一、「设置」由可点的一条路由改为目录节点（path 置空 = 不可点，只负责展开）。
--     旧地址 /org/workbench/settings 仍可用，由前端路由重定向到「外观」子页。
--     子节点的 admin_only 与父节点一致（TRUE，租户管理员面）。
UPDATE nav_nodes SET path = '' WHERE id = 'nav-sys-settings';

INSERT INTO nav_nodes
  (id, scope, parent_id, title, path, icon, perm, sort_order, enabled, admin_only, product, ref, mounted, empty_policy)
VALUES
  ('nav-sys-settings-appearance', 'workbench', 'nav-sys-settings', '外观',      '/org/workbench/settings/appearance', 'BlockOutlined',   '', 10, TRUE, TRUE, '', '', FALSE, 'hide'),
  ('nav-sys-settings-llm',        'workbench', 'nav-sys-settings', '大模型',    '/org/workbench/settings/llm',        'RobotOutlined',   '', 20, TRUE, TRUE, '', '', FALSE, 'hide'),
  ('nav-sys-settings-prompts',    'workbench', 'nav-sys-settings', 'AI 提示词', '/org/workbench/settings/prompts',    'MessageOutlined', '', 30, TRUE, TRUE, '', '', FALSE, 'hide');
