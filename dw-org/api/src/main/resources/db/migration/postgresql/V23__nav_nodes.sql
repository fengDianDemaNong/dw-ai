-- 统一菜单树：**分组与菜单不再分家**，一张表装下任意层级的菜单；org 自己的菜单也进表。
--
-- 【为什么合并】V19 起「分组」是 nav_groups 里的一行、「菜单」是 nav_items 里的一行，
-- 两者靠 group_title 这个【自由字符串】软约束。用户 2026-09-27 看过之后提出：
-- 「现在的分组和菜单是两个概念，我觉得不太合适。应该统一都是菜单，菜单下面还有菜单，
--   就是子菜单，不限制菜单层级。」—— 分组原本表达的就是「一层目录」，把它摊平进菜单表
-- 反而让层级、顺序、空目录策略这些本来该在一处的东西分散在两处。
--
-- 【三类节点，判据只看两列】不加 source 列（多一个可以自相矛盾的字段）：
--   org 自有页面      product = ''                    → path 是 org 的完整路由，直接用
--   手工复制的产品页  product != '' AND mounted = FALSE → path 是【子应用内】路径，前端拼 embed
--   挂载节点          mounted = TRUE                   → (product, ref) 指向产品清单里的一个节点，
--                                                        该节点的子树在【渲染时】实时展开
--
-- 【parent_id 用空串表示顶层，不用 NULL】唯一约束里 NULL 与任何值都不相等（SQL 标准），
-- 顶层节点因此可以重名，约束形同虚设。空串是普通值，uk_nav_node 对顶层同样生效。
--
-- 【empty_policy 的语义从「组」搬到了「目录节点」】一个目录节点展开之后一个可用子项都没有时
-- （产品没开通、没角色、或挂载的产品清单拉不到）：hide = 整枝不出现（现状行为），
-- always = 该节点保留并置灰说明。
--
-- 【DROP 两张旧表是不可逆的】用户已拍板存量数据「不迁，重新配」。升级前请备份。
-- 之所以不迁：旧模型的 nav_items.group_title 是自由字符串，而新模型里分组有身份、
-- 有父链，字符串→节点的映射在「同壳同名分组挂在不同产品下」这类存量上根本推不出唯一答案。
--
-- 与 mysql/ 那份内容一致（那份同时给 H2 用，见 V17 顶部注释）。
CREATE TABLE nav_nodes (
  id           VARCHAR(64)  NOT NULL PRIMARY KEY,
  scope        VARCHAR(16)  NOT NULL,
  parent_id    VARCHAR(64)  NOT NULL DEFAULT '',
  title        VARCHAR(64)  NOT NULL,
  -- 空串 = 目录节点（不可点、只用来挂子节点）。不是 '/':三处都要判「这是不是目录」，
  -- 而 '/' 是一个合法的子应用首页路径（仓建设的 /model 就是这么报的）。
  path         VARCHAR(512) NOT NULL DEFAULT '',
  icon         VARCHAR(32)  NOT NULL DEFAULT '',
  perm         VARCHAR(64)  NOT NULL DEFAULT '',
  sort_order   INT          NOT NULL DEFAULT 0,
  enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
  -- 仅租户管理员可见（org 自有节点用）。现状是 buildSysNav(isRealTenantAdmin) 在前端算，
  -- 现在由服务端判 —— 前端算的那份在「租户管理员被换掉、页面没刷新」时会显示错。
  admin_only   BOOLEAN      NOT NULL DEFAULT FALSE,
  product      VARCHAR(32)  NOT NULL DEFAULT '',
  -- mounted = TRUE 时：产品清单里那个节点的 id（见 gen-menu.mjs 的 id 生成规则）。
  ref          VARCHAR(128) NOT NULL DEFAULT '',
  mounted      BOOLEAN      NOT NULL DEFAULT FALSE,
  empty_policy VARCHAR(16)  NOT NULL DEFAULT 'hide',
  created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  -- 同一父节点下标题唯一。挂载节点的匹配键是 ref 而不是 title，所以挂上之后改名是安全的 ——
  -- 这是相对 V21「已挂载的分组不能改名」的一处改进，改名的语义重新回到「只影响显示」。
  CONSTRAINT uk_nav_node UNIQUE (scope, parent_id, title)
);

-- org 自有菜单的种子（工作台壳）。内容是 sysNav.ts 的 buildSysNav 原地搬过来的，
-- 逐项对应；admin_only = 原先 tenantAdmin 为 false 时被 `...([])` 抹掉的那几项。
INSERT INTO nav_nodes
  (id, scope, parent_id, title, path, icon, perm, sort_order, enabled, admin_only, product, ref, mounted, empty_policy)
VALUES
  ('nav-sys',           'workbench', '',        '系统管理', '',                          'AppstoreOutlined',           '', 10, TRUE, FALSE, '', '', FALSE, 'hide'),
  ('nav-sys-users',     'workbench', 'nav-sys', '用户管理', '/org/workbench/users',       'TeamOutlined',               '', 10, TRUE, TRUE,  '', '', FALSE, 'hide'),
  ('nav-sys-roles',     'workbench', 'nav-sys', '角色管理', '/org/workbench/roles',       'SafetyCertificateOutlined',  '', 20, TRUE, TRUE,  '', '', FALSE, 'hide'),
  ('nav-sys-projects',  'workbench', 'nav-sys', '项目管理', '/org/workbench/projects',    'AppstoreOutlined',           '', 30, TRUE, FALSE, '', '', FALSE, 'hide'),
  ('nav-sys-knowledge', 'workbench', 'nav-sys', '知识库',   '/org/workbench/knowledge',   'ReadOutlined',               '', 40, TRUE, TRUE,  '', '', FALSE, 'hide'),
  ('nav-sys-settings',  'workbench', 'nav-sys', '设置',     '/org/workbench/settings',    'SettingOutlined',            '', 50, TRUE, TRUE,  '', '', FALSE, 'hide');

-- org 自有菜单的种子（项目壳）。内容是 sysNav.ts 的 buildProjectSysNav 搬过来的。
--
-- 「返回工作台」的地址与工作台壳的「项目管理」是同一处（SYS_HOME = /org/workbench/projects）：
-- 进了项目之后必须有回去的路，与产品菜单无关，所以它跟着壳走、不跟着任何产品。
--
-- 「成员管理」的路径含 {code} 占位符 —— 项目码是运行期才知道的，而这一行是全局配置。
-- 前端渲染时把 {code} 替换成当前项目码（encodeURIComponent 之后），见 sysNav.ts。
INSERT INTO nav_nodes
  (id, scope, parent_id, title, path, icon, perm, sort_order, enabled, admin_only, product, ref, mounted, empty_policy)
VALUES
  ('nav-proj',         'project', '',        '项目',       '',                                 'AppstoreOutlined', '',         10, TRUE, FALSE, '', '', FALSE, 'hide'),
  ('nav-proj-back',    'project', 'nav-proj', '返回工作台', '/org/workbench/projects',          'AppstoreOutlined', '',         10, TRUE, FALSE, '', '', FALSE, 'hide'),
  ('nav-proj-members', 'project', 'nav-proj', '成员管理',   '/org/project/{code}/members',      'TeamOutlined',     'iam:member', 20, TRUE, FALSE, '', '', FALSE, 'hide');

-- 不可逆：旧的分组与菜单配置在这里消失。见文件顶部注释。
DROP TABLE nav_groups;
DROP TABLE nav_items;
