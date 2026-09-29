-- 菜单功能扩展：入口页（引用式容器）+ 外链菜单。
--
-- 【V23 那句「三类节点只看 product + mounted 两列」现在过时了】本次加**两类新节点**，
-- 判据仍然是显式列（仍然不加 source 列，理由同 V23：多一个可以自相矛盾的字段）：
--
--   入口页     entry_page = TRUE     → 自己是一个表格页面，表里列出 nav_entry_links 挂的菜单
--   外链菜单   external_url != ''    → 指向平台外的一个地址，可内嵌可外跳
--
-- 两者都与 product 互斥（都是 org 自己的东西），且**互相排斥** —— 逐条校验在
-- NavNodeService.apply() 里，与 adminOnly / mounted 那几条校验放在一处。
--
-- 【为什么入口页要单独一列，而不是靠「有没有 link 记录」判】空入口页是**合法状态**
-- （建好了还没挂东西）。靠关联表判会让它退化成目录节点（path 为空、点不动）—— 而它
-- 明明有一个页面，只是表里还没内容。
--
-- 【token 与密码加密落库，任何回传路径只给 hasToken / hasPassword】
-- 复用 LlmCrypto（与 LLM Key、调度器 Token 同一把密钥 —— 类名带 Llm 是历史原因），
-- 照 V27 的 scheduler_token_enc 那一套。请求与响应是两个不同的 DTO，空值 = 保持原值。
--
-- 这份同时给 H2 用（见 V17 顶部注释 —— MetaDb.flywayLocation 只有 postgresql 有专属
-- 目录），所以写法要 H2 也认：不用 IF NOT EXISTS，BOOLEAN / BLOB / VARCHAR 三方言都认。
ALTER TABLE nav_nodes ADD COLUMN entry_page     BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE nav_nodes ADD COLUMN external_url   VARCHAR(512) NOT NULL DEFAULT '';
-- embed = 内嵌进壳（走 /org/{壳}/external/{id} 那一页）；jump = 新标签页打开。
ALTER TABLE nav_nodes ADD COLUMN open_mode      VARCHAR(16)  NOT NULL DEFAULT 'jump';
-- none = 不授权；token = 拼进 URL 查询串；basic = 账号密码（只保存，不自动登录 —— 浏览器做不到）。
ALTER TABLE nav_nodes ADD COLUMN auth_mode      VARCHAR(16)  NOT NULL DEFAULT 'none';
ALTER TABLE nav_nodes ADD COLUMN token_enc      BLOB         NULL;
ALTER TABLE nav_nodes ADD COLUMN basic_user     VARCHAR(128) NOT NULL DEFAULT '';
ALTER TABLE nav_nodes ADD COLUMN basic_pass_enc BLOB         NULL;
-- 外链菜单独立的可见性开关（**不复用 admin_only**：那个只对 adminOnly 语义明确，
-- 而外链要的是「谁能用这条带凭据的入口」，见 NavNodeService.render 的分支）。
-- 只有 all / tenant_admin 两档：另两档（按产品角色）需要 product 才能算，而外链没有产品。
ALTER TABLE nav_nodes ADD COLUMN visibility     VARCHAR(16)  NOT NULL DEFAULT 'all';

-- 入口页挂了哪些菜单。
--
-- 【引用，不是父子】被挂的菜单**在它原来的位置也还在**（用户裁定）。这与 parent_id 那套
-- 是两回事：parent_id 是「唯一的归属」，这里可以多对多 —— 同一条菜单能同时出现在多个入口页里。
--
-- 【为什么不加外键】nav_nodes 现状没有任何外键（V23），这里保持一致。悬空引用由两处兜：
-- NavNodeService.delete() 连带清理关联行，以及渲染时跳过找不到的 target（防御）。
--
-- 【主键就是去重】(entry_id, target_id) 不允许重复挂同一条，比另加一个唯一索引直白。
--
-- 【不设 sort_order】表格的顺序就是 target 自己在 nav_nodes 里的顺序（渲染时跟着走），
-- 免得同一件事有两个真相 —— 挂了之后改 target 的排序，表格里却不跟着变。
-- 将来真要独立排序再加列，那时才需要一次迁移。
CREATE TABLE nav_entry_links (
  entry_id  VARCHAR(64) NOT NULL,
  target_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (entry_id, target_id)
);
