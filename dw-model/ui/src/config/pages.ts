import { getRunMode, type RunMode } from './runtime';
import { LOGIN_PATH, MODEL_HOME } from './paths';

/**
 * 页面归属。仓建设进程只渲染 model 自己的路由；登录/选租户属于 org，本进程不提供。
 *
 * <p><b>没有「其他产品」这一档。</b>数据地图、数据规范那些页面由组织平台的项目壳
 * 自己整合（壳从各服务拉菜单、按 `scope` 摆到工作台或项目那一层），本进程不再
 * 以 iframe 嵌别人的页面 —— 所以这里既没有 `owner: 'lineage'`，也没有
 * 「这个部署有没有别的服务」这个判据。
 */
export type PageOwner = 'org' | 'model';

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
 * 工作台里的**项目管理**是不是本进程说了算（能建、能改、能删）。
 *
 * <p><b>只有 multi 不是。</b>项目号的真源在组织平台，模块禁止另造
 * （`0.2.0/spec/06-runtime-modes.md:137`），本进程只有 `OrgProjectPuller` 同步下来的镜像 ——
 * 所以多租户下那一页只列表，新建 / 编辑 / 删除都去组织平台做。
 *
 * <p>standalone 的口径是「= standard 去掉用户/登录」，<b>不是</b>「去掉用户
 * 再搭上工作台」：它是一个完整的本地部署，后端 `WarehouseLocalSeedRunner` 对它同样
 * 灌了本地租户、默认项目和演示账号，`TenantFilter` 也直接把它的 `tenantRole` 定成 admin。
 * 它不出的只是账号那一层。
 *
 * <p>此前这里叫 `hasWorkbench()`（multi 恒假、其余恒真），一个函数同时管着
 * 「有没有工作台这一级」和「能不能在本进程摆项目 CRUD」两件事。那两件事在 multi 下
 * 结论恰好相反 —— 工作台这一级要有（用户从组织平台回到本产品得有落脚处，
 * 见 {@link canBackToWorkbench}），但项目 CRUD 不能有。拆开之后这个函数只管后者；
 * 「有没有工作台」不再是个判据（三种模式都有）。
 */
export function ownsProjects(mode: RunMode = getRunMode()): boolean {
  return mode !== 'multi';
}

/**
 * 「返回工作台」这个入口，在当前模式下、对当前用户是否成立。
 *
 * <p><b>multi 与 standalone 下对所有人为真。</b>multi 下它不是管理员专属入口：普通成员
 * 在项目里没有别的去处，少了它就只能靠「退出」离开，而退出是要重新登录的。standalone
 * 的 admin 身份由后端 `TenantFilter` 直接给，不依赖任何账号记录，同理。
 *
 * <p>multi 此前同样满足这一条，但**落点不同**：那时本进程没有工作台这一级，按钮是整页
 * 跳到组织平台（`openOrgWorkbench`）。现在工作台在本进程（见 {@link ownsProjects}），
 * 落点改回 `SYS_HOME` —— 用户 2026-09-26 报的「在 model 里点返回工作台却跳到了 org 的
 * 工作台」就是旧的落点。
 *
 * <p>standard 维持原判：工作台是管理界面，只给租户管理员。
 *
 * <p>两个调用点（`components/UserPanel.vue`、`layouts/ProjectLayout.vue`）共用这一个判据 ——
 * 它们原先各写各的，才会出现「multi 下两处一起消失」而没人发现。
 */
export function canBackToWorkbench(realTenantAdmin: boolean, mode: RunMode = getRunMode()): boolean {
  if (mode === 'multi' || mode === 'standalone') return true;
  return realTenantAdmin;
}

export const MODEL_PAGES = {
  home: MODEL_HOME,
  login: LOGIN_PATH,
  spec: `${MODEL_HOME}/spec`,
  members: `${MODEL_HOME}/members`,
  knowledge: `${MODEL_HOME}/knowledge`,
  projects: `${MODEL_HOME}/projects`,
} as const;

export function isModelPath(path: string): boolean {
  return path === MODEL_HOME || path.startsWith(`${MODEL_HOME}/`);
}
