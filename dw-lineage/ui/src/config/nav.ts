import { authState } from '../stores/auth';
import { can, type Perm } from './iam';
import { PROJECT_GROUPS, WORKBENCH_GROUPS } from './navData';
import { LINEAGE_HOME, WORKBENCH_PAGES, hasLocalAccounts, isWorkbenchPath } from './pages';

/**
 * 导航结构。两套菜单（工作台级 / 项目级），按当前路径二选一。
 *
 * <p>一处定义，侧边栏、顶部菜单栏、面包屑三处共用 —— 改这里等于三处一起改。
 *
 * <p>`ready: false` 的项渲染成置灰状态、不可点。这一栏必须与 `router/index.ts`
 * 里真实存在的路由保持一致 —— 路由表末尾有个兜底规则会把未知路径 redirect 到
 * 本模式首页，所以指向不存在页面的链接不会报 404，而是静默跳回首页。
 *
 * <p>分组标题为空串表示不分组的顶层项。工作台级里「概况」「项目」各占一个这样的组 ——
 * 并列的两个主入口，套一个「工作台」分组反而多一层没有信息量的缩进。
 */
export interface NavItem {
  path: string;
  label: string;
  icon: string;
  ready: boolean;
  /**
   * 只有管理员能看到并进入。目前只有「账号管理」用。
   *
   * <p>这只是<b>不让人去点一个必然 403 的页面</b>，不是安全措施 ——
   * 后端 `LocalUserController.requireAdmin()` 才是判权的地方。
   */
  adminOnly?: boolean;
  /**
   * 进这一页需要本项目下的哪个权限（见 `@dw-ai/engine` 的 `ROLE_PERMS`）。
   *
   * <p>不填 = 谁都能进（概况、以及工作台那一级的全部项 —— 它们是租户级的，
   * 不跟着项目角色走）。
   *
   * <p>权限不够时这一项<b>还在，只是置灰</b>（不是抹掉）：一个空侧栏会让人以为
   * 服务坏了，而置灰 + 一句「当前角色无权访问」说的是「去找项目管理员派角色」。
   * 这与 PRD 对数据地图分组的要求一致。
   */
  perm?: Perm;
}

/**
 * 渲染用的菜单项：在 {@link NavItem} 之上多一个「为什么点不了」。
 *
 * <p>两种情况分开说 —— 「该页面尚未开放」是产品的锅，「当前角色无权访问」是
 * 派角的锅。合成一句话会让人找错人。
 */
export interface RenderNavItem extends NavItem {
  /** 非空 = 置灰不可点，这句话就是 tooltip。 */
  disabledReason?: string;
}

export interface NavGroup {
  title: string;
  items: RenderNavItem[];
}

/**
 * 工作台级菜单：进项目**之前**的那一级。
 *
 * <p>概况（全部项目口径）与项目（CRUD 与进入项目）是不分组的主入口，
 * 设置收着全局的那几项 —— 元数据服务所有项目共用一份，账号与外观偏好
 * 也不跟着项目走，所以它们归工作台而不是项目。
 */
/**
 * 数据本体在 `navData.ts` —— 组织平台要「从各服务获取菜单」，得有一个能脱离浏览器
 * 环境被 Node import 的纯数据文件（见那边的说明）。这里保持名字与类型不变，
 * 调用方（`visibleNavGroups` 与用它的侧栏/面包屑）一行不用改。
 */
export const workbenchNavGroups: NavGroup[] = WORKBENCH_GROUPS;

/**
 * 项目级菜单：进了某个项目之后的那一级。
 *
 * <p>三个口径：当前项目概况、数据地图、以及本项目的设置。
 *
 * <p>设置里只有「数据地图设置」一项（血缘图水印与缩略图）。它归项目级而不是工作台：
 * 水印是盖在**这个项目的**血缘图上、跟着导出的图片走的，跟人在哪个项目绑在一起；
 * 而工作台看的是全部项目的汇总，那里没有「当前这一张图」可言。
 *
 * <p>工作台那一级的设置（元数据服务、账号、外观）仍然归工作台 —— 那几个是
 * 全租户一份的，不跟着项目走。
 */
export const projectNavGroups: NavGroup[] = PROJECT_GROUPS;

/**
 * 按当前路径选出该显示哪一套菜单，再按运行模式与身份过滤。
 *
 * <p>「当前在哪一级」只由路径决定 —— 项目上下文是请求头（`stores/tenant` 的
 * `tenantState.projectId`），不进 URL，所以工作台与项目级的区别就是地址前缀的不同。
 *
 * <p>过滤只有两条：
 * - 只有 standard 有本地账号，所以「账号管理」在这之外的模式一律不出现
 * - 非管理员看不到「账号管理」—— 让他看见一个点进去必然 403 的入口没有意义
 *
 * <p>「项目」页不再需要按模式过滤：它现在只出现在工作台菜单里，而工作台本身
 * 只有 standard 与 standalone 能到（`hasWorkbench` + 路由守卫双重保证）。
 *
 * <p>`authState.me` 在 standard 下是**挂载前**就拉好的（`main.ts` 里 await `loadMe()`），
 * 所以这里能读到它。调用方（AppSidebar / AppTopnav）如果在 setup 里一次性调用，
 * 就得在切换层级时重新求值 —— 所以它们传的是 `computed`。
 */
export function visibleNavGroups(path: string): NavGroup[] {
  const source = isWorkbenchPath(path) ? workbenchNavGroups : projectNavGroups;
  const accounts = hasLocalAccounts();
  const admin = Boolean(authState.me?.admin);
  return source
    .map((g) => ({
      ...g,
      items: g.items
        .filter((item) => {
          if (item.adminOnly && !(accounts && admin)) return false;
          return true;
        })
        // 权限不够的项保留下来，只标记原因 —— 抹掉会让侧栏空掉，看不出是没权限。
        .map((item) => ({
          ...item,
          disabledReason: item.ready
            ? item.perm && !can(item.perm)
              ? '当前角色无权访问，请联系项目管理员'
              : ''
            : '该页面尚未开放',
        })),
    }))
    .filter((g) => g.items.length > 0);
}

/**
 * `/app/*` 是历史前缀（router 里留了重定向），比对前先归一到 `/lineage` 之下。
 *
 * <p>归一化只此一处 —— 下面三个查询函数都从它出发，免得各自记得这件事。
 */
function normalizePath(path: string): string {
  return path.startsWith('/app') ? `${LINEAGE_HOME}${path.slice(4) || ''}` || LINEAGE_HOME : path;
}

export function activeNavPath(path: string): string | undefined {
  const stripped = normalizePath(path);
  let best: string | undefined;
  for (const item of visibleNavGroups(stripped).flatMap((g) => g.items)) {
    // 两个层级的首页都要求精确相等，否则它会点亮本层级下所有页面
    const exact = item.path === LINEAGE_HOME || item.path === WORKBENCH_PAGES.home;
    const hit = exact ? stripped === item.path : stripped.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}

/** 当前高亮的那一项。面包屑要它的 label，`activeNavPath` 只给出 path。 */
export function activeNavItem(path: string): NavItem | undefined {
  const active = activeNavPath(path);
  if (!active) return undefined;
  return visibleNavGroups(normalizePath(path))
    .flatMap((g) => g.items)
    .find((i) => i.path === active);
}

export function groupOf(path: string): string | undefined {
  const active = activeNavPath(path);
  if (!active) return undefined;
  const group = visibleNavGroups(normalizePath(path)).find((g) => g.items.some((i) => i.path === active));
  return group?.title || undefined;
}
