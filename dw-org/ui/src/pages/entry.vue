<template>
  <div class="page entry">
    <PageHeader :title="page?.label || '入口页'" :subtitle="subtitle">
      <template #actions>
        <a-button @click="reload">刷新</a-button>
      </template>
    </PageHeader>

    <p v-if="loading" class="muted">正在加载…</p>

    <div v-else-if="error" class="card">
      <h3>这一页打不开</h3>
      <p class="muted">{{ error }}</p>
    </div>

    <!--
      兜底：这一页自己就是被框起来的。

      <p>正常走不到 —— 服务端不允许把一个入口页挂进另一个入口页（`NavNodeService.apply`
      里那条「target 不能是 entry_page」的校验），所以「入口页套入口页」只可能来自手工
      用地址嵌这一页。真发生了也**不要再套一层 Tab**：每层都再嵌一次就是无限 iframe，
      外层看不出来，只是越来越卡。已经在一个框里 = 老老实实列个名字。
    -->
    <div v-else-if="embed" class="card">
      <h3>{{ page?.label }}</h3>
      <p class="muted">这一页有 {{ rows.length }} 个 Tab：</p>
      <ul class="plain">
        <li v-for="row in rows" :key="row.key">{{ row.label }}</li>
      </ul>
    </div>

    <div v-else-if="!rows.length" class="card">
      <p class="muted" style="margin: 0">
        这一页还没有挂任何菜单。到
        <router-link :to="ORG_PAGES.platformNav">菜单管理</router-link>
        里给它挑几项。
      </p>
    </div>

    <!-- 一个被挂的菜单 = 一个 Tab，Tab 里是它**自己的页面**（内嵌，不跳走）。

         **挂目录 = 挂它下面能打开的每一项**：目录自己没有页面，只给它一个 Tab 的话，
         点开是一张「这是一个目录」的卡片，子菜单一条都看不到（用户 2026-09-27 报的
         就是这个）。摊平在 `rows` 里做、不在服务端做 —— 产品「建模中心」下面有哪些
         分层入口是按项目登记的**运行期**事实，服务端只有静态清单。

         `key` 用节点 id 而不是落点：目录 / 置灰 / 未配地址这三档的 `path` 都是空串，
         拿它当 key 会出现重复 —— antd 的 activeKey 是字符串比对，重复 key 会让
         「点 A 高亮 B / 渲染错 pane」，且不报错。 -->
    <a-tabs v-else v-model:activeKey="active" class="tabs">
      <a-tab-pane v-for="row in rows" :key="row.key" :tab="row.label">
        <div class="pane">
          <div class="pane-bar">
            <a-tag>{{ kindOf(row) }}</a-tag>
            <span class="spacer" />
            <!-- 刷新 = 换 iframe 的 key 重载。切走再切回来**不会**重载（antd 默认把
                 访问过的 pane 留在 DOM 里）—— 正是想要的：滚动位置、筛选条件都还在。 -->
            <a-button v-if="row.frameUrl" size="small" @click="bump(row.key)">重新加载</a-button>
            <!--
              常驻，不是「出错时才出现」：目标站拒嵌（`X-Frame-Options` / CSP
              `frame-ancestors`）时浏览器**不把失败暴露给 JS**，探测不到。
              既然探测不到，出路就得一开始摆在那里（与 `pages/external.vue` 同一个理由）。

              只在**有 iframe** 时给：不能嵌的项由下面卡片里那个（更醒目的）按钮负责 ——
              同一个动作在屏幕上出现两次，用户 2026-09-27 报过一次（见 V24 删掉侧栏那条）。
            -->
            <a v-if="row.openUrl && row.frameUrl" :href="row.openUrl" target="_blank" rel="noreferrer">
              <a-button size="small">在新标签页打开</a-button>
            </a>
          </div>

          <!-- 产品行走 `ProductEmbed`，**不是**裸 iframe：产品在嵌进来的那一刻要拿到
               登录态（`#boot=` 那条通道），之后 token 续期靠它原地换掉子应用手里的
               令牌。裸 iframe 少了后一半 —— 表现是 15 分钟后框里整片 401。

               它也是**运行期菜单树能到这一页**的前提：产品把树 `postMessage` 给
               `window.parent`，直接嵌时 parent 就是这一页所在的窗口（收端注册在
               `SystemLayout` 上）。走壳内那条 `/org/embed/{product}/...` 的话 parent
               是中间那层 embed 窗口，外层永远收不到 —— 「建模中心」摊平出来会是空的。 -->
          <ProductEmbed
            v-if="row.frameUrl && row.node?.product"
            :key="`${row.key}:${nonces[row.key] || 0}`"
            :product="row.node.product"
            :label="row.label"
            :src="row.frameUrl"
          />
          <!-- **不加 sandbox**：这一层是同源的 org 自己那一页，而 sandbox 不给
               `allow-same-origin` 会把它变成 opaque origin —— sessionStorage 随之隔离，
               框里的 org 读不到 `dw-ai.token`，整页 401（而且看不出是 sandbox 造成的）。
               给了 `allow-same-origin` 则沙箱对同源内容形同虚设（子帧能改自己的 sandbox），
               所以对同源内容加沙箱没有意义。外链那种**跨域且不可信**的目标才需要沙箱
               （见 `pages/external.vue`），两种场景别统一处理。 -->
          <iframe
            v-else-if="row.frameUrl"
            :key="`${row.key}:${nonces[row.key] || 0}`"
            class="frame"
            :src="row.frameUrl"
            :title="row.label"
          />
          <div v-else class="card fallback">
            <h3>这一项不能嵌进来看</h3>
            <p class="muted">{{ row.problem || '它没有可以打开的页面。' }}</p>
            <a v-if="row.openUrl" :href="row.openUrl" target="_blank" rel="noreferrer">
              <a-button type="primary">在新标签页打开</a-button>
            </a>
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from '../components/PageHeader.vue';
import ProductEmbed from '../components/ProductEmbed.vue';
import { ORG_PAGES } from '../config/pages';
import { toNavItems, type ShellCtx } from '../config/sysNav';
import { liveChildren } from '../config/navMount';
import { shellProductUrl } from '../config/shellBoot';
import { isEmbed } from '../config/runtime';
import { api } from '../api/client';
import type { NavEntryPage, NavNodeRow } from '../api/client';
import type { NavItem } from '../config/nav';

