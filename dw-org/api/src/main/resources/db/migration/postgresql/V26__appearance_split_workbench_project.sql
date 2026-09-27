-- 工作台壳与项目壳各自一套外观（原先两者共用 scope='tenant' 那一行）。
--
-- 【不需要 DDL】appearance_prefs 的主键已经是 (scope, tenant_id)（见 V1__schema.sql），
-- 所以拆壳只是多两个 scope 取值 —— 本文件是一条数据迁移：把存量那份复制成
-- workbench 与 project 两行，两套都继承它（裁定：升级后观感不变）。
--
-- 【scope='tenant' 的旧行保留不删】dw-model 前端独立打开时读的就是它：
-- dw-model/ui/src/api/client.ts 打 /api/tenants/{id}/appearance 且不带 shell 参数，
-- 服务端把「不带 shell」回落到老口径 scope='tenant'。删了那个产品会静默回落到默认值。
--
-- 【先 project 后 workbench】两条独立语句，各自都限定 WHERE scope='tenant'，
-- 不会读到本轮刚插入的新行。顺序无依赖。
--
-- 【workbench 把 drawer 归一成 left】工作台壳没有项目壳那条顶栏，而抽屉是
-- absolute + top:100% 挂在顶栏下面的（SystemLayout.vue 里的折算）—— 库里的值必须与
-- 页面上的有效值一致，否则工作台「设置 → 外观」的菜单风格 picker 会一项都不高亮。
-- 反过来 project 那份原样保留：drawer 正是项目壳的默认风格。
--
-- 【与 mysql/ 那份逐字相同】这一段没有方言差异（字符串字面量 + CASE WHEN + INSERT ... SELECT
-- 三家通用）。postgresql/ 目录没有测试兜底（测试跑的是 H2 + mysql/ 那份），
-- 所以改动这里时务必同步另一份。
INSERT INTO appearance_prefs (scope, tenant_id, theme, menu_pos, menu_color)
SELECT 'project', tenant_id, theme, menu_pos, menu_color
FROM appearance_prefs WHERE scope = 'tenant';

INSERT INTO appearance_prefs (scope, tenant_id, theme, menu_pos, menu_color)
SELECT 'workbench', tenant_id, theme,
       CASE WHEN menu_pos = 'drawer' THEN 'left' ELSE menu_pos END,
       menu_color
FROM appearance_prefs WHERE scope = 'tenant';
