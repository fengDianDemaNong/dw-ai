<template>
  <!--
    目录节点（有子菜单）。三种形态：

      · 侧栏顶层（`variant='side'` + `depth=0`）—— 用户说的「主菜单」：它就是一个**标题**，
        子项平铺在下面，与 V23 之前的分组标题观感一致。顶层不给折叠头：壳的第一层
        本来就该一眼看全，多一次点击换不来什么。
      · 侧栏深层 —— 可折叠的一行，右侧一个箭头是纯粹的展开/收起，行主体若自己有 `path`
        就跳过去（产品报的「目录自己也指向一个概览页」是常见形态，不能因为它是目录
        就点不动）。当前路由在子树里时默认展开 —— 否则会出现「高亮了一个看不见的项」。
      · 飞出面板（`variant='panel'`，顶栏的下拉与抽屉右侧）—— **不折叠、缩进平铺**：
        面板里再嵌一层弹层，定位与悬停判断都会变得很难说清。
  -->
  <template v-if="kids.length">
    <div v-if="isTopSide" class="gtitle">{{ item.label }}</div>
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
    <div v-show="isTopSide || open" class="kids" :class="{ nested: !isTopSide && !collapsed }">
      <NavNode
        v-for="(kid, k) in kids"
        :key="kidKey(kid, k)"
        :item="kid"
        :active-path="activePath"
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
import { navIcons } from '../config/navIcons';

defineOptions({ name: 'NavNode' });

const props = withDefaults(
  defineProps<{
    item: NavItem;
    /** 当前命中的路径（`activeNavPath` 的结果），用来给叶子加高亮、给目录决定默认展开。 */
    activePath?: string;
    /** 0 = 壳的顶层（用户说的「主菜单」）。 */
    depth?: number;
    /** `side` = 侧栏（深色底）；`panel` = 飞出的浅色面板。 */
    variant?: 'side' | 'panel';
    /** 侧栏收起态：只显示图标。 */
    collapsed?: boolean;
  }>(),
  { activePath: undefined, depth: 0, variant: 'side', collapsed: false }
);

const emit = defineEmits<{ navigate: [] }>();

const icons = navIcons;
const kids = computed(() => props.item.children ?? []);
/** 顶层目录在侧栏里是「标题 + 平铺」，不折叠（见模板顶部注释）。 */
const isTopSide = computed(() => props.variant === 'side' && props.depth === 0);
const style = computed(() => (props.variant === 'panel' ? 'light' : ''));

/**
 * 默认**展开**：这是与 V23 之前逐字一致的观感 —— 那时菜单没有层级，所有项一次看全，
 * 默认折叠会让人以为菜单少了。折叠是用户主动收起来的动作，不是这里的默认。
 */
const open = ref(true);

const tip = computed(() => (props.item.disabled ? props.item.disabledReason ?? '' : ''));

/** 同一个面板里的 key：`path` 可能为空串（目录、占位项），退到 label 再补序号。 */
function kidKey(kid: NavItem, k: number) {
  return kid.id || `${kid.path || kid.label}@${k}`;
}
</script>

<style scoped>
.gtitle {
  margin: 12px 10px 6px;
  font-size: 11px;
  color: var(--menu-muted);
  letter-spacing: 0.08em;
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
