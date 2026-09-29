<template>
  <!--
    目录节点（有子菜单）。四种形态：

      · 侧栏顶层的**目录**（`variant='side'` + `depth=0`，无 `path`）—— 用户说的「主菜单」：
        整行是一个**可折叠的组标题**，点它展开/收起下面的子项。**默认只展开当前页所在的
        那一组**，其余收起（用户 2026-09-27：「系统管理，主菜单，做成可以收起的，点击才展示
        子菜单」）。类名仍是 `.gtitle`：e2e 按它定位顶层标题，换名字会连带打破三条断言。
      · 侧栏顶层的**页面**（同上，但有 `path`）—— 标题主体进页面、右侧箭头单独控展开。
        与下面那条深层形态同一个道理：一个整行可点的按钮没地方同时表达「跳转」与「展开」。
      · 侧栏深层 —— 可折叠的一行，右侧一个箭头是纯粹的展开/收起，行主体若自己有 `path`
        就跳过去（产品报的「目录自己也指向一个概览页」是常见形态，不能因为它是目录
        就点不动）。恒默认展开 —— 与 V23 之前逐字一致，那时菜单没有层级、所有项一次看全。
      · 飞出面板（`variant='panel'`，顶栏的下拉与抽屉右侧）—— **不折叠、缩进平铺**：
        面板里再嵌一层弹层，定位与悬停判断都会变得很难说清。
  -->
  <template v-if="kids.length">
    <!--
      顶层的两种形态，**判据是「它自己有没有页面」**：

        · 无 `path` = 顶层**目录** —— 整行是一个可折叠的组标题，点它展开/收起。
          **这一支逐字不变**：三条 e2e 用例按 `.gtitle` 与它的 `aria-expanded` 定位顶层分组。
        · 有 `path` = 顶层**页面**且它还带子菜单 —— 标题主体进页面、右侧箭头单独控展开，
          照深层 `.dir` 的形态拆开。一个整行可点的按钮没地方同时表达「跳转」与「展开」，
          所以过去顶层一律被渲染成按钮，代价是「顶层 + 有 path」这种配置点不动。
    -->
    <button
      v-if="isTopSide && !item.path"
      type="button"
      class="gtitle"
      :class="{ collapsed }"
      :aria-expanded="open"
      :title="open ? '收起子菜单' : '展开子菜单'"
      @click="open = !open"
    >
      <!-- 文字仍然只有 label 一个文本节点：e2e 用 `allInnerTexts()` 读它、按 `exact` 匹配 -->
      <span>{{ item.label }}</span>
      <!-- 展开态由 `aria-expanded` 表达（折叠控件的标准语义，e2e 与辅助技术都吃它）。
           **别用 `collapsed` 类判**：那个类说的是「侧栏整体收成 64px 图标条」，见样式区。 -->
      <DownOutlined class="caret" :class="{ shut: !open }" />
    </button>
    <div v-else-if="isTopSide" class="gtitle" :class="{ collapsed }" :aria-expanded="open">
      <router-link :to="item.path" class="glabel" @click="emit('navigate')">
        <span>{{ item.label }}</span>
      </router-link>
      <button
        type="button"
        class="twist"
        :title="open ? '收起子菜单' : '展开子菜单'"
        @click="open = !open"
      >
        <DownOutlined class="caret" :class="{ shut: !open }" />
      </button>
    </div>
    <div v-else class="dir">
      <router-link
        v-if="item.path"
        :to="item.path"
        class="item"
        :class="[style, { active: item.path === activePath, collapsed }]"
        @click="emit('navigate')"
      >
        <component :is="icons[item.icon]" class="ico" />
        <span v-if="!collapsed">{{ item.label }}</span>
      </router-link>
      <button
        v-else
        type="button"
        class="item"
        :class="[style, { collapsed }]"
        @click="open = !open"
      >
        <component :is="icons[item.icon]" class="ico" />
        <span v-if="!collapsed">{{ item.label }}</span>
      </button>
      <button
        v-if="!collapsed"
        type="button"
        class="twist"
        :class="style"
        :title="open ? '收起子菜单' : '展开子菜单'"
        @click="open = !open"
      >
        <DownOutlined class="caret" :class="{ shut: !open }" />
      </button>
    </div>
    <!-- `nested`（缩进线）的判据**不要**跟着改成 `open`：它表达的是「这一层比壳的顶层深」，
         与展开态无关。e2e 有一条按 `.kids.nested` 计数断言「只有深层才有缩进线」。 -->
    <div v-show="open" class="kids" :class="{ nested: !isTopSide && !collapsed }">
      <NavNode
        v-for="(kid, k) in kids"
        :key="kidKey(kid, k)"
        :item="kid"
        :active-path="activePath"
        :route-path="routePath"
        :depth="depth + 1"
        :variant="variant"
        :collapsed="collapsed"
        @navigate="emit('navigate')"
      />
    </div>
  </template>

  <!-- 叶子：三种形态（置灰 / 整页跳走 / 壳内路由），与 V23 之前逐字一致。 -->
  <a-tooltip v-else :title="tip">
    <span v-if="item.disabled" class="item off" :class="[style, { collapsed }]">
      <component :is="icons[item.icon]" class="ico" />
      <span v-if="!collapsed">{{ item.label }}</span>
    </span>
    <!--
      没有子端、不能嵌进壳的产品走整页跳转（见 config/products.ts 的 isEmbeddable）。
      新标签页打开：它是「去别的站点」，不是本站的一次导航 —— 在同一标签打开
      会把当前壳（含已选租户/项目）整页带走，用户回退才能回来。
    -->
    <a
      v-else-if="item.external && item.href"
      :href="item.href"
      target="_blank"
      rel="noreferrer"
      class="item"
      :class="[style, { collapsed }]"
      @click="emit('navigate')"
    >
      <component :is="icons[item.icon]" class="ico" />
      <span v-if="!collapsed">{{ item.label }}</span>
    </a>
    <router-link
      v-else
      :to="item.path"
      class="item"
      :class="[style, { active: item.path === activePath, collapsed }]"
      @click="emit('navigate')"
    >
      <component :is="icons[item.icon]" class="ico" />
      <span v-if="!collapsed">{{ item.label }}</span>
    </router-link>
  </a-tooltip>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { DownOutlined } from '@ant-design/icons-vue';
