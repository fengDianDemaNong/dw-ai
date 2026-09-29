-- 租户侧的模块策略 —— 与 dw-org 的 V27 是同一列。理由见 mysql/ 那一份的注释。
--
-- 注意：**这一份没有测试覆盖**（测试跑的是 mysql/ 那份喂给 H2），改这里要人工核对两遍。
ALTER TABLE tenant_licenses ADD COLUMN IF NOT EXISTS module_policies JSONB NULL;
