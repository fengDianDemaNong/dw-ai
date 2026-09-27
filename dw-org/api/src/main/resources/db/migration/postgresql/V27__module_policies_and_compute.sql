-- 工作台「模块管理」与「计算资源」两张页面的数据模型。
-- 与 mysql/V27 逐字等价，只差方言（JSON->JSONB、BLOB->BYTEA、TIMESTAMP->TIMESTAMPTZ）。
-- 这一份**没有测试覆盖**（测试跑的是 mysql 那份喂出来的 H2），改这里要人工核对两遍。

-- 一、模块策略（租户侧的启停 + 可见范围）。NULL = 没配过 -> 读侧回落默认值。
ALTER TABLE tenant_licenses ADD COLUMN IF NOT EXISTS module_policies JSONB NULL;

-- 二、计算资源（租户级的调度器登记 + 引擎启停）。Token 加密落库，回传只给 hasToken。
CREATE TABLE IF NOT EXISTS tenant_compute (
  tenant_id           VARCHAR(64) PRIMARY KEY,
  scheduler_enabled   BOOLEAN      NOT NULL DEFAULT FALSE,
  scheduler_base_url  VARCHAR(512) NOT NULL DEFAULT '',
  scheduler_token_enc BYTEA,
  scheduler_status    VARCHAR(16)  NOT NULL DEFAULT 'unconfigured',
  scheduler_tested_at TIMESTAMPTZ,
  scheduler_note      VARCHAR(255) NOT NULL DEFAULT '',
  engines             JSONB,
  updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT fk_compute_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE
);

-- 三、工作台侧栏的两个新入口（挂在「系统管理」下，租户管理员面）。
INSERT INTO nav_nodes
  (id, scope, parent_id, title, path, icon, perm, sort_order, enabled, admin_only, product, ref, mounted, empty_policy)
VALUES
  ('nav-sys-modules', 'workbench', 'nav-sys', '模块管理', '/org/workbench/modules', 'ApartmentOutlined',   '', 35, TRUE, TRUE, '', '', FALSE, 'hide'),
  ('nav-sys-compute', 'workbench', 'nav-sys', '计算资源', '/org/workbench/compute', 'CloudServerOutlined', '', 36, TRUE, TRUE, '', '', FALSE, 'hide');
