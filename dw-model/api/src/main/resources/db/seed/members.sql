-- 产品维写死 'warehouse'：本进程只服务仓建设，不会有别的产品的行进来。
-- 列是显式写出来的（而不是靠 DEFAULT），为了让「这张表已经带产品维」一眼可见。
--
-- 组织平台的同名文件是每个项目 warehouse + metadata 两行（它两个产品都服务）——
-- 那是服务角色差异，不是「这里漏了 metadata」。改成一样才是破坏设计，参见 ADR-0008。
--
-- 只有 p-trade，没有 p-empty。
--
-- p-empty 属于第二个租户 t-qihang，而那个租户只在 multi 下存在；multi 下本模块又
-- 整份不执行（身份由组织扇出，见 SeedMain.plan）。也就是说 p-empty 这一行在任何
-- 模式下都落不了地 —— 留着它只会在 standard 下撞 projects 的外键（真机实测：
-- Referential integrity constraint violation fk_pm_proj）。
--
-- 前置：identity.sql 已跑过（它负责清空 project_members）。本文件只追加。

INSERT INTO project_members (project_id, user_id, product, role) VALUES
  ('p-trade', '张三', 'warehouse', 'admin'),
  ('p-trade', '李四', 'warehouse', 'modeler');
