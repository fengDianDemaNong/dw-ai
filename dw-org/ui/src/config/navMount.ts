import { ref } from 'vue';
import { EMBED_NAV_TREE, type EmbedNavMessage, type EmbedNavNode } from '@dw-ai/engine';
import type { NavNodeRow } from '../api/client';

/**
 * 收下被嵌产品**运行期**报上来的菜单树，并把它并进壳的侧栏树。
 *
 * <h2>为什么要有这个文件</h2>
 *
 * 「挂载节点」的子树原先只有**一个**来源：产品在构建期导出、部署在它前端的
 * `public/menu.json`，服务端拉回来折成节点（`NavNodeService`）。那张静态文件只能表达
 * 「对**所有**项目都成立」的项 —— 「建模中心」下的分层入口取决于**这个项目**在
 * 「分层规范」里登记了哪些层，是运行期逐项目的事实，文件里根本没有那几个字符串。
 * 于是产品自己的侧栏看得见、壳里看不见。
 *
 * <p>补这一段不需要新接口：产品在自己的壳布局里把**已经算好的**那份菜单
 * `postMessage` 过来（发端见 dw-model 的 `config/embed.ts` 的 `postNavTree`），
 * 本文件收下、按 `ref` 找对应挂载节点、**替换**它的子树。
 *
 * <h2>为什么文件名不叫 embed.ts</h2>
 *
 * `CrossServiceDesignGuardTest.findEmbedChildren()` 扫的是全仓
 * `<module>/ui/src/config/embed.ts`，把每一份都当成**子应用接收端**，逐条要求它实现
 * token 续期（`EMBED_TOKEN` + `applyAccessToken` + 401 兜底 …）。org 是**壳**，
 * 不接那一套；放进那个文件名会把守卫误伤成假的失败。
 *
 * <h2>为什么不放在 stores 里</h2>
 *
 * 消费方是 `config/sysNav.ts`（纯函数），而 `stores/app.ts` 会 import 一批 config ——
 * 放 stores 会引入一条 config → stores → config 的回边。这里只有一个 `ref`，
 * 谁都能 import，依赖方向保持单向。
 */

/**
 * 收到的运行期树，按 `(product, scope, project)` 各留一份。
 *
 * <p>留多份而不是只留最新：切项目时旧的那份**不该被新项目的覆盖掉再靠运气切回来** ——
 * 按 `project` 分开存，查表时精确命中当前项目那一份（见 {@link liveChildren}）。
 * 同一元组的重复上报是覆盖，不是追加 —— 产品在首屏与分层数据加载完之后各报一次，
 * 两次的内容是「不全」与「完整」的关系，追加会得到一堆重复项。
 */
const trees = ref<EmbedNavMessage[]>([]);

function keyOf(t: { product: string; scope: string; project: string }): string {
  return `${t.product}\u0000${t.scope}\u0000${t.project}`;
}

function originOf(url: string): string {
  try {
    return new URL(url).origin;
  } catch {
    return '';
  }
}

/**
 * 开始收产品报上来的菜单树。
 *
 * <p>`origins` 是**允许的来源**，由调用方（布局组件）现取：产品的站点地址在
 * `nav_nodes.frontendUrl` 上（服务注册里配的），只有壳自己知道怎么把它挖出来。
 * 传函数而不是数组，是为了让「服务注册里刚改了地址」立刻生效 —— 传数组就得在
 * 每次 `loadNav()` 之后重新登记一遍，漏了那一处，表现是**换地址后菜单不再更新**。
 *
 * <p>校验 `event.origin` 而不是只看消息内容：本进程的侧栏是这台机器上所有页面的
 * 导航，谁都能往这里发一条 `postMessage`。发端也是定向的（不用 `'*'`），
 * 两半合起来才成立。
 */
export function listenMountTrees(origins: () => string[]): void {
  window.addEventListener('message', (e: MessageEvent) => {
    const data = e.data as EmbedNavMessage | undefined;
    if (!data || data.type !== EMBED_NAV_TREE) return;

    const allowed = new Set(origins().map(originOf).filter(Boolean));
    if (!allowed.has(e.origin)) return;

    if (typeof data.product !== 'string' || !Array.isArray(data.nodes)) return;
    if (typeof data.scope !== 'string' || typeof data.project !== 'string') return;

    const incoming: EmbedNavMessage = { ...data };
    trees.value = [...trees.value.filter((t) => keyOf(t) !== keyOf(incoming)), incoming];
  });
}

/**
 * 从菜单树里收集产品站点地址 —— {@link listenMountTrees} 要的允许来源。
 *
 * <p>递归收集而不是只看挂载节点自己：`frontendUrl` 是服务注册里配在**产品**上的，
 * 服务端给哪些行带上它不是本文件能决定的，递归一遍最稳（多几个 origin 只是多几条
 * 允许项，而漏一个的表现是**那个产品的运行期菜单永远不生效**，静默且难查）。
 */
