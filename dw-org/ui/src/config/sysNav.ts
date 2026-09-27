import { ORG_HOME, ORG_PAGES } from './pages';
import { navIcons } from './navIcons';
import { isEmbeddable } from './products';
import { liveChildren } from './navMount';
import type { NavItem } from './nav';
import type { NavNodeRow } from '../api/client';

/**
 * 两个租户壳。平台壳（`/org/platform/*`）不在其中 —— 它不进库，见 {@link buildAdminNav}。
 */
export type ShellScope = 'workbench' | 'project';

export interface ShellCtx {
  scope: ShellScope;
  /**
   * 项目壳才有值。两处要用它：org 自有节点路径里的 `{code}` 占位符
   * （种子里的「成员管理」= `/org/project/{code}/members`），以及产品页面的嵌入前缀。
   */
  projectCode: string;
}

/**
 * 平台管理壳的菜单（前端硬编码，**不进库**）。
 *
 * <p>这是一处刻意的不对称：两个租户壳的菜单都在 `nav_nodes` 里、可以在「菜单管理」里
 * 编排，而平台壳这几项不是。理由是**把「菜单管理」自己做成一条可删的数据库行，
 * 删掉之后就再也进不去那个页面了**；而且它只有平台管理员可见，与租户侧栏本就是两回事。
 *
 * <p>返回 `NavItem[]` 而不是带标题的分组：「平台管理」这个标题在侧栏里是多余的 ——
 * 平台壳只有这一套菜单，分组（V23 起就是「一层目录节点」）在这里没有对应物。
 */
export function buildAdminNav(): NavItem[] {
  return [
    { path: ORG_PAGES.platformTenants, label: '租户', icon: 'BankOutlined' },
    { path: ORG_PAGES.platformServices, label: '服务注册', icon: 'ApiOutlined' },
    { path: ORG_PAGES.platformNav, label: '菜单管理', icon: 'PartitionOutlined' },
    { path: ORG_PAGES.platformProductRoles, label: '产品角色', icon: 'SafetyCertificateOutlined' },
    { path: ORG_PAGES.platformUsers, label: '平台用户', icon: 'TeamOutlined' },
    { path: ORG_PAGES.platformSettings, label: '设置', icon: 'SettingOutlined' },
  ];
}

/**
 * 产品码 → 兜底图标：菜单项没配 icon、或配了个 `navIcons` 里不存在的名字时用。
 *
 * <p>能嵌进壳的产品清单在 `config/products.ts` 的 `isEmbeddable`（与产品码中文名同处一份，
 * 免得两处各写一个 `'metadata'` 字面量）。
 */
const PRODUCT_ICON: Record<string, string> = {
  warehouse: 'AppstoreOutlined',
  metadata: 'DatabaseOutlined',
  quality: 'AuditOutlined',
  serve: 'ApiOutlined',
};

/** 图标名必须取自 `config/navIcons.ts` —— 表里没有的名字不报错，只是静默不渲染图标。 */
function iconOf(node: NavNodeRow): string {
  if (node.icon && navIcons[node.icon]) return node.icon;
  return PRODUCT_ICON[node.product] ?? 'AppstoreOutlined';
}

/** 产品在这个壳里的嵌入前缀。项目壳多一级项目码 —— 换项目等于换一套页面。 */
function embedBase(ctx: ShellCtx): string {
  return ctx.scope === 'project'
    ? `${ORG_HOME}/project/${encodeURIComponent(ctx.projectCode)}/embed`
    : `${ORG_HOME}/embed`;
}

/**
 * 服务端的菜单树 → 侧栏渲染树。
 *
 * <p><b>服务端一次返回所有壳的顶层节点</b>（每项带 `scope`，见 `NavNodeService.treeFor`），
 * 所以这里按 `ctx.scope` 挑一层。<b>只挑顶层</b>：产品子节点自报的 `scope` 可能与壳不同
 * （归属由平台管理员在「菜单管理」里定），递归时再按 scope 过滤会把它们整支吞掉 ——
 * 而它们已经挂在树里了，挂在哪就是哪。
 *
 * <p><b>权限、许可、空目录策略都在服务端收口</b>（`NavNodeService.render`：该产品没开通
 * 的整支不返回、这个人判不动的产品节点不返回、判不动的 org 自有节点置灰、空目录按
 * `empty_policy` 处理或给占位项）。这里**不再判一次** —— 两个过滤器意味着「侧栏空白」
 * 有两种互不相干的成因，排查时得同时怀疑两处。
 *
 * <p>路径在这里一次拼成 org 的完整路由，不留到渲染时：`AppNav` 的 `router-link :to`
 * 与高亮（`activeNavPath` 最长前缀匹配）都吃这个值，填子应用路径会点进一个不存在的路由、
 * 并且高亮永远匹配不上。
 */
