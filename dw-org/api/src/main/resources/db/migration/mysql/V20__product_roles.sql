-- 产品角色：把「哪个角色有哪些权限」从硬编码矩阵变成可管理的数据。
--
-- 【这是规范里挂了很久的待办，不是新增设计】docs/product/versions/0.2.0/spec/06-runtime-modes.md
--   :84 「现有 Perms 的 admin/modeler/viewer 只覆盖仓建设，要改成『组织角色 + 产品角色』两段」
--   :99 「产品角色仍按 manifest，存在组织平台（project_id + user_id + product + role）」
--   :115「Perms 按产品 manifest 扩展，禁止再写死三套仓建设角色套所有模块」
-- 本表就是那份「存在组织平台」的角色定义。
--
-- 【为什么不建外键】product_role_perms.role_id 指向 product_roles.id，与 nav_groups 同一
-- 惯例不加约束：这类表是配置数据，删角色时手工删 perms（在 ProductRoleService 里同一事务做），
-- 加外键只会让「先删子再删父」的次序要求泄漏到每个调用点。同时 project_members.role 也
-- 刻意不指向本表 —— 它是自由字符串，历史行、被删角色都仍要能落库。
--
-- 【为什么必须灌种子】升级后判权从「读 Perms 硬编码矩阵」改成「先查本表」。表里如果没有
-- 存量角色码，project_members 里已有的那些行（role = admin/modeler/viewer）会全体判否，
-- 表现为升级完所有人突然没权限了。种子的权限词【逐字照抄 dw-common 的 Perms.java:13-29】，
-- 所以升级前后判定结果完全一致。改这份种子就必须同时改 Perms.java，反之亦然 —— 两边漂移
-- 的后果是「某人权限静默变了」，不报错、不可见。
--
-- 【code 固定用 admin 表示管理角色】不采纳设计稿里的 spec_admin / catalog_admin：
-- AccessService.roleOf 对租户管理员是【短路返回字符串 "admin"】的，改这个码要连带改短路逻辑。
-- 角色码只是身份，label 才是给人看的（「规范管理员」「目录管理员」）。
--
-- (product, code) 是角色的身份；code 的宽度对齐 project_members.role VARCHAR(32)，
-- 判权时两条线用的是同一个字符串，列宽不同会在写入时被截断。
--
-- 方言差异：只有时间类型一处（TIMESTAMP + CURRENT_TIMESTAMP，对应 postgresql/ 那份的
-- TIMESTAMPTZ + now()）。纯建表 + 种子，无 V18 那种 DROP INDEX/DROP CONSTRAINT 的分叉。
-- postgres 那份无测试覆盖，改的时候两份要一起看。
CREATE TABLE product_roles (
  id         VARCHAR(64)  NOT NULL PRIMARY KEY,
  product    VARCHAR(32)  NOT NULL,
  code       VARCHAR(32)  NOT NULL,
  label      VARCHAR(64)  NOT NULL,
  hint       VARCHAR(255) NOT NULL DEFAULT '',
  -- 该产品的管理角色：租户管理员在这个产品里短路映射到它（见 AccessService.roleOf）。
  -- 每个产品【有且仅有一个】TRUE —— 由 ProductRoleService 的不变量校验守着。
  is_admin   BOOLEAN      NOT NULL DEFAULT FALSE,
  -- 内置：不可删、不可改 code。内置三角色是 Perms.java 那份矩阵的投影，删了就没有兜底了。
  builtin    BOOLEAN      NOT NULL DEFAULT FALSE,
  sort_order INT          NOT NULL DEFAULT 0,
  created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_product_role UNIQUE (product, code)
);

-- 角色 → 权限词。权限词是严格两段式「域:动作」，动作只有 read/write/admin/publish/member
-- （定义在 packages/engine/src/iam.ts 与 dw-common 的 Perms.java）。
-- 本表【不校验】权限词合法性 —— 词表由各产品自报（菜单候选的 perms 字段），
-- 校验在 ProductRoleService 写入时做，那里才拿得到「这个产品认哪些词」。
CREATE TABLE product_role_perms (
  role_id VARCHAR(64) NOT NULL,
  perm    VARCHAR(64) NOT NULL,
  PRIMARY KEY (role_id, perm)
);

-- 内置角色种子：逐字照抄 Perms.java 的 WAREHOUSE / METADATA 两张表。
-- id 用确定性字符串而不是 uuid：种子必须可重复、可读，也方便人工核对与回滚。
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
