import { layerHref, layerIcon } from './layers';
import type { NavGroup } from './nav';
import { MODEL_PAGES } from './pages';
import { MODEL_HOME } from './paths';

/**
 * 导航的**纯数据**（`nav.ts` 只剩组装与过滤逻辑）。
 *
 * <h2>为什么要单独一个文件</h2>
 *
 * 组织平台要能「从各服务获取菜单」，而浏览器里的菜单是打包进 bundle 的、运行期取不到。
 * `ui/scripts/gen-menu.mjs` 会在构建前 import 本文件，写出 `public/menu.json`；
 * 组织平台按服务登记的**前端地址**去拉 `{frontendUrl}/menu.json`，作为配菜单的候选。
 *
 * <h2>本文件的约束：能被 Node 直接 bundle</h2>
 *
 * 脚本是在 Node 里跑 esbuild 把本文件打包后 import 的，所以这里只能有
 * **纯常量、纯函数与本文件内的类型**。`./pages` 与 `./paths` 是允许的
 * （它们顶层只有路径常量与函数定义，不碰 `window`）。若这里引用了 `authState` /
 * `stores/*` / `@dw-ai/engine` 之类的东西，`npm run build` 会在跑 vue-tsc
 * 之前就挂在生成清单这一步。
 *
 * <p>路径一律引用 `./pages` / `./paths` 的常量而不是重写字面量：那是同一个路径的
 * **唯一真源**，重写一份的话，那边改了这里不会跟着变，而症状是壳里点了菜单落空。
 *
 * <h2>两条已知局限，配菜单时要知道</h2>
 *
 * <ul>
 *   <li><b>菜单变更要重新构建前端才进候选。</b>这符合规格里「菜单是产品信息架构、
 *       跟版本走」——`menu.json` 随构建产物发布，不是运行期可改的配置。</li>
 *   <li><b>管理员自建的层码进不了静态清单。</b>候选里的分层入口报的是产品**内置认识**
 *       的那几个层码（{@link BUILTIN_LAYER_CODES}）—— 它们有图标、有配色、页面也认。
 *       若某个项目在「分层规范」里自建了别的层码（如 `MID`），静态文件里表达不了：
 *       要让它进候选，得由组织平台支持**运行期动态菜单**（壳渲染时向子应用拉一次
 *       本项目实际的分层），那是比「构建期导出清单」大一层的改动。</li>
 * </ul>
 */

/**
 * 本服务的产品码。
 *
 * <p>与 `config/iam.ts` 的 `PRODUCT`、后端 `/api/runtime` 报出去的 `product` 是同一个值。
 * 刻意不 import 那边：`iam.ts` 顶层就 import 了 `@dw-ai/engine`（连同 xlsx），
 * 而本文件要保持在「Node 里 bundle 得动」的范围内。
 */
export const PRODUCT = 'warehouse';

/**
 * 本产品的权限词**全集**，供组织平台的两个下拉使用（菜单挂哪个权限、产品角色勾哪些权限）。
 *
 * <h2>为什么要显式列，不能由组织平台从菜单里聚合</h2>
 *
 * 有些词**不在任何菜单上**：{@code spec:write} / {@code model:write} /
 * {@code model:publish} 都不挂在菜单项上（页面的写操作在页面内判），
 * 而「产品角色」页需要勾它们。聚合菜单的话，「规范管理员」这个角色永远配不出写权限。
 *
 * <h2>为什么要带人话标签</h2>
 *
 * 组织平台上配菜单的是平台管理员，不是本产品的开发者。给他看 {@code model:publish}
 * 他得猜；看「发布模型」不用猜。标签是**本产品自己的说法**，组织平台原样展示、不自己编一套。
 *
 * <p>标签为什么带域（「查看规范」而不是「查看」）：同义词会有多个 —— 查看规范与
 * 查看模型是两个不同的词，都叫「查看」的话下拉里会出现两条一模一样的选项。
 *
 * <p><b>改了这里要重新构建前端</b>（`npm run build` 会跑 `scripts/gen-menu.mjs` 重写
 * `public/menu.json`）：词表随产物发布，不是运行期可改的配置。
 *
 * <p>词表里的每个词都必须能被后端认作合法形状（`域:动作`，动作是
 * `read/write/admin/publish/member` 之一）—— 清单里有非法词会让**整份菜单候选拉取失败**
 * （组织平台整份拒，见 `MenuCandidateService.parsePerms`），不只是少一个词。
 * `scripts/gen-menu.mjs` 在构建期就拦这条。
 */
