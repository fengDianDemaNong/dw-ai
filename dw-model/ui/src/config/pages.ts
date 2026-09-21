import { getRunMode, type RunMode } from './runtime';
import { LINEAGE_HOME, LOGIN_PATH, MODEL_HOME } from './paths';

/**
 * 三个产品的页面归属。仓建设进程只渲染 model 自己的路由；
 * 数据地图以 iframe 嵌 lineage（仅 multi）；登录/选租户属于 org，本进程不提供。
 *
 * standalone / standard 不显示其他服务。
 */
export type PageOwner = 'org' | 'model' | 'lineage';

export function otherProductsVisible(mode: RunMode = getRunMode()): boolean {
  return mode === 'multi';
}

export function localLoginRequired(mode: RunMode = getRunMode()): boolean {
  return mode === 'standard';
}

export function guestAccess(mode: RunMode = getRunMode()): boolean {
  return mode === 'standalone';
}

export const MODEL_PAGES = {
  home: MODEL_HOME,
  login: LOGIN_PATH,
  spec: `${MODEL_HOME}/spec`,
  members: `${MODEL_HOME}/members`,
  knowledge: `${MODEL_HOME}/knowledge`,
  projects: `${MODEL_HOME}/projects`,
} as const;

export const LINEAGE_PAGES = {
  home: LINEAGE_HOME,
  search: `${LINEAGE_HOME}/search`,
  tables: `${LINEAGE_HOME}/tables`,
  catalogs: `${LINEAGE_HOME}/catalogs`,
  tempRules: `${LINEAGE_HOME}/temp-rules`,
  analyze: `${LINEAGE_HOME}/analyze`,
  meta: `${LINEAGE_HOME}/meta`,
  metadata: `${LINEAGE_HOME}/settings/metadata`,
  settings: `${LINEAGE_HOME}/settings/map`,
} as const;

export function isLineagePath(path: string): boolean {
  return path === LINEAGE_HOME || path.startsWith(`${LINEAGE_HOME}/`);
}

export function isModelPath(path: string): boolean {
  return path === MODEL_HOME || path.startsWith(`${MODEL_HOME}/`);
}
