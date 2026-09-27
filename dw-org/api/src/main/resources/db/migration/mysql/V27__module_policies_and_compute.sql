-- 工作台「模块管理」与「计算资源」两张页面的数据模型。
--
-- 一、模块策略（租户侧的启停 + 可见范围）
--
-- 许可读侧本来是「平台给租户开通了哪些模块」（tenant_licenses.modules），那是**上限**，
-- 只有平台管理员在平台后台里能改。这一列是租户在上限之内的第二层控制：
-- 本组织启用哪些、每个模块给谁看。
--
-- 形状（数组，与服务端的 ModulePolicyDto 逐字对应）：
--   [{"product": "warehouse", "enabled": true, "visibleTo": "all_members"}, ...]
--
-- **故意不回填、不加种子**：NULL 表示「这个租户没配过」，读侧回落默认值
-- （全开 + 原型默认可见范围），于是升级前就存在的租户行为逐字不变。
-- 回填成 '[]' 会多一种「配过但一条策略都没有」的状态，读侧还得多判一次 ——
-- V9 给 ai_caps 回填是因为那边的读侧认「非空数组」，这里不适用。
-- （真要回填也得两步走：MySQL 的 JSON 列不支持表达式默认值。）
ALTER TABLE tenant_licenses ADD COLUMN module_policies JSON NULL;

-- 二、计算资源（租户级的调度器登记 + 引擎启停）
--
-- 与 tenant_llm 同一套路：连接信息按租户存，Token 加密落库、任何回传路径只给 hasToken。
-- 引擎那一列只存 kind + enabled —— 状态是**派生**的（enabled ? 'ok' : 'unconfigured'），
-- 不落库，免得同一件事有两个真相。
CREATE TABLE tenant_compute (
  tenant_id           VARCHAR(64) PRIMARY KEY,
  scheduler_enabled   BOOLEAN     NOT NULL DEFAULT FALSE,
  scheduler_base_url  VARCHAR(512) NOT NULL DEFAULT '',
  scheduler_token_enc BLOB,
  scheduler_status    VARCHAR(16) NOT NULL DEFAULT 'unconfigured',
  scheduler_tested_at TIMESTAMP   NULL,
  -- 「测试连接」的结论原文（成功也写一句，含实际请求地址）。
  -- 没有它，失败时页面只能显示一个「未通过」，管理员无从判断是地址错了、token 过期了，
  -- 还是这台机器根本连不到那个网段 —— 而地址填错正是最常见的一种。
  scheduler_note      VARCHAR(255) NOT NULL DEFAULT '',
  engines             JSON        NULL,
  updated_at          TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT fk_compute_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

-- 三、工作台侧栏的两个新入口
--
-- 挂在「系统管理」（nav-sys）下，与用户管理/角色管理/知识库/设置一样是**租户管理员面**
-- （admin_only = TRUE）。
--
-- sort_order 取 35 / 36：插在「项目管理」(30) 与「知识库」(40) 之间，与原型侧栏的次序一致。
-- **不动已有行的 sort** —— 改别人的顺序会让这次迁移的影响面从「加两条」变成「重排一片」。
INSERT INTO nav_nodes
  (id, scope, parent_id, title, path, icon, perm, sort_order, enabled, admin_only, product, ref, mounted, empty_policy)
VALUES
  ('nav-sys-modules', 'workbench', 'nav-sys', '模块管理', '/org/workbench/modules', 'ApartmentOutlined',   '', 35, TRUE, TRUE, '', '', FALSE, 'hide'),
  ('nav-sys-compute', 'workbench', 'nav-sys', '计算资源', '/org/workbench/compute', 'CloudServerOutlined', '', 36, TRUE, TRUE, '', '', FALSE, 'hide');
