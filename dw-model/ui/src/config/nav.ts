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
    const hit = item.path === MODEL_HOME ? path === item.path : path.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}
