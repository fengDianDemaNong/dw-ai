import { ORG_HOME, ORG_PAGES } from './pages';
import { SYS_HOME } from './paths';
import { navIcons } from './navIcons';
import { isEmbeddable } from './products';
import type { NavGroup, NavItem } from './nav';
import type { NavGroupRow, ProductMenu } from '../api/client';

/**
 * 侧栏渲染要用到的分组元数据（`nav_groups` 的投影）。
 *
 * <p>只挑这三样：分组在侧栏里的「身份」仍是标题字符串（`nav_items.group_title`），
 * 分组表提供的是标题之外的**顺序**与**空组策略**。刻意不含 `scope` / `product` ——
 * 调用方已按壳过滤好（见 `stores/app.ts` 的两个 computed），这里不再判一次，
 * 免得两处各有一套「哪些分组算这个壳的」的规则。
 */
export type GroupMeta = Pick<NavGroupRow, 'title' | 'sortOrder' | 'emptyPolicy'>;

export function buildSysNav(tenantAdmin = true): NavGroup[] {
  return [
    {
      title: '系统管理',
      items: [
        ...(tenantAdmin
          ? [
              { path: ORG_PAGES.users, label: '用户管理', icon: 'TeamOutlined' },
              { path: ORG_PAGES.roles, label: '角色管理', icon: 'SafetyCertificateOutlined' },
            ]
          : []),
        { path: ORG_PAGES.workbench, label: '项目管理', icon: 'AppstoreOutlined' },
        ...(tenantAdmin
          ? [
              { path: ORG_PAGES.knowledge, label: '知识库', icon: 'ReadOutlined' },
              { path: ORG_PAGES.settings, label: '设置', icon: 'SettingOutlined' },
            ]
          : []),
      ],
    },
  ];
}

export function buildAdminNav(): NavGroup[] {
  return [
    {
      title: '平台管理',
      items: [
        { path: ORG_PAGES.platformTenants, label: '租户', icon: 'BankOutlined' },
        { path: ORG_PAGES.platformServices, label: '服务注册', icon: 'ApiOutlined' },
        { path: ORG_PAGES.platformNav, label: '菜单管理', icon: 'PartitionOutlined' },
        { path: ORG_PAGES.platformProductRoles, label: '产品角色', icon: 'SafetyCertificateOutlined' },
        { path: ORG_PAGES.platformUsers, label: '平台用户', icon: 'TeamOutlined' },
        { path: ORG_PAGES.platformSettings, label: '设置', icon: 'SettingOutlined' },
      ],
    },
  ];
}

/**
 * 产品页面的菜单项类型直接引用 `api/client` 的那一份 ——
 * 曾经在这里重复声明过，两处字段一旦不同步就是「后端多给一个字段、前端静默丢掉」。
 */

/**
 * 能嵌进壳的产品清单见 `config/products.ts` 的 `isEmbeddable`（与产品码中文名同处一份，
 * 免得两处各写一个 `'metadata'` 字面量）。不在这里再列一遍的原因见 {@link toNavItem}：
 * 侧栏要按「能不能嵌」分流出 iframe 链接与整页跳转链接。
 */

/** 产品码 → 兜底图标：菜单项没配 icon、或配了个 `navIcons` 里不存在的名字时用。 */
const PRODUCT_ICON: Record<string, string> = {
  warehouse: 'AppstoreOutlined',
  metadata: 'DatabaseOutlined',
  quality: 'AuditOutlined',
  serve: 'ApiOutlined',
};

function iconOf(menu: ProductMenu): string {
  if (menu.icon && navIcons[menu.icon]) return menu.icon;
  return PRODUCT_ICON[menu.product] ?? 'AppstoreOutlined';
}

/** 没配分组标题的菜单落在这一组（沿用旧行为，别让它们散落在最上面）。 */
const DEFAULT_GROUP = '产品';

