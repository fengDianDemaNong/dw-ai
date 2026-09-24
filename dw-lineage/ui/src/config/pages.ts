import { type RunMode, getRunMode } from './runtime';

/** 数据地图本进程页面统一挂 /lineage。租户切换属于 org，独立模式不出现。 */
export const LINEAGE_HOME = '/lineage';

/**
 * 本地登录页。只在 standard（普通模式）下用得到 —— 见 stores/auth 的模式分工说明。
 *
 * <p>刻意<b>放在 /lineage 之下</b>：路由表里 AppLayout 挂在 LINEAGE_HOME 上，
 * 而登录页不该有侧边栏和顶栏（那时还没有身份，菜单点了也全是 401）。
 * 所以它在 router 里注册为 LINEAGE_HOME 的<b>兄弟</b>，不进 children。
 */
export const LOGIN_PATH = `${LINEAGE_HOME}/login`;

/**
 * 租户工作台首页。进项目**之前**的层级：概况 / 项目 / 设置。
 *
 * <p>standard 与 standalone 有、multi 没有（见 `hasWorkbench`）。它刻意挂在 `/lineage/workbench` 而不是
 * `/lineage` 本身 —— `/lineage` 及其下的 `search` / `tables` / `catalogs` /
 * `temp-rules` / `analyze` / `meta` 是**嵌入契约**：dw-model 的
 * `pages/map/embed.vue` 会把这些路径通过 postMessage（`config/embed.ts` 的
 * `EMBED_NAV`）推给本进程的 iframe。重排它们等于让壳里的链接全部落空，
 * 所以工作台另起一段，两边互不打扰。
 */
export const WORKBENCH_HOME = `${LINEAGE_HOME}/workbench`;

/**
 * 项目级页面。这些路径是嵌入契约，**不要改**（见 `WORKBENCH_HOME` 的说明）。
 */
export const LINEAGE_PAGES = {
  home: LINEAGE_HOME,
  search: `${LINEAGE_HOME}/search`,
  tables: `${LINEAGE_HOME}/tables`,
  catalogs: `${LINEAGE_HOME}/catalogs`,
  tempRules: `${LINEAGE_HOME}/temp-rules`,
  analyze: `${LINEAGE_HOME}/analyze`,
  meta: `${LINEAGE_HOME}/meta`,
} as const;

/**
 * 工作台级页面。设置类页面从 `/lineage/settings/*` 搬到这里 ——
 * 它们本来就是租户/全局口径（元数据服务是所有项目共用一份，账号与外观偏好
 * 也不跟着项目走），挂在项目层级下只是历史原因；老路径在 router 里留了重定向。
 *
 * <p>没有 `map`：数据地图设置（血缘图水印与缩略图）归**项目级**，
 * 见 `PROJECT_SETTINGS_PAGES.map`。
 */
export const WORKBENCH_PAGES = {
  home: WORKBENCH_HOME,
  projects: `${WORKBENCH_HOME}/projects`,
  metadata: `${WORKBENCH_HOME}/settings/metadata`,
  preferences: `${WORKBENCH_HOME}/settings/preferences`,
  users: `${WORKBENCH_HOME}/settings/users`,
} as const;

/**
 * 项目层级的设置路径。
 *
 * <p>`metadata` 与 `map` 是**嵌入契约的一半，不要删**：dw-model 的「元数据服务」
 * 「数据地图设置」两个页面（`pages/map/embed.vue`）会把
 * `/lineage/settings/metadata`、`/lineage/settings/map` 通过 postMessage
 * 推给本进程的 iframe（见 `dw-model/ui/src/router/index.ts` 的 `lineagePath`）。
 * 而 **multi 模式没有工作台**，这两条必须本身就能渲染出页面 ——
 * 所以它们在路由表里是**真实路由**，而不是「跳到工作台」的重定向：
 * 后者在 multi 下会被守卫打回项目概况，壳里的两个入口就空转了。
 *
 * <p>`map` 另外还是项目级菜单「设置 › 数据地图设置」的落点（standard 下也走菜单，
 * 不再只靠嵌入推路径）。`projects` 与 `users` 是工作台专有页的老地址，
 * 在 router 里按模式重定向。
 *
 * <p>没有 `preferences`：基本信息页只在工作台（见 `WORKBENCH_PAGES.preferences`），
 * 项目层级这条老地址在 router 里直接送去有意义的落点。
 */
