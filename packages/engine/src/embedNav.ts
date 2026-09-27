/**
 * 嵌壳协议：**子应用把自己的运行期菜单树报给宿主**。
 *
 * <h2>为什么需要这条消息</h2>
 *
 * 组织平台的侧栏是一棵配置在库里的树（`dw-org` 的 `nav_nodes`），其中「挂载节点」
 * （`mounted = true`）的子树来自产品的**静态清单** —— 那张清单是构建期由
 * `gen-menu.mjs` 导出、部署在前端 `public/menu.json` 的**文件**，壳去
 * `{frontendUrl}/menu.json` 取。
 *
 * <p>于是它只能表达「对**所有**项目都成立」的菜单项。「建模中心」下的
 * DIM / ODS / DWD / DWS 取决于**当前项目**在「分层规范」里登记了哪些层，是
 * **运行期逐项目**的事实 —— 静态文件里根本没有这几个字符串。产品自己的侧栏
 * 看得见它们（`ProjectLayout.vue` 用 `buildNavGroups(projectLayerRules, …)` 现算），
 * 壳里却看不见。
 *
 * <p>这条消息补的就是这一段：子应用把自己**已经算好的那份**（不是重算一遍）
 * 定向发给宿主，宿主并进对应挂载节点的子树。**两边都不新增 HTTP 接口** ——
 * `window.postMessage` 是浏览器自带的跨 iframe 通道，不经过任何服务器。
 *
 * <h2>为什么不各写一份</h2>
 *
 * 消息是**运行时**传的，字段对不上不会报错，只会静默不生效（侧栏少几项，
 * 看起来像配置没配）。所以契约放在这里由两端共同 import：改单边会**编译期**报错。
 *
 * <h2>安全</h2>
 *
 * 消息内容不敏感（就是菜单标题与路径），但**两端都不许用 `'*'` 广播** ——
 * 定向发送是这个仓里嵌壳协议的既有纪律（见 `config/embed.ts` 的 `hostTarget`），
 * 接收端必须校验 `event.origin`。
 */

/** 子应用 → 宿主：这是我的运行期菜单树。 */
export const EMBED_NAV_TREE = 'dw-embed-nav-tree';

/**
 * 一个菜单节点。
 *
 * <p>字段刻意与 dw-org 的 `NavNodeRow` 的**可渲染子集**对齐（label / path / icon /
 * perm / children），宿主收到后不用做形状转换，补几个渲染不看的默认值即可。
 */
export interface EmbedNavNode {
  /**
   * 产品的候选 id —— 与 `gen-menu.mjs` 的 `candidateId` 同源，也就是壳里那一行
   * `nav_nodes.ref` 记的东西。宿主靠它把子树挂到正确的挂载节点上。
   *
   * <p>空串也是合法的（宿主会退到按标题匹配，见 dw-org 的 `matchMount`）：
   * 产品的 id 规则改过版（`group:` → `dir:`），老库里的 `ref` 与新产品算出来的
   * id 对不上，只按 id 匹配会让**已配置好的菜单在一次升级后集体变空目录**。
   */
  id: string;
  label: string;
  /** 空串 = 目录节点（不可点，只用来挂子节点）。 */
  path: string;
  icon: string;
  /** 进这一页需要的权限词；空串 = 不判权。 */
  perm?: string;
  /**
   * 产品侧自己判的「这一项点不了」（例：本组织没开通 AI 设计规范）。
   *
   * <p>与「不进树」是**两回事**：`ready === false`（页面还没做）与 `adminOnly`
   * （本地账号体系的概念）在 {@link toEmbedNodes} 里就被丢掉了 —— 它们在壳里
   * 没有对应物；而这一种是**页面在、只是这个组织用不了**，要原样报上去。
   * 不报的话，产品侧栏里那条置灰的项在壳里会整个消失，同一个组织在两个界面上
   * 看到的菜单不一样。
   */
  disabled?: boolean;
  /** 置灰的原因，给 tooltip 用（与 {@link disabled} 一起报）。 */
  disabledReason?: string;
  children?: EmbedNavNode[];
}