/**
 * 门户菜单 → 侧栏项。
 *
 * <p><b>许可过滤只做一次，在服务端</b>（`NavController` 按 `tenant_licenses.modules` 过滤）。
 * 这里刻意不再按 `hasModule` 过滤一遍：两个过滤器意味着「侧栏空白」有两种互不相干的
 * 成因（服务端许可没配 / 前端许可没加载），排查时得同时怀疑两处，而且前端那一遍
 * 一旦因为许可数据没到位而过滤掉全部菜单，症状和「真的没配菜单」完全一样。
 *
 * <p>路径在这里<b>一次拼成 org 的完整路由</b>（`/org/embed/{product}{path}`），
 * 不留到渲染时：`AppNav` 的 `router-link :to` 与高亮（`activeNavPath` 最长前缀匹配）
 * 都吃这个值，填子应用路径会点进一个不存在的路由、并且高亮永远匹配不上。
 */
function toNavItem(menu: ProductMenu, embedBase: string): NavItem {
  const sub = menu.path.startsWith('/') ? menu.path : `/${menu.path}`;
  const base = { path: `${embedBase}/${menu.product}${sub}`, label: menu.label, icon: iconOf(menu) };

  if (isEmbeddable(menu.product)) return base;
  // 没有子端 → 整页跳到它自己的站点，而不是塞进壳里当空壳。
  // 地址拼法照抄 `productEmbedUrl` / `openWarehouseApp` 的 `origin + path`：
  // 只跳 `frontendUrl` 会落到那个站点的**首页**，而用户点的是侧栏里具体的一项
  // （点「项目成员」应当到成员页）。菜单报上来的 `path` 本就是子应用内的绝对路径。
  if (menu.frontendUrl) {
    const origin = menu.frontendUrl.replace(/\/+$/, '');
    return { ...base, href: `${origin}${sub}`, external: true };
  }
  // 连地址都没配：点下去只会弹回首页，那还不如明说
  return { ...base, disabled: true, disabledReason: '未配置前端地址（见「服务注册」）' };
}

/** `always` 空组里的占位项：这一组照常出现，但里面没得点。 */
function emptyGroupItem(): NavItem {
  return {
    // 空串是有意的：它不是一个可去的地址。`activeNavPath` 必须跳过 disabled 项，
    // 否则空串会成为「最短前缀」命中任意路径（见 config/nav.ts）。
    path: '',
    label: '暂无可用的入口',
    // 图标名必须取自 config/navIcons.ts —— 写一个不在表里的名字不会报错，只会静默不渲染图标。
    icon: 'BlockOutlined',
    disabled: true,
    disabledReason: '该产品未对本租户开通，或你还没有对应角色',
  };
}

/**
 * 按「分组标题」把菜单切成侧栏分组。
 *
 * <p>组内保持服务端给的顺序（`sort_order` 再 `path`）。不另排一次「组内按 sort 重排」：
 * 管理员看到的排序号是各自分组内的一串（候选生成时每款产品的每组从 10 开始），
 * 一旦跨组比较就会得出与界面上不同的顺序。
 *
 * <p><b>组间顺序</b>由 `nav_groups`（「菜单管理 → 分组管理」）决定，两条规则：
 * 登记过的按登记的 `sortOrder` 升序在前，没登记的按<b>首次出现序</b>跟在后面。
 * 一句话：「你登记过的分组按你排的顺序在前，没登记过的（各服务按项目分层动态生成的，
 * 如仓建设的「建模中心」）按原样跟在后面」。
 *
 * <p><b>空组</b>（一个可用菜单项都没有）按该组的 `empty_policy`：`hide` 整组不产出
 * （这是升级前的现状，也是新表的默认值），`always` 产出一条占位项。
 *
 * <p>分组在侧栏里是按**标题**合并的（现状如此，不改），而分组元数据是按「壳 + 产品」登记的，
 * 所以同名分组可能来自两条登记：`sortOrder` 取最小的、策略取**最宽松**的（任一为
 * `always` 即 `always` —— 「这一组始终出现」是对用户更强的承诺，不该被另一条登记削弱）。
 */
