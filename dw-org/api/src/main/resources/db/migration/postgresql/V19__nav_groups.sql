-- 门户菜单分组：让平台管理员能把「有哪些分组」提前建好，而不是每次配菜单都手打分组名。
--
-- 【这是一张软约束表，不建外键】nav_items.group_title 仍然是自由字符串，本表既不
-- 被它引用、也不约束它。原因：仓建设的「建模中心」分组是按项目分层动态生成的
-- （见 docs/product/versions/0.2.0/spec/01-spec-center.md），天生进不了静态分组表；
-- 一旦加外键，那种分组名就再也落不了库了。
--
-- 所以本表只提供三样东西：
--   1. 配菜单时的下拉候选（省去手打、避免同义异名）；
--   2. 组【间】顺序（组内顺序仍由 nav_items.sort_order 决定）；
--   3. 空组策略 —— 一个分组在「当前人一个可用菜单都没有」时是整组隐藏还是
--      保留并置灰说明。规范里两种都已写死：数据地图要「分组始终出现，未开通或
--      未派角色时禁用并说明」（00-platform.md），启航未配则「无调度分组」
--      （04-requirements.md），此前无处落地。
--
-- 【默认 'hide' 是刻意的】现状就是「空分组不渲染」；新表默认值必须与现状一致，
-- 否则升级后存量行为会变。要「始终出现」必须管理员显式声明。
--
-- (scope, product) 是分组的归属维度，与 nav_items 的同名列同一套取值；
-- title 的宽度对齐 nav_items.group_title，改名级联时不会因列宽不同被截断。
--
-- 方言差异：只有时间类型一处（TIMESTAMPTZ + now()，对应 mysql/ 那份的
-- TIMESTAMP + CURRENT_TIMESTAMP）。纯建表，没有 V18 那种 DROP INDEX/DROP CONSTRAINT
-- 的分叉。postgres 这份无测试覆盖，改的时候两份要一起看。
CREATE TABLE nav_groups (
  id           VARCHAR(64)  NOT NULL PRIMARY KEY,
  scope        VARCHAR(16)  NOT NULL,
  product      VARCHAR(32)  NOT NULL,
  title        VARCHAR(64)  NOT NULL,
  sort_order   INT          NOT NULL DEFAULT 0,
  empty_policy VARCHAR(16)  NOT NULL DEFAULT 'hide',
  created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
  -- 同一个壳下同一个产品里，分组名就是它的身份（前端的下拉候选按这个维度查）
  CONSTRAINT uk_nav_group UNIQUE (scope, product, title)
);
