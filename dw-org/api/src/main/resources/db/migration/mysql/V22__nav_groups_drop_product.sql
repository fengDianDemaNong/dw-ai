-- 分组不再绑产品：一个分组是**壳**的分组，里面可以同时有仓建设和数据地图的菜单。
--
-- 【为什么要拆】nav_groups 自 V19 起把 product 当成分组身份的一部分
-- （唯一键 (scope, product, title)），但**渲染侧从来不按产品分桶**：
-- 前端 sysNav.ts 的 groupMenus 只按 group_title 聚合，同名还要合并
-- （组间顺序取最小、空组策略 always 优先）。也就是说「一个组混装两个产品的菜单」
-- 在渲染上本来就是支持的，绑产品纯粹是管理面自己加的约束 ——
-- 结果是管理员想建一个跨产品的组，只能建成两行，管理页看起来还像两个组。
--
-- 【为什么是重建表而不是 DROP COLUMN】旧模型允许「同壳同名、不同产品各一行」，
-- 去掉 product 后它们会撞新唯一键。所以必须先按 (scope, title) 归并，再建约束。
-- 归并口径与前端 groupMenus 的同名合并**逐字一致**（顺序取最小、always 优先），
-- 所以升级后侧栏观感不变，只是管理页里少掉那几行重复的。
--
-- 【顺序不能换】新表**先不带唯一约束**：postgres 与 H2 的索引名是 schema 级全局的，
-- 带着 uk_nav_group 建新表会和旧表上同名的那个索引直接撞（MySQL 是表级，撞不到，
-- 但三份语法要一致）。所以留到最后一步再补约束。
--
-- mounted 取「或」：只要有一行是挂载的，合并后这一组仍是挂载的 —— 挂载是
-- 「内容由产品在渲染时提供」，丢了这个标记等于静默把功能关掉。
--
-- 这份同时给 H2 用（见 V17 顶部注释 —— MetaDb.flywayLocation 只有 postgresql
-- 有专属目录），所以写法要 H2 也认：不用 IF NOT EXISTS；用 MAX(CASE WHEN ...)
-- 而不是 BOOL_OR（后者 MySQL/H2 都没有）。
CREATE TABLE nav_groups_v22 (
  id           VARCHAR(64)  NOT NULL PRIMARY KEY,
  scope        VARCHAR(16)  NOT NULL,
  title        VARCHAR(64)  NOT NULL,
  sort_order   INT          NOT NULL DEFAULT 0,
  empty_policy VARCHAR(16)  NOT NULL DEFAULT 'hide',
  mounted      BOOLEAN      NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO nav_groups_v22 (id, scope, title, sort_order, empty_policy, mounted, created_at)
SELECT MIN(id), scope, title, MIN(sort_order),
       -- always 优先：它是对用户承诺更强的那一个（组始终出现、置灰说明），
       -- 合并时取更弱的 hide 会让一个本该可见的空组消失
       CASE WHEN MAX(CASE WHEN empty_policy = 'always' THEN 1 ELSE 0 END) = 1
            THEN 'always' ELSE 'hide' END,
       CASE WHEN MAX(CASE WHEN mounted THEN 1 ELSE 0 END) = 1
            THEN TRUE ELSE FALSE END,
       MIN(created_at)
FROM nav_groups
GROUP BY scope, title;

DROP TABLE nav_groups;

ALTER TABLE nav_groups_v22 RENAME TO nav_groups;

-- 分组身份：同一个壳下，分组名唯一。产品不再是它的一部分。
ALTER TABLE nav_groups ADD CONSTRAINT uk_nav_group UNIQUE (scope, title);
