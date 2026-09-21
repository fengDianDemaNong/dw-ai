import type { Perm, ProductModule } from './iam';
import { layerHref, layerIcon } from './layers';
import { LINEAGE_PAGES, MODEL_PAGES, otherProductsVisible } from './pages';
import { LINEAGE_HOME, MODEL_HOME } from './paths';
import { isStandalone } from './runtime';

export interface NavItem {
  path: string;
  label: string;
  icon: string;
  disabled?: boolean;
  disabledReason?: string;
}

export interface NavGroup {
  title: string;
  module?: ProductModule;
  perm?: Perm;
  owner?: 'model' | 'lineage';
  items: NavItem[];
}

function modelingExtra(layers: { layer: string }[]): NavItem[] {
  const codes = new Set(layers.map((l) => l.layer.toUpperCase()));
  const extra: NavItem[] = [
    { path: `${MODEL_HOME}/validate`, label: '规范校验', icon: 'SafetyCertificateOutlined' },
  ];
  if (codes.has('ODS') && codes.has('DWD')) {
    extra.push({ path: `${MODEL_HOME}/ods-dwd`, label: '从 ODS 生成', icon: 'ThunderboltOutlined' });
  }
  if (codes.has('DWD') && codes.has('DWS')) {
    extra.push({ path: `${MODEL_HOME}/dwd-dws`, label: '从 DWD 生成', icon: 'ClusterOutlined' });
  }
  return extra;
}

const LINEAGE_NAV: NavItem[] = [
  { path: LINEAGE_PAGES.search, label: '全文检索', icon: 'SearchOutlined' },
  { path: LINEAGE_PAGES.tables, label: '血缘', icon: 'PartitionOutlined' },
  { path: LINEAGE_PAGES.catalogs, label: '数据目录', icon: 'FolderOutlined' },
  { path: LINEAGE_PAGES.tempRules, label: '临时表规则', icon: 'FilterOutlined' },
  { path: LINEAGE_PAGES.analyze, label: 'SQL 解析', icon: 'CodeOutlined' },
  { path: LINEAGE_PAGES.meta, label: '元数据', icon: 'TableOutlined' },
  { path: LINEAGE_PAGES.metadata, label: '元数据服务', icon: 'ApiOutlined' },
  { path: LINEAGE_PAGES.settings, label: '数据地图设置', icon: 'SettingOutlined' },
];

/** 规范中心、建模中心为本进程；数据地图仅 multi 下 iframe 嵌 dw-lineage。 */
export const navGroups: NavGroup[] = [
  {
    title: '',
    owner: 'model',
    items: [{ path: MODEL_HOME, label: '概况', icon: 'DashboardOutlined' }],
  },
  {
    title: '',
    owner: 'model',
    perm: 'iam:member',
    items: [{ path: MODEL_PAGES.members, label: '项目成员', icon: 'TeamOutlined' }],
  },
  {
    title: '',
    owner: 'model',
    items: [{ path: MODEL_PAGES.knowledge, label: '知识库', icon: 'ReadOutlined' }],
  },
  {
    title: '规范中心',
    owner: 'model',
    module: 'warehouse',
    perm: 'spec:read',
    items: [
      { path: `${MODEL_HOME}/spec/copilot`, label: 'AI 设计规范', icon: 'MessageOutlined' },
      { path: `${MODEL_HOME}/spec/domains`, label: '主题域', icon: 'ApartmentOutlined' },
      { path: `${MODEL_HOME}/spec/layers`, label: '分层规范', icon: 'DatabaseOutlined' },
      { path: `${MODEL_HOME}/spec/grades`, label: '数据等级', icon: 'TagOutlined' },
      { path: `${MODEL_HOME}/spec/roots`, label: '词根库', icon: 'BookOutlined' },
      { path: `${MODEL_HOME}/spec/logic`, label: '加工类型', icon: 'ClusterOutlined' },
      { path: `${MODEL_HOME}/spec/io`, label: '导入导出', icon: 'SwapOutlined' },
    ],
  },
  {
    title: '建模中心',
    owner: 'model',
    module: 'warehouse',
    perm: 'model:read',
    items: modelingExtra([]),
  },
  {
    title: '数据地图',
    owner: 'lineage',
    items: LINEAGE_NAV,
  },
];

export function buildNavGroups(
  layers: { layer: string }[],
  access?: {
    hasModule: (m: ProductModule) => boolean;
    can: (p: Perm) => boolean;
    specAiDisabled?: boolean;
  }
): NavGroup[] {
  const showLineage = otherProductsVisible();
  return navGroups
    .map((g) => {
      if (g.owner === 'lineage' && !showLineage) return null;
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
          ...modelingExtra(layers),
        ],
      };
    })
    .filter((g): g is NavGroup => Boolean(g));
}

export function activeNavPath(path: string, groups: NavGroup[] = navGroups): string | undefined {
  let best: string | undefined;
  for (const item of groups.flatMap((g) => g.items)) {
    const hit = item.path === MODEL_HOME || item.path === LINEAGE_HOME
      ? path === item.path
      : path.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}