function groupMenus(menus: ProductMenu[], embedBase: string, metas: GroupMeta[]): NavGroup[] {
  const byTitle = new Map<string, NavItem[]>();
  /** 未登记分组的「首次出现序」基准 —— 只对出现过的标题有意义。 */
  const firstSeen: string[] = [];
  for (const menu of menus) {
    const title = (menu.groupTitle || '').trim() || DEFAULT_GROUP;
    const bucket = byTitle.get(title);
    if (bucket) bucket.push(toNavItem(menu, embedBase));
    else {
      byTitle.set(title, [toNavItem(menu, embedBase)]);
      firstSeen.push(title);
    }
  }

  const meta = new Map<string, { sortOrder: number; emptyPolicy: GroupMeta['emptyPolicy'] }>();
  for (const m of metas) {
    const prev = meta.get(m.title);
    if (!prev) meta.set(m.title, { sortOrder: m.sortOrder, emptyPolicy: m.emptyPolicy });
    else {
      prev.sortOrder = Math.min(prev.sortOrder, m.sortOrder);
      if (m.emptyPolicy === 'always') prev.emptyPolicy = 'always';
    }
  }

  // 有菜单项的分组 ∪ 只登记了分组、还没有菜单项的分组（后者否则在侧栏里根本看不见，
  // 管理员建完会以为没保存成功）。
  const titles = [...byTitle.keys(), ...[...meta.keys()].filter((t) => !byTitle.has(t))];
  titles.sort((a, b) => {
    const ma = meta.get(a);
    const mb = meta.get(b);
    if (!!ma !== !!mb) return ma ? -1 : 1;
    if (ma && mb) return ma.sortOrder - mb.sortOrder || a.localeCompare(b, 'zh');
    return firstSeen.indexOf(a) - firstSeen.indexOf(b);
  });

  const groups: NavGroup[] = [];
  for (const title of titles) {
    const items = byTitle.get(title);
    if (items?.length) groups.push({ title, items });
    else if (meta.get(title)?.emptyPolicy === 'always') groups.push({ title, items: [emptyGroupItem()] });
  }
  return groups;
}

/**
 * 工作台壳（`/org/workbench/*`，进项目**之前**那一级）的产品菜单。
 *
 * <p>只取 `scope === 'workbench'` 的那些。归属由平台管理员在「菜单管理」里指定 ——
 * 各服务报上来的候选里那个 `scope` 只是建议值。
 *
 * <p>`groups` 传该壳的分组元数据（`stores/app.ts` 的 `workbenchNavGroups`）。
 * 不给默认值：调用点只有一处，忘了传的表现是「登记的顺序与空组策略静默失效」，
 * 而一个 `= []` 的默认值会让它一直不被发现。
 */
export function buildWorkbenchProductNav(menus: ProductMenu[], groups: GroupMeta[]): NavGroup[] {
  return groupMenus(
    menus.filter((m) => m.scope === 'workbench'),
    `${ORG_HOME}/embed`,
    groups
  );
}

/** 项目壳的固定项。产品菜单之外必须有这一条，否则进了项目就没有回去的路。 */
export function buildProjectSysNav(): NavGroup[] {
  return [
    {
      title: '项目',
      items: [{ path: SYS_HOME, label: '返回工作台', icon: 'AppstoreOutlined' }],
    },
  ];
}

/**
 * 项目壳（`/org/project/{项目code}/*`，进项目**之后**那一级）的产品菜单。
 *
 * <p>只取 `scope === 'project'` 的那些。路径比工作台壳多一级**项目码** ——
 * 项目壳里换项目等于换一套页面，把项目码放进地址才能刷新后停在同一个项目上。
 *
 * <p>`groups` 同 {@link buildWorkbenchProductNav}，传项目壳那一份。
 */
export function buildProjectProductNav(
  menus: ProductMenu[],
  projectCode: string,
  groups: GroupMeta[]
): NavGroup[] {
  return groupMenus(
    menus.filter((m) => m.scope === 'project'),
    `${ORG_HOME}/project/${encodeURIComponent(projectCode)}/embed`,
    groups
  );
}
