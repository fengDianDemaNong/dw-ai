-- 工作台「设置」由单页改为「设置 → 三个子页」。
--
-- 【为什么】用户 2026-09-27 看了工作台设置页之后提出：
--   「设置 现在下面内容太多了，拆成设置主菜单+多个子菜单。」
-- 拆成三个子页：外观（主题 / 菜单栏颜色 / 菜单风格）、大模型、AI 提示词
-- （「AI 会改什么」那张只读表并入提示词页 —— 它讲的就是提示词对应哪个接口会写什么数据，
--   分开放反而看不懂）。
--
-- 【这是照抄项目壳既有的结构，不是新发明】V25 给项目壳加的「外观」就是挂在
-- 「设置」下的子节点（见 V25 顶部注释：「设置是项目壳的最后一个主菜单，外观在它下面」）。
-- 工作台这边此前一直没有这一层，这次补齐。
--
-- 【为什么要先 UPDATE 父节点】「设置」原来是**可点的一条路由**（path 指向 /org/workbench/settings）。
-- 现在它要变成**目录**（只负责展开，自己不跳），而 nav_nodes 的约定是
--   path = 空串 → 目录节点，不可点（见 V23 建表注释）
-- 不置空的话它会同时是「能点的一条」和「有子项的一层」，点下去是一个已经不存在了的旧地址。
-- 旧地址本身没坏 —— 前端路由里留了 /org/workbench/settings → 外观页 的重定向。
--
-- 【子节点的 admin_only】与父节点「设置」一致（TRUE）：这三页都是租户管理员面。
--
-- 【sort_order 从 10 起】父节点换了，就没有「跟兄弟节点挤空隙」的问题，直接用 10/20/30。
--
-- 【图标】都取自前端 config/navIcons.ts 的现有 key。**故意不用 SettingOutlined** ——
-- 那是父节点「设置」自己的图标，父子同图标看起来像重复了一项。
--
-- 本文件同时给 H2 用（见 V23 顶部注释：MetaDb 只给 postgresql 配了专属目录），
-- 所以只用 H2 也认的标准写法（UPDATE + INSERT，没有方言特性）。
UPDATE nav_nodes SET path = '' WHERE id = 'nav-sys-settings';

INSERT INTO nav_nodes
  (id, scope, parent_id, title, path, icon, perm, sort_order, enabled, admin_only, product, ref, mounted, empty_policy)
VALUES
  ('nav-sys-settings-appearance', 'workbench', 'nav-sys-settings', '外观',      '/org/workbench/settings/appearance', 'BlockOutlined',   '', 10, TRUE, TRUE, '', '', FALSE, 'hide'),
  ('nav-sys-settings-llm',        'workbench', 'nav-sys-settings', '大模型',    '/org/workbench/settings/llm',        'RobotOutlined',   '', 20, TRUE, TRUE, '', '', FALSE, 'hide'),
  ('nav-sys-settings-prompts',    'workbench', 'nav-sys-settings', 'AI 提示词', '/org/workbench/settings/prompts',    'MessageOutlined', '', 30, TRUE, TRUE, '', '', FALSE, 'hide');
