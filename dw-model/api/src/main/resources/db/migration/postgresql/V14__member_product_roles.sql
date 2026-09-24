-- 项目角色加产品维。
--
-- 与组织侧（dw-org V15）同一件事、同一份口径：同一人在同一项目里，在仓建设与
-- 数据地图可以是不同角色，所以 (project_id, user_id) 这个主键不够了。
--
-- 本进程只服务仓建设，写进来的行 product 恒为 'warehouse'，看起来加这一列没什么用。
-- 但 standard 模式下这张表是本库真源、multi 模式下它是组织的镜像，两种情形都必须
-- 与组织的表结构对得上 —— 主键不一致会让镜像同步在「同一个人两个产品」时打架。
--
-- 存量行一律回填 'warehouse'：三元组时代没有别的产品写过这张表。
-- DEFAULT 'warehouse' 是过渡垫，新代码一律显式传 product。

ALTER TABLE project_members ADD COLUMN product VARCHAR(32) NOT NULL DEFAULT 'warehouse';

ALTER TABLE project_members DROP CONSTRAINT project_members_pkey;
ALTER TABLE project_members ADD PRIMARY KEY (project_id, user_id, product);
