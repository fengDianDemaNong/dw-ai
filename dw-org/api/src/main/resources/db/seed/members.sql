-- 项目成员 = (项目, 用户, 产品) 一行一角色。同一个人在同一项目里，两个产品下的角色
-- 可以不同 —— 仓建设的「建模工程师」在数据地图就是「血缘分析」。
--
-- 王五刻意不出现在这里：他看得见数据地图分组，但入口是禁用状态（未派角色）。
-- 这是 PRD 的验收场景，删掉这几行就没有反例了。
--
-- 组织平台两个产品都服务，所以每个项目写 warehouse + metadata 两行。
-- 仓建设的同名文件只有 warehouse（它那个进程只服务仓建设）—— 这是服务角色差异，
-- 不是「忘了同步」，别把两边改成一样。参见 ADR-0008。
--
-- 前置：identity.sql 已跑过（它负责清空 project_members）。本文件只追加。

INSERT INTO project_members (project_id, user_id, product, role) VALUES
  ('p-trade', '张三', 'warehouse', 'admin'),
  ('p-trade', '张三', 'metadata',  'admin'),
  ('p-trade', '李四', 'warehouse', 'modeler'),
  ('p-trade', '李四', 'metadata',  'modeler'),
  ('p-empty', '李四', 'warehouse', 'admin'),
  ('p-empty', '李四', 'metadata',  'admin');
