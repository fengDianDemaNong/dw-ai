import type { Perm, ProductModule } from './iam';
import { layerHref, layerIcon } from './layers';
import { buildModelingItems, navGroups as navDataGroups } from './navData';
import { MODEL_HOME } from './paths';
import { isStandalone } from './runtime';

export interface NavItem {
  path: string;
  label: string;
  icon: string;
  disabled?: boolean;
  disabledReason?: string;
  /**
   * 进这一页需要本项目下的哪个权限（见 `@dw-ai/engine` 的 `ROLE_PERMS`）。
   *
   * <p>不填 = 谁都能进。权限不够时这一项<b>还在，只是置灰</b>并给一句原因 ——
   * 由 {@link buildNavGroups} 统一标记，渲染端只认 `disabled` / `disabledReason`。
   */
  perm?: Perm;
  /**
   * 子菜单 —— 有它就是**目录节点**，`path` 必须留空串。
   *
   * <p>V23 起组织平台的侧栏是一棵不限深度的树（分组就是一个目录节点），产品报给它的
   * `menu.json` 也跟着能带层级（见 `scripts/gen-menu.mjs` 的 {@code candidateId}）。
   * 这里给自己的一项加 `children`，那一条就在 org 壳的侧栏里多一层缩进，**org 侧不用
   * 改任何配置**。
   *
   * <p><b>两个前提</b>：
   *
   * <ol>
   *   <li>本进程自己的侧栏（{@code AppNav} / {@code AppSidebar}）目前只画两层，
   *       加了 `children` 只会看到一个点不动的空路径项。要用就先把它改成递归渲染 ——
   *       否则「org 壳里看得见、产品自己的侧栏里看不见」。</li>
   *   <li>目录节点不能同时是可点的页面（`path` 必须为空）：渲染端对目录只认它的子节点，
   *       带了 `path` 也不会被用上，而那一行看起来像能点。构建期会拒掉这种写法
   *       （见 `gen-menu.mjs`）。</li>
   * </ol>
   */
  children?: NavItem[];
}

export interface NavGroup {
  title: string;
  module?: ProductModule;
  perm?: Perm;
  items: NavItem[];
}

/**
 * 侧栏全量分组：只有本进程（仓建设）自己的那些。规范中心的 `spec:read`、
 * 建模中心的 `model:read` 都是**组级**判权。
 *
 * <p>数据本体在 `navData.ts` —— 组织平台要「从各服务获取菜单」，得有一个能脱离
 * 浏览器环境被 Node import 的纯数据文件（见那边的说明）。这里保持名字与类型不变，
 * 调用方（`buildNavGroups`、`activeNavPath` 与用它们的布局/侧栏）一行不用改。
 */
export const navGroups: NavGroup[] = navDataGroups;

export function buildNavGroups(
  layers: { layer: string }[],
  access?: {
    hasModule: (m: ProductModule) => boolean;
    can: (p: Perm) => boolean;
    specAiDisabled?: boolean;
  }
): NavGroup[] {
  return navGroups
    .map((g) => {
      // standalone 没有「账号」这回事（见 `pages.ts` 的 hasLocalAccounts），成员管理整组拿掉。
      if (isStandalone() && g.perm === 'iam:member') return null;
      if (access) {
        if (g.module && !access.hasModule(g.module)) return null;
        if (g.perm && !access.can(g.perm)) return null;
      }
      const items =
        g.title === '规范中心'
          ? g.items.map((item) =>
              item.path === `${MODEL_HOME}/spec/copilot`
                ? {
                    ...item,
                    disabled: Boolean(access?.specAiDisabled),
                    disabledReason: '本组织未开通此项',
                  }
                : item
            )
          : g.items;
      if (g.title !== '建模中心') return { ...g, items };
      return {
        ...g,
        items: [
          ...layers.map((l) => ({
            path: layerHref(l.layer),
            label: l.layer,
            icon: layerIcon(l.layer),
          })),
          ...buildModelingItems(layers),
        ],
      };
    })
    .filter((g): g is NavGroup => Boolean(g));
}

export function activeNavPath(path: string, groups: NavGroup[] = navGroups): string | undefined {
  let best: string | undefined;
  for (const item of groups.flatMap((g) => g.items)) {
    // 目录节点（`path` 为空串，见 NavItem.children）不参与高亮：`startsWith('')` 恒真，
    // 它会把**任意**路径都点亮成这一项
    if (!item.path) continue;
    const hit = item.path === MODEL_HOME ? path === item.path : path.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}