/** 子应用报给宿主的一整棵树。 */
export interface EmbedNavMessage {
  type: typeof EMBED_NAV_TREE;
  /** 产品码（`warehouse` / `metadata` / …），与 `service_registry.product` 同一套。 */
  product: string;
  /** 壳：`workbench` | `project`。 */
  scope: string;
  /**
   * 这份菜单属于哪个项目（code）。空串 = 与项目无关（工作台壳）。
   *
   * <p>宿主拿它跟当前项目比对：**不一致就丢弃**。子应用切项目时 iframe 不重建，
   * 消息可能在切换的空档里到达，不比对会把 A 项目的分层画到 B 项目上。
   */
  project: string;
  /** 顶层节点（产品的一组一组就是一层目录节点）。 */
  nodes: EmbedNavNode[];
}

/**
 * 一个候选节点的稳定 id。
 *
 * <p><b>这个 id 是挂载的引用键</b>：组织平台的菜单行拿 `nav_nodes.ref` 记住它
 * （见 dw-org 的 `NavNodeService.requireMountable`）。改了规则，已经挂载的行就找不到
 * 对应的节点，那一支在侧栏里降级成空目录 —— **不报错**，只在有人去看侧栏时才发现坏了。
 * 所以规则钉死在这里，两个产品的 `gen-menu.mjs` 与运行期上报都调这一份：
 *
 * <ul>
 *   <li><b>可点的页面</b>（有 `path`）：`{产品}:{壳}{路径}` —— 与 V22 及更早**逐字相同**，
 *       产品升级到「能报树」这一版时，已挂载的页面不会失配。</li>
 *   <li><b>组目录</b>：见 {@link groupCandidateId}。</li>
 *   <li><b>产品自己加的中间节点</b>：`{产品}:{壳}:dir:{祖先label/…/自己label}` ——
 *       这类节点没有 path 可用，而 label 是产品自己在 `navData.ts` 里写的、比顺序号稳定。
 *       **改一个祖先的 label 会让整支的 id 变**，这是已知代价（重新挂一次即可）。</li>
 * </ul>
 *
 * <p>`parents` 是**祖先 label 链**（不含自己），只有中间节点用得上。
 */
export function candidateId(
  product: string,
  scope: string,
  parents: string[],
  item: { path?: string; label: string }
): string {
  if (item.path) return `${product}:${scope}:${item.path}`;
  return `${product}:${scope}:dir:${[...parents, item.label].join('/')}`;
}

/**
 * 组目录的稳定 id：`{产品}:{壳}:group:{组名}`。
 *
 * <p>与 dw-org 把**老格式**清单折成树时拼出来的 id 逐字相同
 * （`MenuCandidateService.foldGroups`）—— 于是「org 先上、产品还没发版」这段时间里
 * 挂上的组目录，在产品发版后**仍是同一个 id**，不会集体失配。
 *
 * <p>组名是产品写死的（不是 `candidateId` 的 `dir:` 那条规则），因为组是**产品的信息架构**，
 * 而 `dir:` 是产品自加的中间层 —— 两者在壳里长得一样，但来历不同。
 */
export function groupCandidateId(product: string, scope: string, groupTitle: string): string {
  return `${product}:${scope}:group:${groupTitle}`;
}

/**
 * 产品侧菜单项的**结构化**视图。
 *
 * <p>为什么不直接引两个产品的 `NavItem`：那两个类型各在各的 `config/nav.ts` 里，
 * 字段并不相同（dw-lineage 的带 `ready` / `adminOnly`，dw-model 的带 `disabled`），
 * 而且本文件要能被**产品的 `navData.ts`** import —— 那条链是在 Node 里跑 esbuild
 * 打包的，引到产品自己的类型文件会把整棵 import 树（含 `stores/*`）拖进去。
 * 结构类型只描述「转换要看的那几个字段」，两个产品的 `NavItem` 都天然满足它。
 */