defineOptions({ name: 'NavEntryPage' });

const route = useRoute();

/** 当前在哪个壳里 —— 地址的第一段就是（工作台 `/org/workbench/...`、项目 `/org/project/{code}/...`）。 */
const shell = computed(() =>
  route.matched.some((r) => r.meta.shell === 'project') ? 'project' : 'workbench'
);
const projectCode = computed(() => String(route.params.code ?? ''));
const id = computed(() => String(route.params.id ?? ''));

/** 这一页自己被框了起来（见 `config/runtime.ts`）—— 不再往下套 Tab。 */
const embed = isEmbed();

const page = ref<NavEntryPage | null>(null);
const loading = ref(true);
const error = ref('');

/** 每个 Tab 的重载计数：`重新加载` 按钮把它 +1，iframe 的 key 跟着变。 */
const nonces = ref<Record<string, number>>({});
function bump(key: string) {
  nonces.value[key] = (nonces.value[key] || 0) + 1;
}

/** Tab 数而不是「挂了几条」：挂一条目录可能摊出七八个 Tab，报条数会看着对不上。 */
const subtitle = computed(() => (rows.value.length ? `这一页有 ${rows.value.length} 个 Tab。` : ''));

/** 壳上下文。`toNavItems` 与运行期菜单树（`liveChildren`）都要它，两处必须同一份。 */
const ctx = computed<ShellCtx>(() => ({
  scope: shell.value,
  projectCode: shell.value === 'project' ? projectCode.value : '',
}));

/**
 * 服务端节点的索引（递归），摊平之后的每一行靠它回答「你是什么」「你是哪个产品的」。
 *
 * <p>这些只有服务端那一行知道 —— `NavItem` 上刻意没有产品码与产品地址（它是侧栏的形状，
 * 见 `config/nav.ts`），而产品行的 iframe 地址**必须**用它们才拼得对（见 `frameUrlOf`）。
 *
 * <p>挂载节点的<b>运行期</b>子树要单独补进来：它不在 `page.items` 里（产品在浏览器里
 * `postMessage` 报上来的，服务端不知道，见 `config/navMount.ts`），而摊平之后
 * 「建模中心 › 分层规范」这类叶子恰恰是它们 —— 少了这一趟，那些 Tab 会退化成
 * 「没登记前端地址」的卡片。
 */
