import { LINEAGE_HOME, LINEAGE_PAGES, showLocalTenantSettings } from './pages';

/**
 * 导航结构。
 *
 * 一处定义，侧边栏、顶部菜单栏、面包屑三处共用 —— 改这里等于三处一起改。
 *
 * `ready: false` 的项渲染成置灰状态、不可点。这一栏必须与 `router/index.ts`
 * 里真实存在的路由保持一致 —— 路由表末尾有个兜底规则会把未知路径 redirect 到
 * LINEAGE_HOME，所以指向不存在页面的链接不会报 404，而是静默跳回概览页。
 *
 * 分组标题为空串表示不分组的顶层项（概览、元数据）。
 */
export interface NavItem {
  path: string;
  label: string;
  icon: string;
  ready: boolean;
}

export interface NavGroup {
  title: string;
  items: NavItem[];
}

export const navGroups: NavGroup[] = [
  {
    title: '',
    items: [{ path: LINEAGE_PAGES.home, label: '概览', icon: 'DashboardOutlined', ready: true }],
  },
  {
    title: '数据地图',
    items: [
      { path: LINEAGE_PAGES.search, label: '全文检索', icon: 'SearchOutlined', ready: true },
      { path: LINEAGE_PAGES.tables, label: '血缘', icon: 'PartitionOutlined', ready: true },
      { path: LINEAGE_PAGES.catalogs, label: '数据目录', icon: 'FolderOutlined', ready: true },
      { path: LINEAGE_PAGES.tempRules, label: '临时表规则', icon: 'FilterOutlined', ready: true },
      { path: LINEAGE_PAGES.analyze, label: 'SQL 解析', icon: 'CodeOutlined', ready: true },
    ],
  },
  {
    title: '',
    items: [{ path: LINEAGE_PAGES.meta, label: '元数据', icon: 'TableOutlined', ready: true }],
  },
  {
    title: '设置',
    items: [
      { path: LINEAGE_PAGES.tenants, label: '租户', icon: 'TeamOutlined', ready: true },
      { path: LINEAGE_PAGES.projects, label: '项目', icon: 'ProjectOutlined', ready: true },
      { path: LINEAGE_PAGES.metadata, label: '元数据服务', icon: 'ApiOutlined', ready: true },
      { path: LINEAGE_PAGES.preferences, label: '基本信息', icon: 'SettingOutlined', ready: true },
    ],
  },
];

const LOCAL_TENANT_PATHS = new Set([LINEAGE_PAGES.tenants, LINEAGE_PAGES.projects]);

/** 按运行模式过滤后的菜单。独立 / 多租户不提供本进程租户切换。 */
export function visibleNavGroups(): NavGroup[] {
  if (showLocalTenantSettings()) return navGroups;
  return navGroups
    .map((g) => ({
      ...g,
      items: g.items.filter((item) => !LOCAL_TENANT_PATHS.has(item.path)),
    }))
    .filter((g) => g.items.length > 0);
}

export const navItems: NavItem[] = navGroups.flatMap((g) => g.items);

export function activeNavPath(path: string): string | undefined {
  const stripped = path.startsWith('/app') ? `${LINEAGE_HOME}${path.slice(4) || ''}` || LINEAGE_HOME : path;
  let best: string | undefined;
  for (const item of visibleNavGroups().flatMap((g) => g.items)) {
    const hit = item.path === LINEAGE_HOME ? stripped === LINEAGE_HOME : stripped.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}

export function groupOf(path: string): string | undefined {
  const active = activeNavPath(path);
  if (!active) return undefined;
  const group = visibleNavGroups().find((g) => g.items.some((i) => i.path === active));
  return group?.title || undefined;
}
