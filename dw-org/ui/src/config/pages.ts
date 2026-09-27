/**
 * 组织平台只做租户管理，只有 multi。
 * 仓建设 / 数据地图是另外两个进程，从工作台外链进去，不在本 UI 渲染。
 */
export type PageOwner = 'org' | 'model' | 'lineage';

export const ORG_HOME = '/org';

export const ORG_PAGES = {
  login: `${ORG_HOME}/login`,
  selectTenant: `${ORG_HOME}/select-tenant`,
  workbench: `${ORG_HOME}/workbench/projects`,
  users: `${ORG_HOME}/workbench/users`,
  roles: `${ORG_HOME}/workbench/roles`,
  knowledge: `${ORG_HOME}/workbench/knowledge`,
  settings: `${ORG_HOME}/workbench/settings`,
  platformTenants: `${ORG_HOME}/platform/tenants`,
  platformServices: `${ORG_HOME}/platform/services`,
  platformNav: `${ORG_HOME}/platform/nav-items`,
  platformProductRoles: `${ORG_HOME}/platform/product-roles`,
  platformUsers: `${ORG_HOME}/platform/users`,
  platformSettings: `${ORG_HOME}/platform/settings`,
  noProject: `${ORG_HOME}/no-project`,
} as const;

/**
 * 项目壳里某一页的地址：`/org/project/{项目码}/{页}`（`page` 省略即项目首页）。
 *
 * <p><b>为什么不塞进 {@link ORG_PAGES}</b>：那份是 `as const` 的静态字面量对象，
 * 装不下项目码这种动态段 —— 硬塞一个模板字符串进去，它的每个取值就不再是编译期
 * 常量，而「整份表都是常量」正是它能当单一真相用的原因。
 *
 * <p>项目码要 `encodeURIComponent`：与 `SystemLayout` 的 `home`、`sysNav` 拼
 * 产品菜单时同一个口径。code 目前都是 `[a-z_]`，但这层编码是免费的，
 * 少一处不一致就少一处「某个项目码里带 `/` 时点不动」。
 */
export function projectPagePath(code: string, page = ''): string {
  const base = `${ORG_HOME}/project/${encodeURIComponent(code)}`;
  return page ? `${base}/${page.replace(/^\/+/, '')}` : base;
}