export const PERM_OPTIONS: PermOption[] = [
  { value: 'spec:read', label: '查看规范' },
  { value: 'spec:write', label: '编辑规范' },
  { value: 'model:read', label: '查看模型' },
  { value: 'model:write', label: '编辑模型' },
  { value: 'model:publish', label: '发布模型' },
  { value: 'iam:member', label: '成员管理' },
];

export interface PermOption {
  value: string;
  label: string;
}

/**
 * 建模中心里那些**随项目分层变化**的项。
 *
 * <p>这一组是「运行期动态项进不了静态清单」的具体出处，所以它虽然在数据文件里，
 * 却不参与候选导出（见 {@link MENU_CANDIDATES}）。
 */
export function buildModelingItems(layers: { layer: string }[]): NavItem[] {
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

type NavItem = NavGroup['items'][number];

/**
 * 「概况 / 项目成员 / 知识库」三个**不分组**的顶层项。
 *
 * <p>它们各自成组（组标题为空串）而不是合成一组：`buildNavGroups` 的过滤是<b>整组</b>
 * 级的（`module` / `perm` 任一不满足就整组消失），而这三个的可见条件并不相同 ——
 * 「项目成员」要 `iam:member` 且 standalone 下不出现，另外两个谁都能进。
 * 合成一组会让「没有 iam:member」把概况与知识库一起带走。
 */
const GROUPS_TOP: NavGroup[] = [
  {
    title: '',
    items: [{ path: MODEL_HOME, label: '概况', icon: 'DashboardOutlined' }],
  },
  {
    title: '',
    perm: 'iam:member',
    items: [{ path: MODEL_PAGES.members, label: '项目成员', icon: 'TeamOutlined' }],
  },
  {
    title: '',
    items: [{ path: MODEL_PAGES.knowledge, label: '知识库', icon: 'ReadOutlined' }],
  },
];

/** 规范中心：整组要 `spec:read`（见 `buildNavGroups` 的组级过滤）。 */
const GROUP_SPEC: NavGroup = {
  title: '规范中心',
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
};

/**
 * 建模中心：整组要 `model:read`，组内各项**在渲染时**被 {@link buildModelingItems}
 * 换成分层项 —— 这里写的 `buildModelingItems([])` 只是初始值（只剩「规范校验」）。
 */
const GROUP_MODELING: NavGroup = {
  title: '建模中心',
  module: 'warehouse',
  perm: 'model:read',
  items: buildModelingItems([]),
};

/**
 * 本进程（仓建设）自己的菜单。
 *
 * <p><b>侧栏就是这一份，没有「其他产品」那一档。</b>数据地图、数据规范那些页面
 * 归组织平台的项目壳整合（壳从各服务拉菜单、按 `scope` 摆到工作台或项目那一层），
 * 本进程不再以 iframe 嵌别人的页面 —— 以前这里还拼过一组 `owner: 'lineage'` 的
 * 数据地图菜单，整组已删。
 */
export const MODEL_GROUPS: NavGroup[] = [...GROUPS_TOP, GROUP_SPEC, GROUP_MODELING];

/** 侧栏全量分组。保留这个名字，调用方（`buildNavGroups` / `activeNavPath`）不用改。 */
export const navGroups: NavGroup[] = MODEL_GROUPS;

/**
 * 把**组级**权限词下发到组内每一项上没有权限词的那几项上。
 *
 * <p>为什么需要：本进程的判权是「组级」的（`buildNavGroups` 按 `g.perm` 决定整组去留），
 * 所以权限词只写在组上。而组织平台的菜单项是**逐项**存 `perm` 的
 * （`nav_items.perm`），壳靠它决定「这一项留在原地置灰还是能点」。不下发的话，
 * 配到壳里的规范中心七项会是人人都能点进去、点进去再被后端顶回来。
 */
function inheritGroupPerm(groups: NavGroup[]): NavGroup[] {
  return groups.map((g) =>
    g.perm ? { ...g, items: g.items.map((it) => (it.perm ? it : { ...it, perm: g.perm })) } : g
  );
}

/**
 * 产品**内置认识**的层码：`layers.ts` 给它们配了图标与配色，层页面也按它们渲染。
 *
 * <p>为什么是这六个而不是 `seedStdLayers` 灌的那四个（ODS/DWD/DWS/ADS）：那四个只是
 * 「勾了初始化标准规范」的项目开局就有的层，`DIM`/`STG` 同样是一等公民（建维表、
 * 贴源缓冲都用得上），产品认得它们，就一并报给管理员挑。
 */
const BUILTIN_LAYER_CODES = ['ODS', 'DWD', 'DWS', 'ADS', 'DIM', 'STG'];

/**
 * 建模中心报给平台的候选：**每个内置分层入口 + 三个路径固定的工具页**。
 *
 * <p>只报内置层码（{@link BUILTIN_LAYER_CODES}），不报项目实际有哪些层 —— 那是运行期
 * 才知道的（建项目时可勾「初始化标准规范」，之后还能自由增删改），静态清单表达不了。
 * 配上去而项目没登记该层，点进去不是白屏：层页面会说明「该分层未在『分层规范』中登记，
 * 侧栏不会显示。可到规范中心补登记。」（见 `pages/model/dwd-overview.vue`），
 * 挂不挂由管理员按项目情况定。
 *
 * <p>「规范校验」「从 ODS 生成」「从 DWD 生成」在本进程侧栏里是**有条件**的
 * （后两项要项目同时有 ODS+DWD / DWD+DWS），这里一并报：它们是路径固定的工具页，
 * 对有那些层的项目就是该有的入口。
 */
const MODELING_CANDIDATES: NavGroup = {
  title: '建模中心',
  module: 'warehouse',
  perm: 'model:read',
  items: [
    ...BUILTIN_LAYER_CODES.map((l) => ({ path: layerHref(l), label: l, icon: layerIcon(l) })),
    ...buildModelingItems(BUILTIN_LAYER_CODES.map((layer) => ({ layer }))),
  ],
};

/**
 * 报给组织平台的菜单候选及**建议归属**。
 *
 * <p>只报本进程自己的、且在 multi（被组织平台嵌入时的形态）下真实存在的页面：
 *
 * <ul>
 *   <li><b>建模中心报「内置分层入口 + 三个工具页」</b>（ODS/DWD/DWS/ADS/DIM/STG 与
 *       规范校验 / 从 ODS 生成 / 从 DWD 生成，见 {@link MODELING_CANDIDATES}）——
 *       自建的层码报不了，理由写在那边的注释里。</li>
 *   <li><b>不报工作台级项。</b>本进程的工作台挂在 `/model/projects`，而 multi 下
 *       没有工作台 —— `router/index.ts` 会把它重定向回家。报给平台等于给出一个
 *       点进去就跳走的入口。</li>
 * </ul>
 *
 * <p>`scope` 只是建议值，平台管理员在菜单管理页可以改。
 */
export const MENU_CANDIDATES: MenuCandidateSource[] = [
  {
    scope: 'project',
    groups: inheritGroupPerm([...GROUPS_TOP, GROUP_SPEC, MODELING_CANDIDATES]),
  },
];

export interface MenuCandidateSource {
  scope: 'workbench' | 'project';
  groups: NavGroup[];
}
