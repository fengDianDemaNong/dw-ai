<template>
  <aside v-if="menuPos === 'left'" class="sidebar" :class="{ collapsed }">
    <router-link :to="home" class="brand ink" :title="collapsed ? '智仓 DW-AI' : undefined">
      <img src="/logo.svg" alt="" class="logo" />
      <span v-if="!collapsed">
        <b>智仓</b>
        <small>DW-AI</small>
      </span>
    </router-link>
    <!--
      菜单是一棵树（V23 起），三种模式共用 `NavNode` 递归渲染 —— 以前这里是三份独立的
      两层 `v-for`，只改一种模式会让另外两种在多层菜单下露馅（顶栏的下拉里点不出第三层）。
      顶层节点在侧栏里若是个目录，渲染成「标题 + 子项平铺」，与 V23 之前的分组标题一样。
    -->
    <nav class="groups">
      <NavNode
        v-for="(item, i) in items"
        :key="keyOf(item, i)"
        :item="item"
        :active-path="active"
        :depth="0"
        variant="side"
        :collapsed="collapsed"
      />
    </nav>
    <button type="button" class="fold" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggleFold">
      <MenuUnfoldOutlined v-if="collapsed" />
      <MenuFoldOutlined v-else />
      <span v-if="!collapsed">收起</span>
    </button>
    <UserPanel v-if="!standalone" :menu-pos="menuPos" :collapsed="collapsed" />
  </aside>

  <div v-else-if="menuPos === 'top'" class="topbar">
    <router-link :to="home" class="brand ink">
      <img src="/logo.svg" alt="" class="logo" />
      <b>智仓</b>
    </router-link>
    <nav class="h-groups">
      <template v-for="(item, i) in items" :key="keyOf(item, i)">
        <!-- 有子菜单的顶层项才套下拉；没有的（如「项目管理」这种单独一项）直接是个链接，
             否则点开是一个只有一行、与标题同名的空面板。 -->
        <a-dropdown v-if="hasKids(item)" :trigger="['hover', 'click']">
          <span class="main" :class="{ active: subtreeActive(item) }">
            <component :is="icons[item.icon]" class="ico" />
            {{ item.label }}
            <DownOutlined class="caret" />
          </span>
          <template #overlay>
            <div class="sub">
              <NavNode
                v-for="(kid, k) in item.children"
                :key="kidKey(kid, k)"
                :item="kid"
                :active-path="active"
                :depth="0"
                variant="panel"
              />
            </div>
          </template>
        </a-dropdown>
        <a
          v-else-if="item.external && item.href"
          :href="item.href"
          target="_blank"
          rel="noreferrer"
          class="main"
        >
          <component :is="icons[item.icon]" class="ico" />
          {{ item.label }}
        </a>
        <span v-else-if="item.disabled" class="main off" :title="item.disabledReason">
          <component :is="icons[item.icon]" class="ico" />
          {{ item.label }}
        </span>
        <router-link v-else :to="item.path" class="main" :class="{ active: active === item.path }">
          <component :is="icons[item.icon]" class="ico" />
          {{ item.label }}
        </router-link>
      </template>
    </nav>
    <UserPanel v-if="!standalone" menu-pos="top" />
  </div>

  <div v-else class="nav">
    <button type="button" class="burger" :class="{ on: open }" aria-label="打开菜单" @click="toggle">
      <CloseOutlined v-if="open" />
      <MenuOutlined v-else />
    </button>
    <router-link :to="home" class="brand light" @click="open = false">
      <img src="/logo.svg" alt="" class="logo" />
      <span>
        <b>智仓</b>
        <small>DW-AI</small>
      </span>
    </router-link>

    <div v-if="open" class="mask" @click="open = false" />
    <div v-if="open" class="drawer" :style="drawerStyle">
      <div class="cats">
        <template v-for="(item, i) in items" :key="keyOf(item, i)">
          <!-- 叶子（没有子菜单）：点它就是导航，鼠标移上去不必再开右侧面板。 -->
          <router-link
            v-if="!hasKids(item) && !item.disabled && !item.external"
            :to="item.path"
            class="cat"
            :class="{ active: active === item.path }"
            @click="open = false"
          >
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
          </router-link>
          <span v-else-if="!hasKids(item)" class="cat off" :title="item.disabledReason">
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
          </span>
          <button
            v-else
            type="button"
            class="cat"
            :class="{ active: subtreeActive(item), on: hoverIndex === i }"
            @mouseenter="hoverIndex = i"
          >
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
            <RightOutlined class="chev" />
          </button>
        </template>
      </div>
      <div v-if="hoverItem && hasKids(hoverItem)" class="panel" :style="panelStyle">
        <NavNode
          v-for="(kid, k) in hoverItem.children"
          :key="kidKey(kid, k)"
          :item="kid"
          :active-path="active"
          :depth="0"
          variant="panel"
          @navigate="open = false"
        />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { CloseOutlined, DownOutlined, MenuFoldOutlined, MenuOutlined, MenuUnfoldOutlined, RightOutlined } from '@ant-design/icons-vue';
