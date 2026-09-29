-- 入口页的 Tab 名称与 Tab 顺序。
-- 与 mysql/V31 逐字等价，只差 IF NOT EXISTS。理由（含为什么不回填 sort_order）见那一份。
-- 这一份**没有测试覆盖**（测试跑的是 mysql 那份喂出来的 H2），改这里要人工核对两遍。
ALTER TABLE nav_entry_links ADD COLUMN IF NOT EXISTS label      VARCHAR(64) NULL;
ALTER TABLE nav_entry_links ADD COLUMN IF NOT EXISTS sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE nav_entry_product_links ADD COLUMN IF NOT EXISTS label VARCHAR(64) NULL;
