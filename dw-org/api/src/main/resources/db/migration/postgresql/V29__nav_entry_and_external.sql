-- 菜单功能扩展：入口页（引用式容器）+ 外链菜单。
-- 与 mysql/V29 逐字等价，只差方言（BLOB->BYTEA）与 IF NOT EXISTS。
-- 这一份**没有测试覆盖**（测试跑的是 mysql 那份喂出来的 H2），改这里要人工核对两遍。

-- 两类新节点（见 mysql/V29 的详细注释）：
--   entry_page = TRUE  → 入口页；external_url != '' → 外链菜单。两者互斥、都与 product 互斥。
ALTER TABLE nav_nodes ADD COLUMN IF NOT EXISTS entry_page     BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE nav_nodes ADD COLUMN IF NOT EXISTS external_url   VARCHAR(512) NOT NULL DEFAULT '';
ALTER TABLE nav_nodes ADD COLUMN IF NOT EXISTS open_mode      VARCHAR(16)  NOT NULL DEFAULT 'jump';
ALTER TABLE nav_nodes ADD COLUMN IF NOT EXISTS auth_mode      VARCHAR(16)  NOT NULL DEFAULT 'none';
ALTER TABLE nav_nodes ADD COLUMN IF NOT EXISTS token_enc      BYTEA        NULL;
ALTER TABLE nav_nodes ADD COLUMN IF NOT EXISTS basic_user     VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE nav_nodes ADD COLUMN IF NOT EXISTS basic_pass_enc BYTEA        NULL;
ALTER TABLE nav_nodes ADD COLUMN IF NOT EXISTS visibility     VARCHAR(16)  NOT NULL DEFAULT 'all';

-- 入口页挂了哪些菜单（引用关系，多对多；不设 sort_order，见 mysql/V29 的注释）。
CREATE TABLE IF NOT EXISTS nav_entry_links (
  entry_id  VARCHAR(64) NOT NULL,
  target_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (entry_id, target_id)
);
