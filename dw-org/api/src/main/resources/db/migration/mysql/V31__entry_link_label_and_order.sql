-- 入口页的 Tab：给它一个**自己的名字**，以及**自己的顺序**。
--
-- 【为什么现在要推翻 V29「不设 sort_order」那条】
-- V29 的原话是「表格的顺序就是 target 自己在 nav_nodes 里的顺序，免得同一件事有两个真相」。
-- 那条在「Tab 名只能是菜单自己的名字」时是成立的 —— 名字与顺序都跟着 target 走，一个真相。
-- 用户报的场景把它推翻了：一个入口页挂两个名字相同的菜单（截图里两个 Tab 都叫「概况」），
-- **名字与顺序必须能各自独立地给**，否则两个同名 Tab 分不出谁是谁，也就谈不上排序。
-- 用户拍板：**行顺序 = Tab 顺序**（列表里能上下移动）。
--
-- 这是 V29 注释里预留的那次迁移：「将来真要独立排序再加列，那时才需要一次迁移」。
--
-- 【为什么不回填 sort_order】
-- 已有行全部落到 DEFAULT 0。读侧（NavNodeService.entryPage）在 sort_order **并列**时的
-- 兜底是「nav_entry_links 那一类按 target 自己在 nav_nodes 里的 (sort_order, title)，
-- 且整体排在 nav_entry_product_links 那些之前」—— 与本次改动前的旧行为**逐字相同**。
-- 于是老入口页的 Tab 顺序分毫不变，不必依赖一条 UPDATE ... SET c = (SELECT ...) 的
-- 相关子查询（那个写法在三方言下要各测一遍），管理员下次保存时自然被重编号。
--
-- 【label = NULL 的含义】**没有被改名**，渲染时用被挂菜单自己的标题 —— 不是「名字为空」。
-- 空串与 NULL 同等对待（读侧 Str.trim 后判空）。长度上限在写入时校验（64），
-- 这里不写 CHECK：三方言对 CHECK 的支持与报错文案不一致，而写入侧本来就要校验一遍。
--
-- 【label 也给了产品那一类】产品清单节点的标题同样会重名（截图里那两个「概况」就是），
-- 只给一半等于「本站菜单能改名、产品菜单不能」，说不通。
--
-- 这份同时给 H2 用（见 V17 顶部注释），所以写法要 H2 也认：**不用 IF NOT EXISTS**
-- （V8 的事故记录：H2 认、MySQL 8 不认 → 测试全绿但真库起不来）。
ALTER TABLE nav_entry_links ADD COLUMN label      VARCHAR(64) NULL;
ALTER TABLE nav_entry_links ADD COLUMN sort_order INT NOT NULL DEFAULT 0;

ALTER TABLE nav_entry_product_links ADD COLUMN label VARCHAR(64) NULL;
