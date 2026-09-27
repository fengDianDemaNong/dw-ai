import type { ProjectMember } from './types';

/**
 * 产品码，与产品 SKU 对应。取值见 docs/tech/06-0.1.5-routes.md §2。
 *
 * <p>这里只列已交付的两个；`serve` / `quality` 等派了角也没权限词可用。
 */
export type Product = 'warehouse' | 'metadata';

/**
 * 权限词，按产品分。
 *
 * <p>两套词不通用：`spec:read` 只在仓建设有意义，`catalog:read` 只在数据地图有意义。
 * 合成一个联合是为了让 {@link roleHas} 的入参只有一个名字；跨产品传错不会编译报错，
 * 而是在表里查不到、判否。
 */
export type Perm =
  // 仓建设：规范中心 + 建模中心
  | 'spec:read'
  | 'spec:write'
  | 'model:read'
  | 'model:write'
  | 'model:publish'
  | 'iam:member'
  // 数据地图：目录 + 血缘
  | 'catalog:read'
  | 'lineage:read'
  | 'lineage:write'
  | 'catalog:admin';

export type ProjectRole = ProjectMember['role'];

/**
 * 角色 → 权限，按产品分。
 *
 * <p>放在共享包里而不是各 UI 各一份：三个前端（工作台 / 仓建设 / 数据地图）都要用它
 * 画菜单，抄三份的话加一个产品得改三处，而漏掉的那一处不会编译报错 —— 它只在运行时
 * 判否，表现为「菜单莫名少了一项」这种最难查的故障。
 *
 * <p>后端有一份<b>必须与之一致</b>的矩阵：`dw-common` 的 `Perms.java`。改这里就得改那里。
 *
 * <p>同一个「项目管理员」在仓建设是规范管理员、在数据地图是目录管理员 —— 那不是两个
 * 角色，是同一个角色在两个产品里的词不同。所以第一维是产品。
 */
export const ROLE_PERMS: Record<Product, Record<ProjectRole, Perm[]>> = {
  warehouse: {
    admin: ['spec:read', 'spec:write', 'model:read', 'model:write', 'model:publish', 'iam:member'],
    modeler: ['spec:read', 'model:read', 'model:write'],
    viewer: ['spec:read', 'model:read'],
  },
  metadata: {
    admin: ['catalog:read', 'lineage:read', 'lineage:write', 'catalog:admin'],
    modeler: ['catalog:read', 'lineage:read', 'lineage:write'],
    viewer: ['catalog:read', 'lineage:read'],
  },
};

/**
 * 某产品下某角色是否具备该权限。产品、角色、权限任一未知都判否。
 *
 * <p><b>为什么查不到要判否，而不是直接 `includes`</b>：V20 起后端的写侧改成
 * 「角色码问产品角色表」，平台管理员在后台新建的角色码（如 `analyst`）是能派下去的。
 * 而 {@link ROLE_PERMS} 是**本前端的判权矩阵**，只登记了内置三档 —— 表里查不到
 * 就是 `undefined`。早先那句 `.includes()` 在这种情况下抛 TypeError，接进 Vue 的
 * computed 就是「某人被派了自定义角色 → 整个侧栏白屏」。
 *
 * <p>自定义角色在本前端的语义是「未登记」，与产品未知、角色未派同一口径：判否。
 */
export function roleHas(product: Product, role: ProjectRole | undefined, perm: Perm): boolean {
  if (!role) return false;
  const perms = (ROLE_PERMS[product] as Record<string, Perm[] | undefined>)[role];
  return perms ? perms.includes(perm) : false;
}
