-- 入口页的**第二类引用**：产品清单里的节点（V29 的 nav_entry_links 只装得下 nav_nodes.id）。
--
-- 【为什么另起一张表，而不是给 nav_entry_links 加列或加宽】
--
-- 1. `target_id` 是 VARCHAR(64)，装不下清单 id。实测仓内真清单最长的一条是
--    `metadata:workbench:/lineage/workbench/settings/preferences` = 58 字符，
--    距 64 只差 6 个 —— 产品路径再深一点就是 MySQL 8 严格模式下的
--    「Data too long for column」（500），而不是一句干净的 400。
--    而加宽它**没有三方言通用写法**：MySQL 是 `MODIFY COLUMN`，H2 2.x 是
--    `ALTER COLUMN … SET DATA TYPE`，互相不认 —— 而 mysql/ 这一份要**同时**喂
--    H2（测试）与真 MySQL 8（见 V17 顶部注释）。
-- 2. 清单节点的 id 自己就带产品码前缀（`warehouse:project:group:规范中心`），
--    但那是**产品的约定**，不是本服务的契约。显式存 product 列，读的时候就不必去
--    解析那个前缀（`GenMenu` 改一次生成规则，解析式写法会静默失配）。
-- 3. 两类引用的语义不同：nav_nodes 那类的顺序**跟随 target 自己**（V29 刻意不加
--    sort_order），而清单节点在 nav_nodes 里根本没有行 —— 没有「它自己的顺序」可跟，
--    只能按用户勾选的顺序存。硬塞进同一张表就得给整张表加一列对一半行无意义的字段。
--
-- 【为什么允许同一个 (entry, product, ref) 之外的重复顺序】
-- 不设唯一约束在 sort_order 上：勾选顺序里两条同序号只是显示顺序不唯一，不是数据错误，
-- 不值得让一次保存失败。
--
-- 【引用，不是父子】与 V29 同一口径：被引用的清单节点在产品侧栏里照旧在原位置，
-- 这里只是「这个入口页也把它列出来」。
--
-- 这份同时给 H2 用（见 V17 顶部注释 —— MetaDb.flywayLocation 只有 postgresql 有专属
-- 目录），所以写法要 H2 也认：**不用 IF NOT EXISTS**（V8 的事故记录：H2 认、MySQL 8 不认
-- → 测试全绿但真库起不来），只用一个纯 CREATE TABLE，三方言逐字相同。
CREATE TABLE nav_entry_product_links (
  entry_id   VARCHAR(64)  NOT NULL,
  -- 产品码，见 ProductCodes.KNOWN；没有它就得去猜 ref 属于哪个产品
  product    VARCHAR(32)  NOT NULL,
  -- 产品清单里那个节点的 id（`{frontendUrl}/menu.json` 的 menus[].id）
  ref        VARCHAR(128) NOT NULL,
  -- 勾选顺序。与 nav_entry_links 不同，这里**必须**存：清单节点没有自己的顺序可跟
  sort_order INT          NOT NULL DEFAULT 0,
  PRIMARY KEY (entry_id, product, ref)
);
