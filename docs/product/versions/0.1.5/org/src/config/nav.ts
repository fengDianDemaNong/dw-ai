import { generateHref, layerHref, layerIcon } from './layers';
import type { PlatformService, ProductModule } from '../types';
import { productOf } from './products';

export interface NavItem {
  path: string;
  label: string;
  icon: string;
  disabled?: boolean;
  disabledReason?: string;
}

export interface NavGroup {
  title: string;
  items: NavItem[];
}

const MODELING_EXTRA: NavItem[] = [
  { path: '/app/model/validate', label: '规范校验', icon: 'SafetyCertificateOutlined' },
  { path: '/app/model/lineage', label: '影响与血缘', icon: 'PartitionOutlined' },
];

export interface NavBuildOpts {
  specAiDisabled?: boolean;
  licensed?: ProductModule[];
  opened?: ProductModule[];
  visible?: ProductModule[];
  services?: PlatformService[];
  /** 租户自有 DS，不是平台产品 */
  schedulerReady?: boolean;
  schedulerConfigured?: boolean;
  projectAdmin?: boolean;
  myRoles?: Partial<Record<ProductModule, string>>;
}

function serviceOnline(services: PlatformService[] | undefined, code: ProductModule) {
  return services?.some((s) => s.product === code && s.status === 'online');
}

function externalState(
  code: ProductModule,
  opts: NavBuildOpts
): { show: boolean; disabled: boolean; reason?: string } {
  const licensed = opts.licensed ?? [];
  const opened = opts.opened ?? [];
  const visible = opts.visible ?? [];
  if (!licensed.includes(code)) return { show: false, disabled: true, reason: '平台未给本组织开通此产品' };
  if (!opened.includes(code)) return { show: false, disabled: true, reason: '本组织已关闭此模块' };
  if (!visible.includes(code)) return { show: false, disabled: true, reason: '当前权限看不见此模块' };
  if (!serviceOnline(opts.services, code)) {
    return { show: true, disabled: true, reason: '平台没有该产品的在线服务。进程启动后向平台注册地址。' };
  }
  if (!opts.projectAdmin && !opts.myRoles?.[code]) {
    return { show: true, disabled: true, reason: '未派此产品角色。项目管理员在「项目成员」里按产品派角。' };
  }
  return { show: true, disabled: false };
}

function applyState(items: NavItem[], disabled: boolean, reason?: string): NavItem[] {
  if (!disabled) return items;
  return items.map((it) => ({ ...it, disabled: true, disabledReason: reason }));
}

export function buildNavGroups(
  layers: { layer: string }[],
  opts?: NavBuildOpts
): NavGroup[] {
  const o = opts ?? {};
  const groups: NavGroup[] = [
    {
      title: '',
      items: [{ path: '/app', label: '概况', icon: 'DashboardOutlined' }],
    },
    {
      title: '',
      items: [{ path: '/app/members', label: '项目成员', icon: 'TeamOutlined' }],
    },
    {
      title: '',
      items: [{ path: '/app/knowledge', label: '知识库', icon: 'ReadOutlined' }],
    },
  ];

  const wh = externalState('warehouse', o);
  if (wh.show) {
    groups.push({
      title: '规范中心',
      items: applyState(
        [
          {
            path: '/app/spec/copilot',
            label: 'AI 设计规范',
            icon: 'MessageOutlined',
            disabled: Boolean(o.specAiDisabled),
            disabledReason: o.specAiDisabled ? '本组织未开通此项' : undefined,
          },
          { path: '/app/spec/domains', label: '主题域', icon: 'ApartmentOutlined' },
          { path: '/app/spec/layers', label: '分层规范', icon: 'DatabaseOutlined' },
          { path: '/app/spec/grades', label: '数据等级', icon: 'TagOutlined' },
          { path: '/app/spec/roots', label: '词根库', icon: 'BookOutlined' },
          { path: '/app/spec/logic', label: '加工类型', icon: 'ClusterOutlined' },
          { path: '/app/spec/io', label: '导入导出', icon: 'SwapOutlined' },
        ],
        wh.disabled,
        wh.reason
      ),
    });
    groups.push({
      title: '建模中心',
      items: applyState(
        [
          ...layers.map((l) => ({
            path: layerHref(l.layer),
            label: l.layer,
            icon: layerIcon(l.layer),
          })),
          ...MODELING_EXTRA,
        ],
        wh.disabled,
        wh.reason
      ),
    });
  }

  const meta = externalState('metadata', o);
  if (meta.show) {
    groups.push({
      title: '元数据',
      items: applyState(
        [
          { path: '/app/meta/tables', label: '元数据目录', icon: 'TableOutlined' },
          { path: '/app/meta/lineage', label: '作业血缘', icon: 'PartitionOutlined' },
          { path: '/app/meta/analyze', label: 'SQL 解析', icon: 'CodeOutlined' },
          { path: '/app/meta/catalogs', label: '数据目录', icon: 'DatabaseOutlined' },
          { path: '/app/meta/temp-rules', label: '临时表规则', icon: 'FilterOutlined' },
        ],
        meta.disabled,
        meta.reason
      ),
    });
  }

  const whVisible = (o.visible ?? []).includes('warehouse') && (o.opened ?? []).includes('warehouse');
  if (whVisible && o.schedulerConfigured) {
    groups.push({
      title: '调度',
      items: applyState(
        [{ path: '/app/dev/jobs', label: '调度任务', icon: 'ScheduleOutlined' }],
        !o.schedulerReady,
        o.schedulerReady ? undefined : '本组织调度集群未测通。租户管理员到工作台「计算资源」登记 DolphinScheduler。'
      ),
    });
  }

  for (const code of ['quality', 'serve', 'materialize', 'dev'] as ProductModule[]) {
    const st = externalState(code, o);
    if (!st.show) continue;
    const title = productOf(code).label;
    const items: NavItem[] =
      code === 'quality'
        ? [{ path: '/app/dev/quality', label: '质量报告', icon: 'AuditOutlined' }]
        : code === 'serve'
          ? [
              { path: '/app/serve/market', label: '数据市场', icon: 'ShopOutlined' },
              { path: '/app/serve/factory', label: '指标工厂', icon: 'ExperimentOutlined' },
            ]
          : code === 'materialize'
            ? [{ path: '/app/materialize', label: '指标反向建模', icon: 'ThunderboltOutlined' }]
            : [{ path: '/app/dev/lineage', label: '血缘追踪', icon: 'PartitionOutlined' }];
    groups.push({ title, items: applyState(items, st.disabled, st.reason) });
  }

  return groups;
}

export const navGroups: NavGroup[] = buildNavGroups([]);

export function activeNavPath(path: string, groups: NavGroup[] = navGroups): string | undefined {
  const dwd = generateHref('ODS', 'DWD');
  const dws = generateHref('DWD', 'DWS');
  if (dwd && path.startsWith(dwd)) return layerHref('DWD');
  if (dws && path.startsWith(dws)) return layerHref('DWS');
  let best: string | undefined;
  for (const item of groups.flatMap((g) => g.items)) {
    const exact = item.path === '/platform/tenants' || item.path === '/workbench/projects' || item.path === '/app';
    const hit = exact ? path === item.path : path.startsWith(item.path);
    if (hit && (!best || item.path.length > best.length)) best = item.path;
  }
  return best;
}