import type { MenuPos } from '../stores/prefs';
import type { NavItem } from '../config/nav';
import { activeNavPath } from '../config/nav';
import { navIcons } from '../config/navIcons';
import NavNode from './NavNode.vue';
import UserPanel from './UserPanel.vue';
import { isStandalone } from '../config/runtime';

const standalone = isStandalone();
const CAT_W = 200;
const ITEM_W = 176;
const ITEM_H = 36;
const PAD_Y = 24;
const ROW_H = 40;

const props = defineProps<{
  /**
   * 当前壳的菜单树（顶层节点）。V23 起不再有「分组」这一层包装 ——
   * 分组就是一个有 `children` 的节点，两种东西在同一个类型里（见 config/nav.ts）。
   */
  items: NavItem[];
  home: string;
  menuPos: MenuPos;
}>();

const NAV_COLLAPSE_KEY = 'dw-ai.navCollapsed';
const icons = navIcons;
const route = useRoute();
const collapsed = ref(typeof localStorage !== 'undefined' && localStorage.getItem(NAV_COLLAPSE_KEY) === '1');
const open = ref(false);
const hoverIndex = ref(0);
const active = computed(() => activeNavPath(route.path, props.items));

function hasKids(item: NavItem): boolean {
  return !!item.children?.length;
}

/**
 * 顶层节点在列表里的 key。`id` 由服务端给（产品节点是 `{product}/{id}`，org 节点是
 * `nav-*`），只有平台壳那几项是前端硬编码的、没有 id —— 退回 `path || label` 再补序号。
 */
function keyOf(item: NavItem, i: number) {
  return item.id || `${item.path || item.label}@${i}`;
}

/** 同一层里子项的 key（`path` 可能为空串，见 NavNode 里的同名函数）。 */
function kidKey(kid: NavItem, k: number) {
  return kid.id || `${kid.path || kid.label}@${k}`;
}

/** 子树里有没有当前命中的项 —— 顶栏用它给父项加高亮（顶层自己不是一条路由）。 */
function subtreeActive(item: NavItem): boolean {
  if (item.path && item.path === active.value) return true;
  return (item.children ?? []).some(subtreeActive);
}

const hoverItem = computed(() => props.items[hoverIndex.value] ?? null);

function panelCols(n: number) {
  if (n <= 2) return Math.max(1, n);
  if (n <= 6) return 3;
  return 4;
}

const panelStyle = computed(() => {
  const n = hoverItem.value ? hoverItem.value.children?.length ?? 0 : 0;
  const cols = panelCols(n);
  return {
    width: `${cols * ITEM_W}px`,
    gridTemplateColumns: `repeat(${cols}, ${ITEM_W}px)`,
  };
});

const drawerStyle = computed(() => {
  const catsH = PAD_Y + props.items.length * ROW_H;
  const n = hoverItem.value ? hoverItem.value.children?.length ?? 0 : 0;
  const cols = panelCols(n);
  const rows = cols ? Math.ceil(n / cols) : 0;
  const panelH = n ? PAD_Y + rows * ITEM_H : 0;
  const height = Math.max(catsH, panelH);
  const width = CAT_W + (n ? cols * ITEM_W + 24 : 0);
  return { width: `${width}px`, height: `${height}px` };
});

/** 打开抽屉时默认选中「当前所在的那一支」，没有就选第一个 —— 右侧面板总该有内容。 */
function pickHover() {
  const i = props.items.findIndex(subtreeActive);
  hoverIndex.value = i >= 0 ? i : 0;
}

function toggle() {
  open.value = !open.value;
  if (open.value) pickHover();
}

