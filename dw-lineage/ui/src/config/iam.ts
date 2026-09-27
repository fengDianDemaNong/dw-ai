// 从子路径 `@dw-ai/engine/iam` 取，**不是**包根 `@dw-ai/engine`。
//
// 包根的 `index.ts` 是桶文件，`export *` 了 19 个模块（modeling / ddl / metrics …），
// 而 dev 下 vite 不做 tree-shaking：写包根，浏览器就要逐个请求那 19 个模块
// （实测每个路由固定 20 个 engine 请求，是本项目里最大的一块固定开销），
// 只为拿 `roleHas` 一个函数。
//
// `src/iam.ts` 是自包含的（73 行，唯一 import 是 `./types` 的 type-only 导入，
// 编译后消失），本文件要的 `roleHas` 与 `Perm` / `Product` / `ProjectRole`
// 全在里面。子路径由 `packages/engine/package.json` 的 `exports` 声明。
//
// 另两个产品不改：它们真的用到 engine 的几十个模块（modeling、specIo、grades …），
// 深导入要逐处改写且收益为负 —— 这条只对「只用 iam」的 lineage 成立。
import { roleHas, type Perm, type Product, type ProjectRole } from '@dw-ai/engine/iam';

export type { Perm, Product, ProjectRole };
export { roleHas };

/**
 * 本进程的产品码。
 *
 * <p>与后端 `/api/runtime` 报出去的 `product` 是同一个值，也是权限矩阵
 * （`@dw-ai/engine` 的 `ROLE_PERMS`）的第一维。它在这里是常量而不是配置：
 * 这个前端只画数据地图的菜单，换个值在矩阵里查不到任何东西。
 */
export const PRODUCT: Product = 'metadata';

const ROLES_KEY = 'sql-tools.bootRoles';

/**
 * 壳（工作台）通过 `#boot=` 带进来的「我在本项目各产品下的角色」。
 *
 * <p>存 sessionStorage 而不是内存：`#boot=` 只在首屏那一次出现，之后刷新页面就没了，
 * 而侧边栏每次切换都要重新求值。
 */
function readBootRoles(): Partial<Record<string, string>> {
  try {
    const raw = sessionStorage.getItem(ROLES_KEY);
    if (!raw) return {};
    const parsed: unknown = JSON.parse(raw);
    return parsed && typeof parsed === 'object' ? (parsed as Partial<Record<string, string>>) : {};
  } catch {
    return {};
  }
}

/** 由 `config/runtime.ts` 的 `consumeBootHash` 调用。 */
export function setBootRoles(raw: unknown): void {
  try {
    sessionStorage.setItem(ROLES_KEY, JSON.stringify(raw ?? {}));
  } catch {
    /* 隐私模式下存不下 —— 菜单会全开，后端仍然拦得住写操作 */
  }
}

/**
 * 手上有没有「壳给的」角色。
 *
 * <p>没有 = standalone / standard / 直接打开这个前端，三种情形都<b>不按角色收口</b>：
 * 前两种模式里根本没有「项目角色」这回事，按角色过滤只会得到一个空菜单。
 */
export function hasBootRoles(): boolean {
  return Object.keys(readBootRoles()).length > 0;
}

/** 我在本项目、本产品下的角色。没派角就是 `undefined` —— <b>不是</b> viewer。 */
export function currentProjectRole(product: Product): ProjectRole | undefined {
  const role = readBootRoles()[product];
  return role === 'admin' || role === 'modeler' || role === 'viewer' ? role : undefined;
}

/**
 * 本进程的判权。
 *
 * <p>拿不到角色时一律放行（理由见 {@link hasBootRoles}）。有角色时按矩阵判 ——
 * 没有角色（被拉进项目但只在仓建设有角色）会判否，那正是「入口置灰并说明」的来源。
 *
 * <p>这只决定<b>菜单画不画得出来</b>。真正的门禁在服务后端：写操作各自调组织平台的
 * `/internal/v1/authz/check` 兜底，所以这里放行不等于后端会放行。
 */
export function can(perm: Perm): boolean {
  if (!hasBootRoles()) return true;
  return roleHas(PRODUCT, currentProjectRole(PRODUCT), perm);
}