const nodeById = computed(() => {
  const index = new Map<string, NavNodeRow>();
  const walk = (list: NavNodeRow[]) => {
    for (const n of list) {
      index.set(n.id, n);
      walk(n.children ?? []);
      // `liveChildren` 只认带 `ref` 的挂载行（运行期子项自己没有），递归不会打转
      const live =
        n.mounted && n.ref ? liveChildren(n, ctx.value.scope, ctx.value.projectCode) : undefined;
      if (live) walk(live);
    }
  };
  walk(page.value?.items ?? []);
  return index;
});

/**
 * 把一层菜单摊平成「一行一个 Tab」的列表：**目录展开成它下面能打开的每一行**。
 *
 * <p>为什么在前端摊、不在服务端：产品「建模中心」下面有哪些分层入口，是按<b>项目</b>
 * 登记的<b>运行期</b>事实，服务端只有那张静态清单（`config/navMount.ts` 里说得很直白）。
 * 而这里拿到的树已经过 `toNavItems → convert`，`liveChildren` 已经介入 —— 它就是
 * <b>产品自己侧栏里那一棵</b>，产品看得见的，这里就摊得出来。
 *
 * <p>两条边界：
 * <ul>
 *   <li><b>置灰 / 占位行当叶子保留</b>。它们渲染出来同样是「空 `path` + `children`」，
 *       盲目往下摊会得到空集 —— 那个 Tab 整个消失，与侧栏里「`always` 空目录显示一张
 *       置灰卡」正好相反。</li>
 *   <li><b>摊平为空时回退成目录自己那一行</b>（子树整个判权不过、或全是空目录），
 *       别出现「挂了一条却什么都没有」。</li>
 * </ul>
 */
function flatten(items: NavItem[]): NavItem[] {
  const out: NavItem[] = [];
  for (const item of items) {
    const kids = item.children ?? [];
    if (!item.path && !item.disabled && kids.length) {
      const leaves = flatten(kids);
      if (leaves.length) {
        out.push(...leaves);
        continue;
      }
    }
    out.push(item);
  }
  return out;
}

/**
 * 一个 Tab = 服务端渲染过的菜单项 + 前端拼出来的落点。
 *
 * <p><b>落点规则不在这里写第二遍</b>：把每一项丢给 `toNavItems`（侧栏用的同一个函数），
 * 它按壳拼好 org 路由、处理 `{code}` / `{node}` 占位符、认出外链的 `href`、把判权不过的
 * 标成 disabled。这条分流正好把「能不能内嵌」也一并决定了（见 `frameUrlOf`）——
 * 另写一套的话，「侧栏点得动、这里嵌不出来」（或反过来）迟早会出现，而两边各自看着都对。
 *
 * <p><b>按 id 配对，不能按下标</b>：`toNavItems` 会先按 `ctx.scope` 过滤一层，被滤掉的项
 * 不在结果里 —— 用 `converted[i]` 会让后面每一项都错位一格，表现为「整批都变成卡片」。
 * 而服务端**不校验**路由壳与入口页的壳是否一致（用 `/org/project/{code}/entry/{工作台入口页id}`
 * 访问完全合法，两条路由注册的是同一个组件），所以这个错位是真能发生的。
 */
const rows = computed<Row[]>(() => {
  const items = page.value?.items ?? [];
  const converted = toNavItems(items, ctx.value);
  const byId = new Map(converted.map((n) => [n.id, n]));
  const index = nodeById.value;

  const out: Row[] = [];
  // 按行 id 去重：既挂了目录、又单独挂了它下面某一项时，那两个 id 会摊出同一行
  const seen = new Set<string>();
  for (const top of items) {
    const nav = byId.get(top.id);
    if (!nav) {
      // 不在这个壳的菜单里（服务端不校验路由壳与入口页的壳是否一致，见上面这段注释）。
      // 摊不了，但仍占一个 Tab 把成因说出来 —— 静默少一行比一张卡片更难查。
      out.push(buildRow(`miss:${top.id}`, undefined, top));
      continue;
    }
    for (const leaf of flatten([nav])) {
      const key = leaf.id || leaf.path;
      if (!key || seen.has(key)) continue;
      seen.add(key);
      out.push(buildRow(key, leaf, index.get(leaf.id ?? '')));
    }
  }
  return out;
});

