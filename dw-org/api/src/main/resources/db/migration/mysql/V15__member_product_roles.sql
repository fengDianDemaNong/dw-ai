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
--
-- 这份同时给 H2 用 —— MetaDb.flywayLocation 只有 postgresql 有专属目录，
-- 其余（含测试里的 H2 MODE=MySQL）全落到这里。所以写法要 H2 也认。

ALTER TABLE project_members ADD COLUMN product VARCHAR(32) NOT NULL DEFAULT 'warehouse';

-- 这两条索引不是给查询用的，是给下面 DROP PRIMARY KEY 让路。
--
-- project_members 上有 fk_pm_proj(project_id) 与 fk_pm_user(user_id) 两条外键，
-- InnoDB 靠主键的最左前缀给它们当索引（project_id 是主键第一列，user_id 靠
-- 主键前两列）。直接 DROP PRIMARY KEY 会抛：
--   SQLException: Cannot drop index 'PRIMARY': needed in a foreign key constraint
-- 先补两条单列索引，外键就有替代索引可用，主键才删得掉。
--
-- 不用 `DROP FOREIGN KEY` + 事后重建外键那套：那是 MySQL 专属语法，H2 不认
-- （见文件顶部「写法要 H2 也认」）。CREATE INDEX 是标准 SQL，两边通用。
CREATE INDEX idx_pm_project ON project_members (project_id);
CREATE INDEX idx_pm_user ON project_members (user_id);

ALTER TABLE project_members DROP PRIMARY KEY;
ALTER TABLE project_members ADD PRIMARY KEY (project_id, user_id, product);