export interface NavLike {
  path?: string;
  label: string;
  icon?: string;
  perm?: string;
  /** `false` = 页面还没做。整项不进树（与 `gen-menu.mjs` 的候选口径一致）。 */
  ready?: boolean;
  /** 本地账号体系的概念，壳里没有能表达它的列。整项不进树。 */
  adminOnly?: boolean;
  /** 产品侧判的「点不了」（见 {@link EmbedNavNode.disabled}）—— 这一种要报上去。 */
  disabled?: boolean;
  /**
   * 置灰的原因。**两个产品用它表达的意思不同**，所以下面两条都要认：
   * dw-model 是 `disabled: true` 配上它；dw-lineage 没有 `disabled` 这一列，
   * 判据就是「这个字段非空 = 置灰」（见各自 `config/nav.ts` 的 `NavItem`）。
   */
  disabledReason?: string;
  children?: NavLike[];
}

/** 产品侧分组的结构化视图（`title` 为空串 = 不分组，它的项直接进上一层）。 */
export interface NavGroupLike {
  title: string;
  /** 组级权限词 —— 挂到组目录节点上，壳据此整组判权。 */
  perm?: string;
  items: NavLike[];
}

/**
 * 产品**当前算好的**侧栏分组 → 报给宿主的树。
 *
 * <p>输入的 `groups` 必须是产品侧栏用的那一份（不是另算一遍）—— 见本文件头部的说明：
 * 这条消息的全部意义就是「把已经渲染出来的那份搬过去」，重算一遍等于把规则复制成两份。
 *
 * <p>过滤口径与 `gen-menu.mjs` 的 `toCandidate` **一致**（丢掉 `ready === false`
 * 与 `adminOnly`）：挂载节点下的项，要与管理员在「菜单管理」里看到的候选对得上 ——
 * 多了，他看不见也删不掉；少了，他勾了却不显示。
 */
export function toEmbedNodes(
  product: string,
  scope: string,
  groups: NavGroupLike[]
): EmbedNavNode[] {
  const nodes: EmbedNavNode[] = [];
  for (const group of groups) {
    const items = group.items
      .map((item) => toEmbedNode(product, scope, [], item))
      .filter((node): node is EmbedNavNode => node !== null);
    if (!items.length) continue;
    // 标题为空 = 不分组：它的项直接成为顶层，不套一层目录（与 `gen-menu.mjs` 同理）。
    if (!group.title) {
      nodes.push(...items);
      continue;
    }
    nodes.push({
      id: groupCandidateId(product, scope, group.title),
      label: group.title,
      path: '',
      icon: '',
      perm: group.perm ?? '',
      children: items,
    });
  }
  return nodes;
}

/** 一项（连同子树）→ 一个节点；`null` = 这一支整个不报（见 {@link toEmbedNodes}）。 */
function toEmbedNode(
  product: string,
  scope: string,
  parents: string[],
  item: NavLike
): EmbedNavNode | null {
  if (item.ready === false || item.adminOnly) return null;
  const kids = (item.children ?? [])
    .map((child) => toEmbedNode(product, scope, [...parents, item.label], child))
    .filter((node): node is EmbedNavNode => node !== null);
  // 目录节点自己也可能是「不该出现」的：子节点全被过滤掉了，留一个空目录没有意义
  // （壳里会按 empty_policy 处理它，但那时它代表的是「产品这一支没了」，不是「本来就没有」）。
  if (!kids.length && !item.path) return null;
  // 两种表达都要认（见 {@link NavLike.disabledReason}）：dw-model 明写 `disabled: true`，
  // dw-lineage 靠 `disabledReason` 非空。只认前者的话，lineage 那边的置灰项
  // ——「当前角色无权访问」—— 报到壳里会变成一条**能点**的入口。
  const off = item.disabled === true || Boolean(item.disabledReason);
  return {
    id: candidateId(product, scope, parents, item),
    label: item.label,
    path: item.path ?? '',
    icon: item.icon ?? '',
    perm: item.perm ?? '',
    ...(off ? { disabled: true, disabledReason: item.disabledReason || '当前不可用' } : {}),
    ...(kids.length ? { children: kids } : {}),
  };
}