export function mountOrigins(nodes: NavNodeRow[]): string[] {
  const out: string[] = [];
  const walk = (list: NavNodeRow[]) => {
    for (const node of list) {
      if (node.mounted && node.frontendUrl) out.push(node.frontendUrl);
      walk(node.children ?? []);
    }
  };
  walk(nodes);
  return out;
}

/** 按 id 找（深度优先）。`id` 是产品算的候选 id，与挂载行的 `ref` 同源。 */
function findById(nodes: EmbedNavNode[], id: string): EmbedNavNode | undefined {
  for (const node of nodes) {
    if (node.id && node.id === id) return node;
    const hit = findById(node.children ?? [], id);
    if (hit) return hit;
  }
  return undefined;
}

/**
 * 按标题找（深度优先）。
 *
 * <p>兜底：产品的 id 规则改过版（分组那层从 `group:` 到 `dir:` 来回过），老库里存下的
 * `ref` 与新版本算出来的 id 会对不上 —— 只按 id 匹配的话，**一次产品升级会让已配好的
 * 菜单集体变成空目录**，而且不报错。
 *
 * <p>只取第一个同名的。同名节点的歧义无解（标题不是键），但比「什么都找不到」好：
 * 命中的多半就是管理员挂的那个顶层目录。
 */
function findByLabel(nodes: EmbedNavNode[], label: string): EmbedNavNode | undefined {
  for (const node of nodes) {
    if (node.label === label) return node;
    const hit = findByLabel(node.children ?? [], label);
    if (hit) return hit;
  }
  return undefined;
}

/** 运行期节点 → 壳的节点形状。子树一并转，`convert` 那边就不用认识两种类型。 */
function toRows(
  nodes: EmbedNavNode[],
  scope: NavNodeRow['scope'],
  product: string,
  frontendUrl: string
): NavNodeRow[] {
  return nodes.map((node, i) => {
    const kids = toRows(node.children ?? [], scope, product, frontendUrl);
    return {
      id: node.id,
      // scope 只用于 `toNavItems` 挑顶层那一层；递归时不看它（产品子节点自报的归属
      // 可能与壳不同，见 `toNavItems` 的说明），这里跟着壳走即可。
      scope,
      parentId: '',
      label: node.label,
      path: node.path,
      icon: node.icon,
      perm: node.perm ?? '',
      // 排序按产品给的顺序：它就是产品侧栏里的顺序，再排一次反而与产品自己看到的不一致。
      sortOrder: (i + 1) * 10,
      enabled: true,
      adminOnly: false,
      product,
      ref: '',
      mounted: false,
      emptyPolicy: 'hide',
      // 不可嵌产品要整页跳到它自己的站点（`convert` 里那条 `external` 分支），
      // 地址从挂载节点继承 —— 它才是服务注册里那一条。
      frontendUrl,
      // 产品侧自己判的「这一项点不了」（本组织没开通某项、或这个人无权访问）：原样搬过去，
      // 让壳渲染成置灰。**不能省** —— 省了的话产品侧栏里明明是灰的，壳里却是一条能点的
      // 入口（`convert` 对产品节点只看 `path`，看不出这一层区别）。
      ...(node.disabled ? { disabled: true, disabledReason: node.disabledReason ?? '' } : {}),
      ...(kids.length ? { children: kids } : {}),
    };
  });
}

/**
 * 一个挂载节点**当前该显示**的子节点；`undefined` = 没有运行期消息，调用方回落到
 * 服务端（静态清单）那一份。
 *
 * <p>三道匹配，缺一不可：
 * <ol>
 *   <li><b>产品 + 壳</b>：一个产品在两个壳里各有一份菜单（工作台那级与项目那级），
 *       拿工作台那份去填项目壳的节点会填出牛头不对马嘴的子树。</li>
 *   <li><b>项目</b>：`t.project` 为空 = 与项目无关（工作台壳）；否则必须等于当前项目。
 *       切项目时 iframe 不重建，消息可能在切换的空档里到达，不比对会把 A 项目的分层
 *       画到 B 项目上。</li>
 *   <li><b>节点</b>：先按 `ref` 精确匹配，再按标题兜底（见 {@link findByLabel}）。</li>
 * </ol>
 */
export function liveChildren(
  node: NavNodeRow,
  scope: NavNodeRow['scope'],
  projectCode: string
): NavNodeRow[] | undefined {
  if (!node.product || !node.ref) return undefined;

  const tree = trees.value.find(
    (t) =>
      t.product === node.product &&
      t.scope === scope &&
      (t.project === '' || t.project === projectCode)
  );
  if (!tree) return undefined;

  const hit = findById(tree.nodes, node.ref) ?? findByLabel(tree.nodes, node.label);
  if (!hit?.children?.length) return undefined;

  return toRows(hit.children, scope, node.product, node.frontendUrl ?? '');
}
