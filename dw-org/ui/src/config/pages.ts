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
