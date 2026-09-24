-- 项目角色加产品维。
--
-- 同一人在同一项目里，在仓建设与数据地图可以是不同角色（PRD §3「产品层」：
-- 仓建设是规范管理员/建模工程师/只读，数据地图是目录管理员/血缘分析/只读）。
-- 所以 (project_id, user_id) 这个主键不够了，得把 product 加进去。
--
-- 存量行一律回填 'warehouse'：三元组时代只有仓建设在用这张表，数据地图的角色
-- 从来没被派过。不给存量行凭空补一条 metadata —— 那等于替管理员做决定，
-- 而他很可能并不想让这些人在数据地图里也有角色。
--
-- DEFAULT 'warehouse' 是过渡垫：让还没跟上 product 的旧写入不至于立刻炸。
-- 新代码一律显式传 product（见 AccessService.roleOf / ProjectService.upsertMember）。

ALTER TABLE project_members ADD COLUMN product VARCHAR(32) NOT NULL DEFAULT 'warehouse';

ALTER TABLE project_members DROP CONSTRAINT project_members_pkey;
ALTER TABLE project_members ADD PRIMARY KEY (project_id, user_id, product);