/** 一个 Tab 的形状：侧栏那套拼好的落点 + 服务端那一行 + 算好的三条结论。 */
type Row = {
  key: string;
  /** 侧栏那套的落点；空 = 这一项不在这个壳的菜单里。 */
  nav?: NavItem;
  /** 服务端那一行（类型、产品码、产品地址都在它上面）；运行期子项没有。 */
  node?: NavNodeRow;
  /** Tab 标题。 */
  label: string;
  /** 能嵌进 Tab 的地址；空 = 这一项走卡片。 */
  frameUrl: string;
  /** 卡片上「在新标签页打开」的地址；空 = 连出路都没有（目录 / 置灰项）。 */
  openUrl: string;
  /** 嵌不进来时的说明文案。 */
  problem: string;
};

/** 组装一行：三条结论都在这里算完，模板只做渲染。 */
function buildRow(key: string, nav: NavItem | undefined, node: NavNodeRow | undefined): Row {
  const row: Row = {
    key,
    nav,
    node,
    label: nav?.label || node?.label || '未命名',
    frameUrl: '',
    openUrl: '',
    problem: '',
  };
  row.frameUrl = frameUrlOf(row);
  row.openUrl = openUrlOf(row);
  row.problem = problemOf(row);
  return row;
}

/** 当前激活的 Tab。数据是异步来的，所以由下面的 watch 兜（列表变了就回到第一项）。 */
const active = ref('');
watch(
  rows,
  (list) => {
    if (!list.some((r) => r.key === active.value)) active.value = list[0]?.key ?? '';
  },
  { immediate: true }
);

/**
 * 能不能把这一项嵌进 Tab：**只有**「壳内的一次导航」可以。
 *
 * @returns 可以当 iframe src 的地址；空串 = 这一项走卡片分支。
 */
function frameUrlOf(row: Row): string {
  const nav = row.nav;
  if (!nav || nav.disabled || nav.external) return '';
  const p = nav.path;
  // 以 `/` 开头是硬要求：空串会被浏览器解析成**当前文档地址**（不是 `about:blank`），
  // 于是这一页 iframe 自己、子层再 iframe 自己 —— 表面看不出异常，只是标签越来越卡。
  if (typeof p !== 'string' || !p.startsWith('/')) return '';
  // 模板占位符没被替换干净 → 那种地址会命中 `router/index.ts` 的 wildcard 兜底路由，
  // 标签里静默显示成首页，看起来像「这个菜单就是首页」。
  if (p.includes('{')) return '';

  // 产品行：`p` 是 `convert` 拼的**壳内**地址（`/org/embed/{product}/...`），不能拿它当
  // Tab 的落点 —— 那是壳自己的 embed 页，进去还要再套一层壳（每个 Tab 多一次 SPA 启动 +
  // 一次 `GET /api/nav`），而且产品推的运行期菜单树发给了**中间那层**窗口，
  // 这一页的 `liveChildren` 永远收不到（摊平会退回静态清单）。
  // 所以直接用产品自己的地址嵌：产品只嵌一层，parent 就是这一页。
  const n = row.node;
  if (n?.product) {
    if (!n.frontendUrl || !n.path) return '';
    return shellProductUrl(n.product, n.path, n.frontendUrl, ctx.value.scope);
  }
  return p;
}

/**
 * 卡片与工具条上「在新标签页打开」的地址。目录与置灰项两者都是空 ——
 * 不给按钮，而不是给个死按钮。
 *
 * <p>产品行直接用 `frameUrl`：它就是那个产品的**完整地址**（带 `#boot=`），
 * 比壳内那条 `/org/embed/...`（开出来还是壳）更该在新标签页里打开。
 */
function openUrlOf(row: Row): string {
  const nav = row.nav;
  if (!nav || nav.disabled) return '';
  if (row.node?.product && row.frameUrl) return row.frameUrl;
  if (nav.href) return nav.href;
  return nav.path?.startsWith('/') ? nav.path : '';
}