import type { NavItem } from '../config/nav';
import { subtreeOnSameBranch } from '../config/nav';
import { navIcons } from '../config/navIcons';

defineOptions({ name: 'NavNode' });

const props = withDefaults(
  defineProps<{
    item: NavItem;
    /** 当前命中的**菜单项**路径（`activeNavPath` 的结果），用来给叶子加高亮。 */
    activePath?: string;
    /**
     * 当前**路由**路径（`route.path`）。
     *
     * <p>与 `activePath` 分开是因为它俩真的会不一样：`activeNavPath` 是「命中了哪条菜单项」，
     * 没命中时是 `undefined`。项目壳首页 `/org/project/{code}` 就是这种情况 —— 没有任何
     * 菜单项等于它，但用户明明站在「项目」那一组里。拿 `activePath` 判「当前页在哪一组」，
     * 一进项目整个侧栏就默认收起（成员管理、返回工作台全都不见了）。
     */
    routePath?: string;
    /** 0 = 壳的顶层（用户说的「主菜单」）。 */
    depth?: number;
    /** `side` = 侧栏（深色底）；`panel` = 飞出的浅色面板。 */
    variant?: 'side' | 'panel';
    /** 侧栏收起态：只显示图标。 */
    collapsed?: boolean;
  }>(),
  { activePath: undefined, routePath: '', depth: 0, variant: 'side', collapsed: false }
);

const emit = defineEmits<{ navigate: [] }>();

const icons = navIcons;
const kids = computed(() => props.item.children ?? []);
/** 顶层目录在侧栏里是「可折叠的组标题」（见模板顶部注释）。 */
const isTopSide = computed(() => props.variant === 'side' && props.depth === 0);
const style = computed(() => (props.variant === 'panel' ? 'light' : ''));

/**
 * 当前页属不属于这棵子树 —— 顶层分组据此决定默认展开。
 *
 * <p>判据是「同支」而不是「精确命中」（`subtreeOnSameBranch`，含相等）：项目壳的首页
 * `/org/project/{code}` 不等于组里任何一项，只按精确匹配的话，一进项目侧栏就整片收起。
 * 逻辑复用 config/nav.ts 那份，别再写一遍递归。
 */
const containsActive = computed(
  () => !!props.routePath && subtreeOnSameBranch(props.item, props.routePath)
);

/**
 * 展开态，顶层与深层的默认**不一样**：
 *
 * <p>· 顶层（分组标题）：只展开**当前页所在的那一组**，其余收起 —— 用户 2026-09-27 的裁定。
 * 一眼能看出自己在哪，侧栏也不会被撑得很长。
 * <p>· 深层：恒默认展开，与 V23 之前逐字一致 —— 那时菜单没有层级、所有项一次看全，
 * 默认折叠会让人以为菜单少了。别顺手把这条也改成 `containsActive`。
 */