function toggleFold() {
  collapsed.value = !collapsed.value;
  localStorage.setItem(NAV_COLLAPSE_KEY, collapsed.value ? '1' : '0');
}

function onKey(ev: KeyboardEvent) {
  if (ev.key === 'Escape') open.value = false;
}

watch(
  () => route.path,
  () => {
    open.value = false;
  }
);

onMounted(() => document.addEventListener('keydown', onKey));
onUnmounted(() => document.removeEventListener('keydown', onKey));
</script>

<style scoped>
.sidebar {
  width: 212px;
  flex-shrink: 0;
  height: 100vh;
  background: var(--menu-bg);
  color: var(--menu-fg);
  display: flex;
  flex-direction: column;
  transition: width 0.18s ease;
}

.sidebar.collapsed {
  width: 64px;
}

.topbar {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 52px;
  padding: 0 16px;
  background: var(--menu-bg);
  color: var(--menu-fg);
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  flex-shrink: 0;
}

.brand.ink {
  height: 56px;
  padding: 0 16px;
  color: var(--menu-fg);
  border-bottom: 1px solid var(--menu-line);
}

.topbar .brand.ink {
  height: auto;
  padding: 0 8px 0 0;
  border-bottom: 0;
}

.brand.light {
  gap: 8px;
  color: var(--menu-fg);
}

.logo {
  width: 28px;
  height: 28px;
}

.brand.light .logo {
  width: 26px;
  height: 26px;
}

.brand b {
  display: block;
  font-size: 15px;
  line-height: 1.1;
}

.brand small {
  display: block;
  font-size: 10px;
  letter-spacing: 0.12em;
  color: var(--menu-active);
}

.groups {
  flex: 1;
  overflow: auto;
  padding: 10px 8px 16px;
}

.h-groups {
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  overflow: auto;
}

.main {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: 8px;
  color: var(--menu-muted);
  cursor: pointer;
  font-size: 13px;
  text-decoration: none;
  white-space: nowrap;
}

.main:hover,
.main.active {
  background: var(--menu-active-bg);
  color: var(--menu-active);
}

.main.off {
  opacity: 0.4;
  cursor: not-allowed;
}

.caret {
  font-size: 10px;
}

.sub {
  min-width: 160px;
  padding: 6px;
  background: var(--menu-bg);
  border: 1px solid var(--menu-line);
  border-radius: 8px;
}

.fold {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 8px 8px;
  padding: 7px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--menu-muted);
  cursor: pointer;
  font-size: 13px;
}

.fold:hover {
  background: var(--menu-hover);
  color: var(--menu-fg);
}

.sidebar.collapsed .brand {
  justify-content: center;
  padding: 0;
}

.sidebar.collapsed .fold {
  justify-content: center;
  margin: 0 6px 8px;
  padding: 9px 0;
}

.nav {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.burger {
  width: 36px;
  height: 36px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--menu-fg);
  font-size: 18px;
  cursor: pointer;
}

.burger:hover,
.burger.on {
  background: var(--menu-hover);
  color: var(--menu-active);
}

.mask {
  position: fixed;
  inset: 0;
  top: 48px;
  z-index: 30;
}

.drawer {
  position: absolute;
  top: 100%;
  left: 0;
  display: flex;
  align-items: stretch;
  overflow: hidden;
  background: #fff;
  border: 1px solid var(--line);
  border-top: 0;
  box-shadow: 0 16px 40px rgba(15, 23, 42, 0.12);
  z-index: 40;
}

.cats {
  width: 200px;
  flex-shrink: 0;
  padding: 12px 8px;
  background: #f7f8fa;
}

.cat {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  height: 40px;
  padding: 0 12px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #334155;
  font-size: 13px;
  text-align: left;
  text-decoration: none;
  cursor: pointer;
}

.cat:hover,
.cat.on {
  background: #fff;
  color: var(--primary);
}

.cat.active {
  color: var(--primary);
  font-weight: 600;
}

.cat.off {
  opacity: 0.4;
  cursor: not-allowed;
}

.ico {
  font-size: 14px;
  flex-shrink: 0;
}

.chev {
  margin-left: auto;
  font-size: 10px;
  color: #94a3b8;
}

.panel {
  display: grid;
  align-content: start;
  gap: 4px 0;
  padding: 12px 12px;
}
</style>
