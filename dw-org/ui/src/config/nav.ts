import type { Perm, ProductModule } from './iam';

export interface NavItem {
  path: string;
  label: string;
  icon: string;
  href?: string;
  external?: boolean;
  disabled?: boolean;
  disabledReason?: string;
}

export interface NavGroup {
  title: string;
  icon?: string;
  module?: ProductModule;
  perm?: Perm;
  items: NavItem[];
}

/** 组织平台只渲染租户管理工作台 / 平台后台，不挂仓建设或数据地图页面。 */
export const navGroups: NavGroup[] = [];

export function buildNavGroups(_layers?: unknown, _access?: unknown): NavGroup[] {
  return [];
}

export function activeNavGroup(path: string, groups: NavGroup[] = navGroups): NavGroup | undefined {
  const hit = activeNavPath(path, groups);
  if (!hit) return undefined;
  return groups.find((g) => g.items.some((item) => item.path === hit));
}

export function activeNavPath(path: string, groups: NavGroup[] = navGroups): string | undefined {
  let best: string | undefined;
  for (const item of groups.flatMap((g) => g.items)) {
    const hit = path === item.path || path.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}