const open = ref(!isTopSide.value || containsActive.value);

/**
 * 路由一变就重算顶层的展开态：进了别的组，当前组自动展开、原来那组自动收起。
 *
 * <p>副作用是「用户手动展开的组，切页后会被收回」—— 这是「当前页所在组展开，其余收起」
 * 的直接结果，不是漏了状态持久化。真觉得别扭的话，改这里，别去动上面的初值。
 */
watch(
  () => props.routePath,
  () => {
    if (isTopSide.value) open.value = containsActive.value;
  }
);

const tip = computed(() => (props.item.disabled ? props.item.disabledReason ?? '' : ''));

/** 同一个面板里的 key：`path` 可能为空串（目录、占位项），退到 label 再补序号。 */
function kidKey(kid: NavItem, k: number) {
  return kid.id || `${kid.path || kid.label}@${k}`;
}
</script>

<style scoped>
/* 顶层分组标题：整行可点，点它展开/收起。观感尽量贴着「加折叠之前那行纯文本」——
   字号、颜色、字距都照旧，只补上铺满宽度与 hover 反馈（不然没人知道它能点）。 */
.gtitle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  width: 100%;
  margin: 12px 0 6px;
  padding: 4px 10px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--menu-muted);
  font-family: inherit;
  font-size: 11px;
  letter-spacing: 0.08em;
  text-align: left;
  cursor: pointer;
}

.gtitle:hover {
  background: var(--menu-hover);
  color: var(--menu-fg);
}

/* 注意：`.gtitle.collapsed` 说的是**侧栏整体收成 64px 图标条**（`collapsed` prop），
   与「这一组展开还是收起」无关 —— 后者是 `open`，由 `aria-expanded` 表达。
   这里只是图标条态下标题 + 右对齐箭头会互相挤（标题反而被挤出去），所以把箭头藏掉。 */
.gtitle.collapsed {
  justify-content: center;
  padding: 4px 0;
}

.gtitle.collapsed .caret {
  display: none;
}

/* 图标条态下站不住「标题 + 箭头」两个元素（上面那条按钮态是藏箭头，这里连箭头按钮
   一起藏掉 —— 只藏图标会留下一个空的点击区，点了没反应）。 */
.gtitle.collapsed .twist {
  display: none;
}

/* 顶层**页面**（有 `path`）的标题主体：铺满整行，点它进页面（见模板那一支）。
   父级 `.gtitle` 的 hover 背景已经给了反馈，这里不再叠一层。 */
.glabel {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: inherit;
  text-decoration: none;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--menu-muted);
  text-decoration: none;
  font-size: 13px;
  text-align: left;
  white-space: nowrap;
  cursor: pointer;
  width: 100%;
}

.item:hover {
  background: var(--menu-hover);
  color: var(--menu-fg);
}

.item.active {
  background: var(--menu-active-bg);
  color: var(--menu-active);
}

.item.light {
  height: 36px;
  padding: 0 12px;
  color: #334155;
}

.item.light:hover {
  background: #f1f5f9;
  color: var(--text);
}

.item.light.active {
  background: #e0f2fe;
  color: #0e7490;
}

.item.off {
  opacity: 0.4;
  cursor: not-allowed;
}

/* 侧栏收起态：只有图标，居中。这一条以前在 AppNav 的 `.sidebar.collapsed .item` 里 ——
   菜单项搬进本组件后 scoped 样式管不到子组件内部，所以跟着搬过来。 */
.item.collapsed {
  justify-content: center;
  padding: 9px 0;
}

.ico {
  font-size: 14px;
  flex-shrink: 0;
}

.dir {
  display: flex;
  align-items: center;
}

.twist {
  border: 0;
  background: transparent;
  color: var(--menu-muted);
  cursor: pointer;
  padding: 4px 6px;
  border-radius: 6px;
  flex-shrink: 0;
}

.twist:hover {
  background: var(--menu-hover);
}

.twist.light {
  color: #94a3b8;
}

.twist.light:hover {
  background: #f1f5f9;
}

.caret {
  font-size: 10px;
  transition: transform 0.15s ease;
}

.caret.shut {
  transform: rotate(-90deg);
}

/* 深层子树：一条竖线 + 缩进，让「这是谁的子项」不用靠猜。 */
.kids.nested {
  margin-left: 18px;
  padding-left: 6px;
  border-left: 1px solid var(--menu-line);
}
</style>
