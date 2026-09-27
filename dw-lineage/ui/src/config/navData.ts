import type { NavGroup } from './nav';
import { LINEAGE_PAGES, PROJECT_SETTINGS_PAGES, WORKBENCH_PAGES } from './pages';

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
 * **纯常量、纯函数与本文件内的类型**。`./pages` 是允许的（它顶层只有路径常量与函数定义，
 * 不碰 `window`）。若这里引用了 `sessionStorage` / `authState` / `@dw-ai/engine`
 * （**包根**）之类的东西，`npm run build` 会在跑 vue-tsc 之前就挂在生成清单这一步 ——
 * 包根的 `index.ts` 会连带 `iam.ts`，那里面有 xlsx。
 *
 * <p>唯一的例外是**子路径** `@dw-ai/engine/embedNav`（见下面那段 re-export）：
 * 那个文件自己没有任何 import，打包进来是干净的。
 *
 * <p>路径一律引用 `./pages` 的常量而不是重写字面量：那是同一个路径的**唯一真源**，
 * 重写一份的话，`pages.ts` 改了这边不会跟着变，而症状是壳里点了菜单落空。
 *
 * <h2>两条已知局限，配菜单时要知道</h2>
 *
 * <ul>
 *   <li><b>菜单变更要重新构建前端才进候选。</b>这符合规格里「菜单是产品信息架构、
 *       跟版本走」——`menu.json` 随构建产物发布，不是运行期可改的配置。</li>
 *   <li><b>运行期动态生成的菜单项进不了静态清单。</b>数据地图是静态的，这条对**候选**
 *       仍然成立；仓建设（warehouse）的「建模中心」按 `layers` 每层生成一项，属动态项，
 *       候选只给静态骨架。
 *       <p><b>但运行期那一份已经能到壳里了</b>：子应用把现算的菜单树用 postMessage
 *       报给壳（见 `config/embed.ts` 的 `postNavTree` 与 `@dw-ai/engine/embedNav`），
 *       挂载节点下因此能看到真实的那份。这条局限剩下的部分只关乎候选清单。</li>
 * </ul>
 */

/**
 * 本服务的产品码。
 *
 * <p>与 `config/iam.ts` 的 `PRODUCT`、后端 `/api/runtime` 报出去的 `product` 是同一个值。
 * 刻意不 import 那边：`iam.ts` 顶层就 import 了 `@dw-ai/engine`（连同 xlsx），
 * 而本文件要保持在「Node 里 bundle 得动」的范围内。
 */
export const PRODUCT = 'metadata';

/**
 * 候选 id 的规则、以及「侧栏分组 → 报给壳的树」的转换 —— 本体在
 * `@dw-ai/engine/embedNav`，这里只做转出（见那边的完整说明）。
 *
 * <h2>为什么是本产品的 navData 转出，而不是各调用点自己去 engine 拿</h2>
 *
 * 调用点有两类，都要拿**本产品的** `PRODUCT` 一起用：构建脚本
 * （`scripts/gen-menu.mjs`，跑 esbuild 打包本文件后 `mod.candidateId(…)`）与
 * 运行期的上报（`config/embed.ts` 的 `postNavTree`，见 `layouts/AppLayout.vue`）。
 * 从这里转出，两边都只认本文件一个入口。
 *
 * <p>规则本身**必须与仓建设（warehouse）那份逐字相同**：id 是挂载的引用键
 * （dw-org 的 `nav_nodes.ref`），两处漂移的表现是**静默失配** —— 菜单看着在、
 * 子树不展开，没有任何报错。所以它不住在任何一个产品里。
 */
export {
  candidateId,
  groupCandidateId,
  toEmbedNodes,
} from '@dw-ai/engine/embedNav';

/**
 * 报给壳的一个菜单节点。别名指向契约类型本身。
 */
export type { EmbedNavNode as EmbedNode } from '@dw-ai/engine/embedNav';

/**
 * 本产品的权限词**全集**，供组织平台的两个下拉使用（菜单挂哪个权限、产品角色勾哪些权限）。
 *
 * <h2>为什么要显式列，不能由组织平台从菜单里聚合</h2>
 *
 * {@code lineage:write} **不在任何菜单上**：它只用在 SQL 解析页的「保存」按钮上
 * （见 `components/Header/index.vue`），页面本身的菜单挂的是 {@code lineage:read}。
 * 聚合菜单会漏掉它，于是「血缘分析」这个角色永远配不出写权限 ——
 * 而症状只是保存按钮一直 403，看不出是词表漏了。
 *
 * <h2>为什么要带人话标签</h2>
 *
 * 组织平台上配菜单的是平台管理员，不是本产品的开发者。给他看 {@code catalog:admin}
 * 他得猜；看「管理目录」不用猜。标签是**本产品自己的说法**，组织平台原样展示、不自己编一套。
 *
 * <p>标签为什么带域（「查看目录」而不是「查看」）：查看目录与查看血缘是两个不同的词，
 * 都叫「查看」的话下拉里会出现两条一模一样的选项。
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
  { value: 'catalog:read', label: '查看目录' },
  { value: 'lineage:read', label: '查看血缘' },
  { value: 'lineage:write', label: '编辑血缘' },
  { value: 'catalog:admin', label: '管理目录' },
];

export interface PermOption {
  value: string;
  label: string;
}

/**
 * 工作台级菜单：进项目**之前**的那一级。
 *
 * <p><b>这一组就是报给组织平台的 workbench 候选</b>（见 {@link MENU_CANDIDATES}）。
 *
 * <p>早先不报，理由是「multi 下数据地图没有工作台、这些路径会被路由守卫打回项目概况」，
 * 并由此推出「报出去等于在门户侧栏摆一批与平台自己那套重复的入口」。这两条现在都不成立：
 * 那条守卫已于 2026-09-26 删除（工作台回到本进程，见 `router/index.ts` 工作台路由的注释），
 * 而候选只是**可选项** —— 平台管理员在菜单管理里勾中才挂，不勾不会自己出现在侧栏上，
 * 所以「重复入口」不再是个问题，报全了反而让管理员有的选。
 *
 * <p>`nav.ts` 也用这一组 —— 三种模式自己的侧栏都是它。
 */
