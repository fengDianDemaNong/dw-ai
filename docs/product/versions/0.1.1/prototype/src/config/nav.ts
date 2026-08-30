import { generateHref, layerHref, layerIcon } from './layers';

export interface NavItem {
  path: string;
  label: string;
  icon: string;
}

export interface NavGroup {
  title: string;
  items: NavItem[];
}

const MODELING_EXTRA: NavItem[] = [
  { path: '/w/model/validate', label: '规范校验', icon: 'SafetyCertificateOutlined' },
];

export const navGroups: NavGroup[] = [
  {
    title: '',
    items: [{ path: '/w', label: '概况', icon: 'DashboardOutlined' }],
  },
  {
    title: '',
    items: [{ path: '/w/members', label: '项目成员', icon: 'TeamOutlined' }],
  },
  {
    title: '规范中心',
    items: [
      { path: '/w/spec/copilot', label: 'AI 设计规范', icon: 'MessageOutlined' },
      { path: '/w/spec/domains', label: '主题域', icon: 'ApartmentOutlined' },
      { path: '/w/spec/layers', label: '分层规范', icon: 'DatabaseOutlined' },
      { path: '/w/spec/grades', label: '数据等级', icon: 'TagOutlined' },
      { path: '/w/spec/roots', label: '词根库', icon: 'BookOutlined' },
      { path: '/w/spec/io', label: '导入导出', icon: 'SwapOutlined' },
    ],
  },
  {
    title: '建模中心',
    items: [...MODELING_EXTRA],
  },
  {
    title: '开发中心',
    items: [
      { path: '/w/dev/jobs', label: '任务调度', icon: 'ScheduleOutlined' },
      { path: '/w/dev/quality', label: '数据质量', icon: 'AuditOutlined' },
      { path: '/w/dev/lineage', label: '血缘追踪', icon: 'PartitionOutlined' },
    ],
  },
  {
    title: '沉淀优化',
    items: [{ path: '/w/materialize', label: '指标反向建模', icon: 'ThunderboltOutlined' }],
  },
  {
    title: '数据服务',
    items: [
      { path: '/w/service/catalog', label: '指标目录', icon: 'AppstoreOutlined' },
      { path: '/w/service/factory', label: '指标工厂', icon: 'BlockOutlined' },
      { path: '/w/service/gateway', label: 'API 网关', icon: 'ApiOutlined' },
      { path: '/w/service/market', label: '指标市场', icon: 'ShopOutlined' },
    ],
  },
  {
    title: '数据服务（新）',
    items: [
      { path: '/w/serve/market', label: '数据市场', icon: 'ShopOutlined' },
      { path: '/w/serve/factory', label: '指标工厂', icon: 'ExperimentOutlined' },
    ],
  },
];

export function buildNavGroups(layers: { layer: string }[]): NavGroup[] {
  return navGroups.map((g) => {
    if (g.title !== '建模中心') return g;
    return {
      title: g.title,
      items: [
        ...layers.map((l) => ({
          path: layerHref(l.layer),
          label: l.layer,
          icon: layerIcon(l.layer),
        })),
        ...MODELING_EXTRA,
      ],
    };
  });
}

export function activeNavPath(path: string, groups: NavGroup[] = navGroups): string | undefined {
  const dwd = generateHref('ODS', 'DWD');
  const dws = generateHref('DWD', 'DWS');
  if (dwd && path.startsWith(dwd)) return layerHref('DWD');
  if (dws && path.startsWith(dws)) return layerHref('DWS');
  let best: string | undefined;
  for (const item of groups.flatMap((g) => g.items)) {
    const exact = item.path === '/admin' || item.path === '/projects' || item.path === '/w';
  const hit = exact ? path === item.path : path.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}
