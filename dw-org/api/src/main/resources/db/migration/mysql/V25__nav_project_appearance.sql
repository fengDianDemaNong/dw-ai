-- 项目壳补一条「外观」（PRD §4 第 7 条：设置是项目壳的最后一个主菜单，外观在它下面）。
--
-- 【为什么落在 org 自有节点上】外观改的是**壳**（主题、菜单栏颜色、菜单风格），
-- 全是 org 自己渲染的东西，与任何产品无关 —— 所以 product = ''、path 直接是 org 的完整路由。
-- 与「成员管理」一样带 {code} 占位符，渲染时由 sysNav.ts 替换成当前项目码（encodeURIComponent 之后）。
--
-- 【admin_only = FALSE】PRD 写「外观全员可改本组织」：观感是每个人自己的事，
-- 不该只有租户管理员能调。与「成员管理」不同，这一条也不挂权限词。
--
-- 【改的是同一份租户外观】页面里用的作用域是 tenant（`AppearancePickers scope="tenant"`），
-- 与工作台「设置」是同一个 `prefs` 状态 —— PRD 说的是「按组织记住」，不是按项目，
-- 所以这个入口是同一设置的第二入口，不是项目级独立存储。
--
-- 【为什么不改 V23】V23 已在既有库上执行过，改一个执行过的迁移文件会让 flyway 的
-- checksum 对不上（同 V24 的先例，见该文件注释）。
--
-- 本文件同时给 H2 用（见 V23 顶部注释：MetaDb 只给 postgresql 配了专属目录），
-- 所以只用 H2 也认的标准写法。
INSERT INTO nav_nodes
  (id, scope, parent_id, title, path, icon, perm, sort_order, enabled, admin_only, product, ref, mounted, empty_policy)
VALUES
  ('nav-proj-appearance', 'project', 'nav-proj', '外观', '/org/project/{code}/settings/nav', 'SettingOutlined', '', 30, TRUE, FALSE, '', '', FALSE, 'hide');
