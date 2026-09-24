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

/**
 * 数据地图（iframe 内嵌 dw-lineage）的菜单项。
 *
 * <p>`perm` 与数据地图自己的项目级菜单（`dw-lineage/ui/src/config/nav.ts` 的
 * `projectNavGroups`）逐项对齐 —— 同一个页面在两处入口的判据必须是同一个权限词，
 * 否则从工作台进来的和从数据地图侧栏进去的会是两套口径。
 *
 * <p>「元数据服务」不配 perm：它归数据地图的<b>工作台级</b>（全租户一份，不跟项目角色走），
 * 对侧也是谁都能进，写操作由后端拦。
 */
const LINEAGE_NAV: NavItem[] = [
  { path: LINEAGE_PAGES.search, label: '全文检索', icon: 'SearchOutlined', perm: 'catalog:read' },
  { path: LINEAGE_PAGES.tables, label: '血缘', icon: 'PartitionOutlined', perm: 'lineage:read' },
  // 「数据目录」与「临时表规则」是管理页，只有目录管理员进得去 —— 注意它们
  // 不蕴含 catalog:read（见 Perms.java 的注释），两件事。
  { path: LINEAGE_PAGES.catalogs, label: '数据目录', icon: 'FolderOutlined', perm: 'catalog:admin' },
  { path: LINEAGE_PAGES.tempRules, label: '临时表规则', icon: 'FilterOutlined', perm: 'catalog:admin' },
  // SQL 解析：能看要读血缘，页面里的「保存」另外要 lineage:write（那在页面内判）。
  { path: LINEAGE_PAGES.analyze, label: 'SQL 解析', icon: 'CodeOutlined', perm: 'lineage:read' },
  { path: LINEAGE_PAGES.meta, label: '元数据', icon: 'TableOutlined', perm: 'catalog:read' },
  { path: LINEAGE_PAGES.metadata, label: '元数据服务', icon: 'ApiOutlined' },
  { path: LINEAGE_PAGES.settings, label: '数据地图设置', icon: 'SettingOutlined', perm: 'catalog:admin' },
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
    // 入口由租户开通的模块决定：没开数据地图的租户进来必然报错（iframe 那侧没有
    // 这个租户的上下文）。但**不在这里整组抹掉** —— 见 buildNavGroups 里对
    // `owner: 'lineage'` 的单独处理：分组留着，只把里面的项置灰并说明原因。
    module: 'metadata',
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
  // 数据地图整体「点不了」时的原因。三种情况要找的人不一样，所以不能合成一句：
  // 部署形态找运维（这个部署就没有数据地图）、未开通用量找租户管理员、
  // 没派角色找项目管理员。为空 = 没这一层障碍，组内各项再各自按 perm 判。
  const lineageBlocked = !showLineage
    ? '当前部署未包含数据地图'
    : access && !access.hasModule('metadata')
      ? '本组织未开通数据地图'
      : '';
  return navGroups
    .map((g) => {
      // 数据地图分组**始终出现**，点不了就逐项置灰并说明原因。
      //
      // 与「规范中心 / 建模中心」的整组抹掉不同，这里刻意不做成一样：仓建设是本进程的
      // 主服务，没有它这个进程没有意义，抹掉不算丢信息；数据地图是「你可能只是没被派
      // 角色」的地方 —— 抹掉之后「没角色」和「没这个服务」在界面上长得一模一样，
      // 而前者去找项目管理员是要得回来的。这也是 PRD 对数据地图分组的明文要求。
      if (g.owner === 'lineage') {
        return {
          ...g,
          items: g.items.map((item) =>
            lineageBlocked
              ? { ...item, disabled: true, disabledReason: lineageBlocked }
              : access && item.perm && !access.can(item.perm)
                ? { ...item, disabled: true, disabledReason: '当前角色无权访问，请联系项目管理员' }
                : item
          ),
        };
      }
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
