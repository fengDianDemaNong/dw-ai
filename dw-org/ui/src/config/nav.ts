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

/**
 * 当前路由命中的菜单项（最长前缀匹配）。
 *
 * <p>这里以前还有 `navGroups` / `buildNavGroups()` / `activeNavGroup()` 三样：那时菜单
 * 打算在前端按「项目层 → 分组」拼出来，实现到一半改成了后端 `nav_items` 表
 * （见 dw-org 的 `NavController`），`navGroups` 恒返回 `[]`、`AppSidebar.vue` 全仓无引用。
 * 三样一起删掉 —— 留着一个恒空的常量，下一个人会当它是可用入口。
 *
 * <p>{@code groups} 没有默认值：所有调用方都显式传（`AppNav` 传 props.groups）。
 * 给个默认空数组只会让「忘了传」表现为「高亮永远失效」而不报错。
 */
export function activeNavPath(path: string, groups: NavGroup[]): string | undefined {
  let best: string | undefined;
  for (const item of groups.flatMap((g) => g.items)) {
    // 禁用的项不参与高亮：它点不开，高亮它没有意义。而且 `always` 空分组的占位项
    // `path` 是空串，**任何路径都以空串开头**，它会变成「最短前缀」命中一切 ——
    // 于是没有真实命中时返回的是一串空字符串而不是 undefined。
    if (item.disabled) continue;
    const hit = path === item.path || path.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}