export const PROJECT_SETTINGS_PAGES = {
  projects: `${LINEAGE_HOME}/settings/projects`,
  metadata: `${LINEAGE_HOME}/settings/metadata`,
  map: `${LINEAGE_HOME}/settings/map`,
  users: `${LINEAGE_HOME}/settings/users`,
} as const;

/**
 * 这个路径属不属于工作台层级。菜单、品牌链接、面包屑都按它分流。
 *
 * <p>比的是**路径段**而不是裸前缀：`/lineage/workbenchfoo` 不该被当成工作台。
 */
export function isWorkbenchPath(path: string): boolean {
  return path === WORKBENCH_HOME || path.startsWith(`${WORKBENCH_HOME}/`);
}

export function otherProductsVisible(mode: RunMode = getRunMode()): boolean {
  return mode === 'multi';
}

/**
 * 这个部署有没有「租户工作台」（进项目之前的那一级）。
 *
 * <p><b>standard 与 standalone 都有，只有 multi 没有。</b>
 *
 * <p>{@code standalone} 的口径是「= standard 去掉用户/登录」，而不是「= standard
 * 去掉用户<b>再</b>去掉工作台」。工作台管的是「这一个部署有哪些项目、全局设置在哪」——
 * standalone 是一个完整的本地部署，有租户、有项目、有全局一份的元数据服务配置，
 * 这些事对它有同样的意义；它不出的只是账号体系那一层（`hasLocalAccounts` 仍然
 * 只有 standard）。
 *
 * <p>multi 反过来：那里的项目由组织平台下发、进入某个项目也在组织平台完成，
 * 本进程再摆一个「项目 CRUD + 进入」的工作台只会与平台打架，所以保持单级
 * （项目概况 + 数据地图）。
 *
 * <p>「项目」设置页原来另有一个 `showProjectSettings()`，取值与这里完全一样 ——
 * 项目与其它设置一起搬进工作台之后，那个名字不再有独立含义，已经收掉。
 */
export function hasWorkbench(mode: RunMode = getRunMode()): boolean {
  return mode !== 'multi';
}

/**
 * 这个部署的「首页」：standard 是工作台，其余模式是项目概况。
 *
 * <p>登录落地、`/`、未知路径、登录页的兜底四处都走它 —— 一处定义，
 * 保证不会有人被送进一个本模式压根不存在的层级。
 */
export function defaultHome(mode: RunMode = getRunMode()): string {
  return hasWorkbench(mode) ? WORKBENCH_HOME : LINEAGE_HOME;
}

/**
 * 这个部署有没有「本地账号」这回事。
 *
 * <p>只有 standard 有。注意它回答的是「功能存不存在」，
 * 回答不了「当前这个人能不能进」—— 后者还要看他是不是管理员，
 * 那要看 `stores/auth` 的 `me.admin`（数据地图没有角色表，只有管理员/普通两档）。
 */
export function hasLocalAccounts(mode: RunMode = getRunMode()): boolean {
  return mode === 'standard';
}

/**
 * 要不要由本进程自己拦出登录页。
 *
 * <p>只有 standard 要。standalone 没有账号体系（`/api/**` 全放行），拦了就是无门可入；
 * multi 的身份由组织平台负责，令牌经 `#boot=` 带进来，本地再拦一次只会和组织打架。
 */
export function requiresLocalLogin(mode: RunMode = getRunMode()): boolean {
  return mode === 'standard';
}