export const WORKBENCH_GROUPS: NavGroup[] = [
  {
    title: '',
    items: [
      { path: WORKBENCH_PAGES.home, label: '概况', icon: 'DashboardOutlined', ready: true },
      { path: WORKBENCH_PAGES.projects, label: '项目', icon: 'ProjectOutlined', ready: true },
    ],
  },
  {
    title: '设置',
    items: [
      { path: WORKBENCH_PAGES.metadata, label: '元数据服务', icon: 'ApiOutlined', ready: true },
      { path: WORKBENCH_PAGES.users, label: '账号管理', icon: 'UserOutlined', ready: true, adminOnly: true },
      { path: WORKBENCH_PAGES.preferences, label: '基本信息', icon: 'SettingOutlined', ready: true },
    ],
  },
];

/**
 * 项目级菜单：进了某个项目之后的那一级。
 *
 * <p>这组就是要报给组织平台的候选 —— 它和后端 `GET /api/manifest` 的 `menus`
 * 说的是同一批页面（路径与权限词都要对得上）。
 *
 * <p>「账号管理」那种 `adminOnly` 的项不在这里：那是本地账号体系的概念，
 * multi 下不存在，`nav_items` 里也没有能表达它的列。
 */
export const PROJECT_GROUPS: NavGroup[] = [
  {
    // 概况不配 perm：进得来这个前端就该看得见自己项目的总览。
    title: '',
    items: [{ path: LINEAGE_PAGES.home, label: '概况', icon: 'DashboardOutlined', ready: true }],
  },
  {
    title: '数据地图',
    items: [
      { path: LINEAGE_PAGES.search, label: '全文检索', icon: 'SearchOutlined', ready: true, perm: 'catalog:read' },
      { path: LINEAGE_PAGES.tables, label: '血缘', icon: 'PartitionOutlined', ready: true, perm: 'lineage:read' },
      // 「数据目录」与「临时表规则」是管理页，只有目录管理员进得去 —— 注意它们
      // 不蕴含 catalog:read（见 Perms.java 的注释），两件事。
      { path: LINEAGE_PAGES.catalogs, label: '数据目录', icon: 'FolderOutlined', ready: true, perm: 'catalog:admin' },
      { path: LINEAGE_PAGES.tempRules, label: '临时表规则', icon: 'FilterOutlined', ready: true, perm: 'catalog:admin' },
      // SQL 解析：能看要读血缘，页面里的「保存」另外要 lineage:write（那在页面内判）。
      { path: LINEAGE_PAGES.analyze, label: 'SQL 解析', icon: 'CodeOutlined', ready: true, perm: 'lineage:read' },
      { path: LINEAGE_PAGES.meta, label: '元数据', icon: 'TableOutlined', ready: true, perm: 'catalog:read' },
    ],
  },
  {
    title: '设置',
    items: [
      { path: PROJECT_SETTINGS_PAGES.map, label: '数据地图设置', icon: 'SettingOutlined', ready: true, perm: 'catalog:admin' },
    ],
  },
];

/**
 * 报给组织平台的菜单候选及**建议归属**。
 *
 * <p>两个壳都报：工作台那一层报 {@link WORKBENCH_GROUPS}（概况 / 项目 / 设置），
 * 项目那一层报 {@link PROJECT_GROUPS}。显式列出而不是让脚本去猜哪个数组要报 ——
 * 「哪些页面在哪个层级下有意义」是各服务自己的知识，写在这里比写在脚本里可靠。
 *
 * <p>`scope` 只是建议值，平台管理员在菜单管理页可以改。
 */
export interface MenuCandidateSource {
  scope: 'workbench' | 'project';
  groups: NavGroup[];
}

export const MENU_CANDIDATES: MenuCandidateSource[] = [
  // 工作台这一层报的就是本产品自己的侧栏。三项会进候选：概况、项目，以及
  // 「设置」目录下的元数据服务与基本信息 —— 「账号管理」是 `adminOnly`，
  // `gen-menu.mjs` 会跳过它（它只在本地账号体系下存在；multi 下 `authState.me`
  // 为空，路由守卫的 `to.meta.adminOnly` 也会把它打回工作台概况）。
  //
  // 注意「元数据服务」报的是**工作台路径**（`/lineage/workbench/settings/metadata`），
  // 不是 `PROJECT_SETTINGS_PAGES.metadata`（`/lineage/settings/metadata`）。
  // 两者是同一个组件（`pages/settings/metadata-sources.vue`）的两条路径、两种用途：
  // 后者是 dw-model 把「元数据服务」页 postMessage 推过来时的**项目级嵌入契约**
  // （见 `pages.ts` 的 `PROJECT_SETTINGS_PAGES` 注释），不是给人点的菜单入口 ——
  // 所以它不进 `PROJECT_GROUPS`，也不该冒充工作台候选。
  //
  // 这一项早先是单独手工列在这里的（且报的是那条项目级路径）：当时的理由是
  // multi 没有工作台，唯一能渲染的就是项目级那条。工作台回到本进程后不再需要，
  // 整组按产品自己的结构报即可。
  { scope: 'workbench', groups: WORKBENCH_GROUPS },
  { scope: 'project', groups: PROJECT_GROUPS },
];
