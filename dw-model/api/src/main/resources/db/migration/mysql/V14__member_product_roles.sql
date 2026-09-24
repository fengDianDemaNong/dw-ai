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
--
-- 这份同时给 H2 用 —— MetaDb.flywayLocation 只有 postgresql 有专属目录，
-- 其余（含测试里的 H2 MODE=MySQL）全落到这里。所以写法要 H2 也认。

ALTER TABLE project_members ADD COLUMN product VARCHAR(32) NOT NULL DEFAULT 'warehouse';

-- 这两条索引不是给查询用的，是给下面 DROP PRIMARY KEY 让路。
--
-- project_members 上有 fk_pm_proj(project_id) 与 fk_pm_user(user_id) 两条外键
-- （见 V1__schema.sql:52），InnoDB 靠主键的最左前缀给它们当索引。直接 DROP PRIMARY KEY
-- 会抛：Cannot drop index 'PRIMARY': needed in a foreign key constraint
-- 先补两条单列索引，外键就有替代索引可用，主键才删得掉。
--
-- 不用 `DROP FOREIGN KEY` + 事后重建外键那套：那是 MySQL 专属语法，H2 不认
-- （见文件顶部「写法要 H2 也认」）。CREATE INDEX 是标准 SQL，两边通用。
CREATE INDEX idx_pm_project ON project_members (project_id);
CREATE INDEX idx_pm_user ON project_members (user_id);

ALTER TABLE project_members DROP PRIMARY KEY;
ALTER TABLE project_members ADD PRIMARY KEY (project_id, user_id, product);