/** 为什么这一项没能嵌进来。判权那类**用服务端给的文案**，前端不另写一套说法。 */
function problemOf(row: Row): string {
  // 能嵌的项没有「问题」—— 下面那串兜底分支是对「路径不合法」写的，
  // 不先挡这一句的话，一个能嵌的项也会被它报成「嵌不进来」。
  if (row.frameUrl) return '';
  const nav = row.nav;
  const n = row.node;
  if (!nav) return '这一项不在这个壳的菜单里，可以到菜单管理里把它挂到对的地方。';
  // 置灰的理由用服务端给的（`toRows` 把产品自己判的那一份也搬过来了，见 `navMount.ts`）
  if (nav.disabled) return nav.disabledReason || '这一项对当前角色不可见。';
  if (nav.external) return '这一项要去别的站点，不能嵌在这里。';
  if (n?.product) {
    if (!n.frontendUrl) return '这一项的产品还没有登记前端地址（见「服务注册」）。';
    return '这是一个目录，它自己没有页面。它下面的菜单都没能打开。';
  }
  if (!nav.path) return '这是一个目录，它自己没有页面。';
  if (nav.path.includes('{')) return '这一项的地址还没生成好（占位符没被替换）。';
  return '这一项的地址不是一个壳内地址，嵌不进来。';
}

/**
 * 类型列：与「五类节点」同一套判据（见 `NavNodeRow` 的注释），不另立一套说法。
 *
 * <p>服务端那一行**理论上一定有** —— `nodeById` 摊的就是 `convert` 用过的同一批来源
 * （含挂载节点的运行期子树）。真缺了只可能是产品运行期报上来的项，那就是产品页面。
 */
function kindOf(row: Row): string {
  const n = row.node;
  if (!n) return '产品页面';
  if (n.entryPage) return '入口页';
  if (n.externalUrl) return n.openMode === 'embed' ? '外链（内嵌）' : '外链（外跳）';
  if (n.product) return n.mounted ? '产品（挂载）' : '产品页面';
  if (!n.path) return '目录';
  return '本站页面';
}

/**
 * 刷新按钮：判权结论随角色与项目变，而这页是常开的（挂在侧栏上）——
 * 换了角色再回来，不刷新就会看到旧的置灰状态。
 */
async function reload() {
  loading.value = true;
  error.value = '';
  try {
    page.value = await api.navEntry(id.value);
  } catch (e) {
    page.value = null;
    // 404 是最常见的一种（管理员刚把它删了，或这一页只给租户管理员看）—— 原样说清，
    // 不吞成「加载失败」：那会让人以为是自己网络的问题。
    error.value = (e as { message?: string })?.message || '这一页不存在，或者你没有权限看它。';
  } finally {
    loading.value = false;
  }
}

onMounted(reload);
// 同一个组件在两个入口页之间跳（侧栏里两条入口页互相点）时不会重新挂载，
// 而 `id` 变了 —— 不监听就会一直显示上一条的内容。
watch(id, reload);
</script>

<style scoped>
/*
 * 高度链的**第一环**在这里。
 *
 * <p>`.page`（全局）是 `height: 100%; overflow: auto` 的**块盒** —— 块盒里 `flex: 1`
 * 静默失效，高度一路退化成 auto，最后 iframe 拿到浏览器默认的 **150px**（只剩顶上一条）。
 * `SystemLayout.vue` 的 `.main` 注释记过这个坑，`workbench.spec.ts` 有一条断言专门守它。
 * 所以这一页自己接管：display:flex 让 flex 传递能往下走，overflow 收掉全局的滚动条
 *（滚动交给 Tab 里的页面，否则两条滚动条）。
 */
.entry {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* 第二环：Tab 组件吃掉剩下的高度。 */
.tabs {
  flex: 1;
  min-height: 0;
}

/*
 * 第三环：antd 自己那一层。
 *
 * <p>`.ant-tabs-content-holder` 只声明了 `flex: auto`，**它自己不是 flex 容器** ——
 * 于是里面的 `.ant-tabs-content` 高度是 auto，再里面的 `height: 100%` 静默回落到 150px。
 * 三行都得补，缺哪一行都还是 150px，而且页面不报错、Tab 也切得动。
 */
:deep(.ant-tabs-content-holder) {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
:deep(.ant-tabs-content) {
  flex: 1;
  min-height: 0;
}
:deep(.ant-tabs-tabpane) {
  height: 100%;
}

.pane {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.pane-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  min-height: 24px;
}

.spacer {
  flex: 1;
}

.frame {
  flex: 1;
  min-height: 0;
  width: 100%;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--card);
}

.fallback {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 8px;
}

.plain {
  margin: 0;
  padding-left: 18px;
}
</style>
