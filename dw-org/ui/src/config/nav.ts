export interface NavItem {
  /** 服务端节点身份（`nav_nodes.id`，或产品节点的 `{product}:{scope}:{...}`）；只用作列表 key。 */
  id?: string;
  path: string;
  label: string;
  icon: string;
  /**
   * 子菜单。非空 = 这一项是**目录**（点击展开/收起，而不是跳转）。
   *
   * <p>V23 起菜单是一棵树：`nav_groups` 那种「分组」概念没有了 —— 分组原本表达的就是
   * 一层目录，现在它就是一个 `path` 为空、带 `children` 的节点。层数不限
   * （用户 2026-09-27：「统一都是菜单，菜单下面还有菜单，就是子菜单。不限制菜单层级」）。
   */
  children?: NavItem[];
  href?: string;
  external?: boolean;
  disabled?: boolean;
  disabledReason?: string;
}

/**
 * 当前路由命中的菜单项（最长前缀匹配，递归整棵树）。
 *
 * <p>{@code items} 没有默认值：所有调用方都显式传（`AppNav` 传 props.items）。
 * 给个默认空数组只会让「忘了传」表现为「高亮永远失效」而不报错。
 *
 * <p>以前这里还有 `navGroups` / `buildNavGroups()` / `activeNavGroup()` 三样，已随
 * `nav_groups` 一起删掉 —— 留着一个恒空的常量，下一个人会当它是可用入口。
 */
/** 子树里有没有这一条路径。侧栏用它判「当前页在不在这个分组里」（见 NavNode 的 `open`）。 */
export function subtreeHasPath(item: NavItem, hit: string): boolean {
  if (item.path === hit) return true;
  return (item.children ?? []).some((kid) => subtreeHasPath(kid, hit));
}

/**
 * 子树里有没有哪一项与 `hit` 在**同一条路径上**（按 `/` 分段，任一方是另一方的前缀；
 * 相等也算）。
 *
 * <p>比 {@link subtreeHasPath} 宽一档，专治「壳的首页不等于组里任何一项」：项目壳的首页是
 * `/org/project/{code}`，而「项目」组里的项是 `/org/project/{code}/members` —— 精确匹配
 * 一个也命中不了，于是进了项目、侧栏里那一组反而默认收起，看起来像菜单没了。
 *
 * <p>**必须按段比，不能用字符串 `startsWith`**：`/lineage/a` 与 `/lineage/ab` 是两个页面，
 * 字符串前缀会把它们判成同一个。
 *
 * <p>目录自身的 `path` 是空串，这里直接跳过（`!/^path/` 那条守卫）—— 空串是任何字符串的
 * 前缀，不挡掉它，每个组都会命中一切。
 */
export function subtreeOnSameBranch(item: NavItem, hit: string): boolean {
  const onBranch = (path: string) => {
    if (!path) return false;
    const a = path.split('/');
    const b = hit.split('/');
    for (let i = 0; i < Math.min(a.length, b.length); i += 1) {
      if (a[i] !== b[i]) return false;
    }
    return true;
  };
  if (onBranch(item.path)) return true;
  return (item.children ?? []).some((kid) => subtreeOnSameBranch(kid, hit));
}

/**
 * 当前路由落在哪个**顶层主菜单**里（快捷栏用它决定显示哪一组）。
 *
 * <p>分两步：先按 {@link activeNavPath} 求最长前缀命中的那条路径，再回到顶层找包含它的
 * 那一支。不能像原型那样只在顶层取 `path.startsWith` —— V23 起顶层节点自己通常是个
 * **目录**（`path` 为空、内容在 `children`），拿当前路由去比它的 `path` 一条也命中不了。
 */
export function activeTopItem(path: string, items: NavItem[]): NavItem | undefined {
  const hit = activeNavPath(path, items);
  if (!hit) return undefined;
  return items.find((item) => subtreeHasPath(item, hit));
}

export function activeNavPath(path: string, items: NavItem[]): string | undefined {
  let best: string | undefined;
  const walk = (nodes: NavItem[]) => {
    for (const item of nodes) {
      // 禁用的项不参与高亮：它点不开，高亮它没有意义。而且 `always` 空目录的占位项
      // `path` 是空串，**任何路径都以空串开头**，它会变成「最短前缀」命中一切 ——
      // 于是没有真实命中时返回的是一串空字符串而不是 undefined。
      if (!item.disabled && item.path) {
        const hit = path === item.path || path.startsWith(item.path);
        if (hit && (!best || item.path.length > best.length)) best = item.path;
      }
      // 目录自己也可能有 path（既能点开又有子菜单），所以判据只看 path、不看有无 children；
      // 子节点一样要走一遍 —— 最长前缀匹配的赢家通常在更深处。
      if (item.children?.length) walk(item.children);
    }
  };
  walk(items);
  return best;
}
