-- 产品角色：把「哪个角色有哪些权限」从硬编码矩阵变成可管理的数据。
--
-- 详细理由（规范出处 06-runtime-modes.md:84/99/115、为什么不建外键、为什么必须灌种子、
-- 为什么 code 固定用 admin）都写在 mysql/ 那份的顶部注释里，本份不再重复 ——
-- 两份的注释刻意保持一致，读一份即可。
--
-- 方言差异：只有时间类型一处（TIMESTAMPTZ + now()，对应 mysql/ 那份的
-- TIMESTAMP + CURRENT_TIMESTAMP）。本份【无测试覆盖】（测试走 H2 + mysql/ 那份），
-- 改动需人工核方言：BOOLEAN 的 TRUE/FALSE 字面量两种方言都认，不受影响。
CREATE TABLE product_roles (
  id         VARCHAR(64)  NOT NULL PRIMARY KEY,
  product    VARCHAR(32)  NOT NULL,
  code       VARCHAR(32)  NOT NULL,
  label      VARCHAR(64)  NOT NULL,
  hint       VARCHAR(255) NOT NULL DEFAULT '',
  is_admin   BOOLEAN      NOT NULL DEFAULT FALSE,
  builtin    BOOLEAN      NOT NULL DEFAULT FALSE,
  sort_order INT          NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
  CONSTRAINT uk_product_role UNIQUE (product, code)
);

CREATE TABLE product_role_perms (
  role_id VARCHAR(64) NOT NULL,
  perm    VARCHAR(64) NOT NULL,
  PRIMARY KEY (role_id, perm)
);

-- 内置角色种子：逐字照抄 Perms.java 的 WAREHOUSE / METADATA 两张表（见 mysql/ 那份）。
INSERT INTO product_roles (id, product, code, label, hint, is_admin, builtin, sort_order) VALUES
  ('prole-warehouse-admin',  'warehouse', 'admin',   '规范管理员', '改规范、建模与发布',                    TRUE,  TRUE, 0),
  ('prole-warehouse-modeler','warehouse', 'modeler', '建模工程师', '规范只读，建模可写',                    FALSE, TRUE, 10),
  ('prole-warehouse-viewer', 'warehouse', 'viewer',  '只读',       '规范与模型只读',                        FALSE, TRUE, 20),
  ('prole-metadata-admin',   'metadata',  'admin',   '目录管理员', '目录、数据目录、临时表规则',            TRUE,  TRUE, 0),
  ('prole-metadata-modeler', 'metadata',  'modeler', '血缘分析',   '解析 SQL、看图；不能改目录规则',        FALSE, TRUE, 10),
  ('prole-metadata-viewer',  'metadata',  'viewer',  '只读',       '只看目录与血缘图',                      FALSE, TRUE, 20);

INSERT INTO product_role_perms (role_id, perm) VALUES
  -- warehouse / admin
  ('prole-warehouse-admin', 'spec:read'),
  ('prole-warehouse-admin', 'spec:write'),
  ('prole-warehouse-admin', 'model:read'),
  ('prole-warehouse-admin', 'model:write'),
  ('prole-warehouse-admin', 'model:publish'),
  ('prole-warehouse-admin', 'iam:member'),
  -- warehouse / modeler
  ('prole-warehouse-modeler', 'spec:read'),
  ('prole-warehouse-modeler', 'model:read'),
  ('prole-warehouse-modeler', 'model:write'),
  -- warehouse / viewer
  ('prole-warehouse-viewer', 'spec:read'),
  ('prole-warehouse-viewer', 'model:read'),
  -- metadata / admin
  ('prole-metadata-admin', 'catalog:read'),
  ('prole-metadata-admin', 'lineage:read'),
  ('prole-metadata-admin', 'lineage:write'),
  ('prole-metadata-admin', 'catalog:admin'),
  -- metadata / modeler
  ('prole-metadata-modeler', 'catalog:read'),
  ('prole-metadata-modeler', 'lineage:read'),
  ('prole-metadata-modeler', 'lineage:write'),
  -- metadata / viewer
  ('prole-metadata-viewer', 'catalog:read'),
  ('prole-metadata-viewer', 'lineage:read');
