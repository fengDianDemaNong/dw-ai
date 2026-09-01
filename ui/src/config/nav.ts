import type { Perm, ProductModule } from './iam';
import { layerHref, layerIcon } from './layers';

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
  items: NavItem[];
}

function modelingExtra(layers: { layer: string }[]): NavItem[] {
  const codes = new Set(layers.map((l) => l.layer.toUpperCase()));
  const extra: NavItem[] = [
    { path: '/w/model/validate', label: '规范校验', icon: 'SafetyCertificateOutlined' },
  ];
  if (codes.has('ODS') && codes.has('DWD')) {
    extra.push({ path: '/w/model/ods-dwd', label: '从 ODS 生成', icon: 'ThunderboltOutlined' });
  }
  if (codes.has('DWD') && codes.has('DWS')) {
    extra.push({ path: '/w/model/dwd-dws', label: '从 DWD 生成', icon: 'ClusterOutlined' });
  }
  return extra;
}

/** 运行时只开放已交付的规范中心、建模中心；其余模块仅在产品原型中。 */
export const navGroups: NavGroup[] = [
  {
    title: '',
    items: [{ path: '/w', label: '概况', icon: 'DashboardOutlined' }],
  },
  {
    title: '',
    perm: 'iam:member',
    items: [{ path: '/w/members', label: '项目成员', icon: 'TeamOutlined' }],
  },
  {
    title: '',
    items: [{ path: '/w/knowledge', label: '知识库', icon: 'ReadOutlined' }],
  },
  {
    title: '规范中心',
    module: 'warehouse',
    perm: 'spec:read',
    items: [
      { path: '/w/spec/copilot', label: 'AI 设计规范', icon: 'MessageOutlined' },
      { path: '/w/spec/domains', label: '主题域', icon: 'ApartmentOutlined' },
      { path: '/w/spec/layers', label: '分层规范', icon: 'DatabaseOutlined' },
      { path: '/w/spec/grades', label: '数据等级', icon: 'TagOutlined' },
      { path: '/w/spec/roots', label: '词根库', icon: 'BookOutlined' },
      { path: '/w/spec/logic', label: '加工类型', icon: 'ClusterOutlined' },
      { path: '/w/spec/io', label: '导入导出', icon: 'SwapOutlined' },
    ],
  },
  {
    title: '建模中心',
    module: 'warehouse',
    perm: 'model:read',
    items: modelingExtra([]),
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
  return navGroups
    .map((g) => {
      if (access) {
        if (g.module && !access.hasModule(g.module)) return null;
        if (g.perm && !access.can(g.perm)) return null;
      }
      const items =
        g.title === '规范中心'
          ? g.items.map((item) =>
              item.path === '/w/spec/copilot'
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
    const hit = item.path === '/w' ? path === '/w' : path.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}
