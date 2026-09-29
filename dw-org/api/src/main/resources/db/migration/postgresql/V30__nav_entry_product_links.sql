-- 入口页的第二类引用：产品清单里的节点。
-- 与 mysql/V30 逐字等价，只差 IF NOT EXISTS。
-- 这一份**没有测试覆盖**（测试跑的是 mysql 那份喂出来的 H2），改这里要人工核对两遍。

-- 为什么不塞进 nav_entry_links 的 target_id / 为什么不给它加列：
-- 那一列是 VARCHAR(64)，而真清单最长的一条 id 是 58 字符，加宽又没有三方言通用写法。详见 mysql/V30。
CREATE TABLE IF NOT EXISTS nav_entry_product_links (
  entry_id   VARCHAR(64)  NOT NULL,
  product    VARCHAR(32)  NOT NULL,
  ref        VARCHAR(128) NOT NULL,
  sort_order INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (entry_id, product, ref)
);
