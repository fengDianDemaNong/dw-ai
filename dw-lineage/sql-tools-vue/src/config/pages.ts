import { isMulti, isStandalone, type RunMode, getRunMode } from './runtime';

/** 数据地图本进程页面统一挂 /lineage。租户切换属于 org，独立模式不出现。 */
export const LINEAGE_HOME = '/lineage';

export const LINEAGE_PAGES = {
  home: LINEAGE_HOME,
  search: `${LINEAGE_HOME}/search`,
  tables: `${LINEAGE_HOME}/tables`,
  catalogs: `${LINEAGE_HOME}/catalogs`,
  tempRules: `${LINEAGE_HOME}/temp-rules`,
  analyze: `${LINEAGE_HOME}/analyze`,
  meta: `${LINEAGE_HOME}/meta`,
  tenants: `${LINEAGE_HOME}/settings/tenants`,
  projects: `${LINEAGE_HOME}/settings/projects`,
  metadata: `${LINEAGE_HOME}/settings/metadata`,
  preferences: `${LINEAGE_HOME}/settings/preferences`,
  map: `${LINEAGE_HOME}/settings/map`,
} as const;

export function otherProductsVisible(mode: RunMode = getRunMode()): boolean {
  return mode === 'multi';
}

export function hideTenantSwitch(mode: RunMode = getRunMode()): boolean {
  return mode === 'standalone' || isMulti();
}

export function showLocalTenantSettings(mode: RunMode = getRunMode()): boolean {
  return !isStandalone() && !isMulti();
}
