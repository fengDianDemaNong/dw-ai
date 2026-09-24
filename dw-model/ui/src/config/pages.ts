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

/**
 * 这个部署有没有「本地账号」这回事。
 *
 * <p>只有 standard 有。standalone 直接放行（后端 `TenantFilter` 把
 * `tenantRole` 定成 admin、userId 定成 standalone），没有人需要登录，
 * 也就没有账号可管 —— 库里那几行演示账号是种子数据，不是给人管的对象。
 * multi 的账号在组织平台，本进程不提供。
 */
export function hasLocalAccounts(mode: RunMode = getRunMode()): boolean {
  return mode === 'standard';
}

/**
 * 这个部署有没有「工作台」——进项目**之前**的那一级，挂在 `SYS_HOME`。
 *
 * <p><b>standalone 与 standard 都有，只有 multi 没有。</b>
 *
 * <p>standalone 的口径是「= standard 去掉用户/登录」，<b>不是</b>「去掉用户
 * 再搭上工作台」。工作台管的是「这一个部署有哪些项目、本地的全局设置在哪」——
 * standalone 是一个完整的本地部署：后端 `WarehouseLocalSeedRunner` 对它同样
 * 灌了本地租户、默认项目和演示账号，`TenantFilter` 也直接把它的 `tenantRole`
 * 定成 admin，这些事对它有同样的意义。它不出的只是账号那一层。
 *
 * <p>multi 反过来：项目由组织平台 fan-out、进入某个项目也在组织平台完成，
 * 本进程再摆一个「项目 CRUD」的工作台只会与平台打架。
 */
export function hasWorkbench(mode: RunMode = getRunMode()): boolean {
  return mode !== 'multi';
}

/**
 * 「返回工作台」这个入口，在当前模式下、对当前用户是否成立。
 *
 * <p><b>multi 下对所有人为真</b>：本进程没有工作台（见上），上一级在组织平台那边，
 * 而组织平台对普通成员也放行工作台的「项目管理」页
 * （`dw-org/ui/src/router/index.ts` 的 `if (to.name === 'sys-projects') return true`）。
 * 所以它不是管理员专属入口 —— 恰恰相反，普通成员在项目里没有别的去处，
 * 少了它就只能靠「退出」离开，而退出是要重新登录的。
 *
 * <p>其余模式维持原判：工作台在本进程、且是管理界面，只给租户管理员。standalone
 * 例外，它的 admin 身份由后端 `TenantFilter` 直接给，不依赖任何账号记录。
 *
 * <p>两个调用点（`components/UserPanel.vue`、`layouts/ProjectLayout.vue`）共用这一个判据 ——
 * 它们原先各写各的，才会出现「multi 下两处一起消失」而没人发现。
 */
export function canBackToWorkbench(realTenantAdmin: boolean, mode: RunMode = getRunMode()): boolean {
  if (mode === 'multi') return true;
  return hasWorkbench(mode) && (realTenantAdmin || mode === 'standalone');
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
