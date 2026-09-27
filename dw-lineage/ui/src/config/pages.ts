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
 * <p><b>三种模式都有这一级</b>（2026-09-26 起）。此前 multi 没有，理由是「项目由组织
 * 平台下发、进入项目也在组织平台完成，本进程再摆一个工作台只会与平台打架」；现在
 * 口径改了 —— 用户从组织平台（或直连本服务）回到本产品时得有个落脚处，而工作台里的
 * 项目页在 multi 下退化成只读清单（见 `ownsProjects`），不再构成与平台打架。
 *
 * <p>它刻意挂在 `/lineage/workbench` 而不是 `/lineage` 本身 —— `/lineage` 及其下的
 * `search` / `tables` / `catalogs` / `temp-rules` / `analyze` / `meta` 是**嵌入契约**：
 * dw-model 的 `pages/map/embed.vue` 会把这些路径通过 postMessage（`config/embed.ts` 的
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
 * 这两条必须本身就能渲染出页面 —— 嵌入发生在**项目页**里，它们挂的也是项目层级，
 * 所以路由表里是**真实路由**，而不是「跳到工作台」的重定向：后者会让壳里的
 * 两个入口空转。
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
 * 工作台里的**项目管理**是不是本进程说了算（能建、能改、能删）。
 *
 * <p><b>只有 multi 不是。</b>项目号的真源在组织平台，本进程只有 `OrgProjectPuller`
 * 同步下来的镜像 —— 所以那一页在 multi 下只列表，新建 / 编辑 / 删除都去组织平台做
 * （后端 `TenantAdminController.assertLocalAdmin()` 在 multi 下也是一律 403）。
 *
 * <p>standalone 的口径是「= standard 去掉用户/登录」，<b>不是</b>「去掉用户
 * 再搭上工作台」：它是一个完整的本地部署，有租户、有项目、有全局一份的元数据服务
 * 配置，`TenantFilter` 直接把它的 `tenantRole` 定成 admin。它不出的只是账号那一层
 * （`hasLocalAccounts` 仍然只有 standard）。
 *
 * <p>此前这里叫 `hasWorkbench()`（multi 恒假、其余恒真），一个函数同时管着
 * 「有没有工作台这一级」和「能不能在本进程摆项目 CRUD」两件事。那两件事在 multi 下
 * 结论恰好相反 —— 工作台这一级要有（用户回到本产品得有落脚处），但项目 CRUD 不能有。
 * 拆开之后这个函数只管后者；「有没有工作台」不再是个判据（三种模式都有）。
 *
 * <p>「项目」设置页原来另有一个 `showProjectSettings()`，取值与这里完全一样 ——
 * 项目与其它设置一起搬进工作台之后，那个名字不再有独立含义，已经收掉。
 */
export function ownsProjects(mode: RunMode = getRunMode()): boolean {
  return mode !== 'multi';
}

/**
 * 这个部署的「首页」：工作台。三种模式都是它。
 *
 * <p>登录落地、`/`、未知路径、登录页的兜底四处都走它 —— 一处定义，
 * 保证不会有人被送进一个本模式压根不存在的层级。
 *
 * <p>此前 multi 的首页是项目概况（那时它没有工作台）；工作台回到本进程之后统一成
 * 工作台 —— 先看有哪些项目、再进某个项目，与另外两种模式同一个动线。
 */
export function defaultHome(): string {
  return WORKBENCH_HOME;
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