export function toNavItems(nodes: NavNodeRow[], ctx: ShellCtx): NavItem[] {
  return convertAll(nodes.filter((n) => n.scope === ctx.scope), ctx);
}

function convertAll(nodes: NavNodeRow[], ctx: ShellCtx): NavItem[] {
  const out: NavItem[] = [];
  for (const node of nodes) {
    const item = convert(node, ctx);
    if (item) out.push(item);
  }
  return out;
}

/** 一个服务端节点 → 侧栏项；`null` = 这一项不出现（服务端不该产出这种，防御性返回）。 */
function convert(node: NavNodeRow, ctx: ShellCtx): NavItem | null {
  // 挂载节点的子树有**两个**来源，优先用产品运行期报上来的那一份：
  // 服务端折出来的是静态清单（`{frontendUrl}/menu.json`），它只能表达「对所有项目
  // 都成立」的项；而「建模中心」下有哪些分层入口取决于这个项目在「分层规范」里登记了
  // 什么（见 `config/navMount.ts`）。产品没报过（版本旧、或消息还没到）就沿用静态那份 ——
  // `liveChildren` 返回 `undefined` 时回落，不会因为消息没来就变成空目录。
  const live = node.mounted ? liveChildren(node, ctx.scope, ctx.projectCode) : undefined;
  const kids = convertAll(live ?? node.children ?? [], ctx);
  const base: NavItem = { id: node.id, label: node.label, icon: iconOf(node), path: '' };
  // 子节点只在非空时挂上去：空数组会让「这是不是目录」的判断从 `!children` 变成
  // `children.length > 0`，两处判据不一致就是一类「空目录渲染成可点项」的 bug。
  const withKids = kids.length ? { children: kids } : {};

  // 服务端置灰的项（org 自有节点判不动、`always` 空目录的占位项）：原样搬运，
  // 文案由服务端给 —— 前端再写一份，两处措辞迟早不一致。
  if (node.disabled) {
    return { ...base, ...withKids, disabled: true, disabledReason: node.disabledReason || '' };
  }

  // org 自己的页面：`path` 已经是 org 的完整路由，替换掉运行期才知道的项目码即可。
  if (!node.product) {
    const path = (node.path || '').split('{code}').join(encodeURIComponent(ctx.projectCode));
    return { ...base, ...withKids, path };
  }

  const sub = node.path ? (node.path.startsWith('/') ? node.path : `/${node.path}`) : '';

  // 目录型产品节点（挂载了产品树里的一层目录）：自己不可点，内容在 children 里。
  if (!sub) return { ...base, ...withKids, path: '' };

  const path = `${embedBase(ctx)}/${node.product}${sub}`;

  if (isEmbeddable(node.product)) return { ...base, ...withKids, path };

  // 没有子端 → 整页跳到它自己的站点，而不是塞进壳里当空壳。
  // 地址拼法照抄 `productEmbedUrl` / `openWarehouseApp` 的 `origin + path`：
  // 只跳 `frontendUrl` 会落到那个站点的**首页**，而用户点的是侧栏里具体的一项
  // （点「项目成员」应当到成员页）。菜单报上来的 `path` 本就是子应用内的绝对路径。
  if (node.frontendUrl) {
    const origin = node.frontendUrl.replace(/\/+$/, '');
    return { ...base, ...withKids, path, href: `${origin}${sub}`, external: true };
  }
  // 连地址都没配：点下去只会弹回首页，那还不如明说
  return { ...base, ...withKids, path, disabled: true, disabledReason: '未配置前端地址（见「服务注册」）' };
}
