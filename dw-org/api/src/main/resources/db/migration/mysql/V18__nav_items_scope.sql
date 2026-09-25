-- 门户菜单加「挂哪个壳」等三列：工作台壳与项目壳各挂一批。
--
-- scope 是本次新增的核心。以前 nav_items 只有一份菜单，全部挂在工作台壳上；
-- 现在工作台（/org/workbench/*）与项目（/org/project/{code}/*）是两个壳，
-- 同一条菜单项要能分别指定挂哪个。默认 'workbench' 让存量行零回填 ——
-- 它们本来就是工作台级的产品入口。
--
-- group_title 是侧栏分组标题（数据地图、规范中心…）。各服务的菜单天然分组，
-- 丢了分组侧栏会变成一长条。
--
-- perm 是权限词（catalog:read…），供壳里做「留在原地但置灰」——
-- 与 dw-lineage/ui/src/config/nav.ts 记的既有原则一致：权限不够时这一项还在，
-- 只是置灰，因为一个空侧栏会让人以为服务坏了。
--
-- 【唯一约束必须改】旧的 uk_nav_product_path (product, path) 要把同一条子应用路径
-- 判成重复，但它既可能挂项目壳（数据地图六页），也可能需要一个工作台级入口
-- （如「数据地图设置」）。带上 scope 之后两者可以共存。
--
-- 这份同时给 H2 用（见 V17 顶部注释 —— MetaDb.flywayLocation 只有 postgresql
-- 有专属目录），所以写法要 H2 也认：不用 IF NOT EXISTS。
ALTER TABLE nav_items ADD COLUMN scope       VARCHAR(16) NOT NULL DEFAULT 'workbench';
ALTER TABLE nav_items ADD COLUMN group_title VARCHAR(64) NOT NULL DEFAULT '';
ALTER TABLE nav_items ADD COLUMN perm        VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE nav_items DROP INDEX uk_nav_product_path;
ALTER TABLE nav_items ADD CONSTRAINT uk_nav_scope_product_path UNIQUE (scope, product, path);
