<template>
  <aside v-if="menuPos === 'left'" class="sidebar" :class="{ collapsed }">
    <router-link :to="home" class="brand ink" :title="collapsed ? '智仓 DW-AI' : undefined">
      <img src="/logo.svg" alt="" class="logo" />
      <span v-if="!collapsed">
        <b>智仓</b>
        <small>DW-AI</small>
      </span>
    </router-link>
    <nav class="groups">
      <div v-for="(group, i) in groups" :key="group.title || i" class="group">
        <div v-if="group.title && !collapsed" class="gtitle">{{ group.title }}</div>
        <a-tooltip
          v-for="item in group.items"
          :key="item.path"
          :title="item.disabled ? item.disabledReason : collapsed ? item.label : ''"
        >
          <span v-if="item.disabled" class="item off">
            <component :is="icons[item.icon]" class="ico" />
            <span v-if="!collapsed">{{ item.label }}</span>
          </span>
          <router-link v-else :to="item.path" class="item" :class="{ active: active === item.path }">
            <component :is="icons[item.icon]" class="ico" />
            <span v-if="!collapsed">{{ item.label }}</span>
          </router-link>
        </a-tooltip>
      </div>
    </nav>
    <button type="button" class="fold" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggleFold">
      <MenuUnfoldOutlined v-if="collapsed" />
      <MenuFoldOutlined v-else />
      <span v-if="!collapsed">收起</span>
    </button>
    <UserPanel :menu-pos="menuPos" :collapsed="collapsed" />
  </aside>

  <div v-else-if="menuPos === 'top'" class="topbar">
    <router-link :to="home" class="brand ink">
      <img src="/logo.svg" alt="" class="logo" />
      <b>智仓</b>
    </router-link>
    <nav class="h-groups">
      <a-dropdown v-for="(group, i) in groups" :key="group.title || i" :trigger="['hover', 'click']">
        <span class="main" :class="{ active: groupActive(group) }">
          {{ group.title || group.items[0]?.label }}
          <DownOutlined class="caret" />
        </span>
        <template #overlay>
          <div class="sub">
            <a-tooltip v-for="item in group.items" :key="item.path" :title="item.disabled ? item.disabledReason : ''">
              <span v-if="item.disabled" class="item off">
                <component :is="icons[item.icon]" class="ico" />
                <span>{{ item.label }}</span>
              </span>
              <router-link v-else :to="item.path" class="item" :class="{ active: active === item.path }">
                <component :is="icons[item.icon]" class="ico" />
                <span>{{ item.label }}</span>
              </router-link>
            </a-tooltip>
          </div>
        </template>
      </a-dropdown>
    </nav>
    <UserPanel menu-pos="top" />
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
        <template v-for="(group, i) in groups" :key="group.title || i">
          <router-link
            v-if="isLeaf(group)"
            :to="group.items[0].path"
            class="cat"
            :class="{ active: groupActive(group), on: hoverKey === keyOf(group, i) }"
            @mouseenter="hoverKey = keyOf(group, i)"
            @click="open = false"
          >
            <component :is="icons[railIcon(group)]" class="ico" />
            <span>{{ railLabel(group) }}</span>
          </router-link>
          <button
            v-else
            type="button"
            class="cat"
            :class="{ active: groupActive(group), on: hoverKey === keyOf(group, i) }"
            @mouseenter="hoverKey = keyOf(group, i)"
          >
            <component :is="icons[railIcon(group)]" class="ico" />
            <span>{{ group.title }}</span>
            <RightOutlined class="chev" />
          </button>
        </template>
      </div>
      <div v-if="hoverGroup && !isLeaf(hoverGroup)" class="panel" :style="panelStyle">
        <a-tooltip
          v-for="item in hoverGroup.items"
          :key="item.path"
          :title="item.disabled ? item.disabledReason : ''"
        >
          <span v-if="item.disabled" class="item off light">
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
          </span>
          <router-link
            v-else
            :to="item.path"
            class="item light"
            :class="{ active: active === item.path }"
            @click="open = false"
          >
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
            <RightOutlined class="go" />
          </router-link>
        </a-tooltip>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { CloseOutlined, DownOutlined, MenuFoldOutlined, MenuOutlined, MenuUnfoldOutlined, RightOutlined } from '@ant-design/icons-vue';
import type { MenuPos } from '../stores/prefs';
import type { NavGroup } from '../config/nav';
import { activeNavPath } from '../config/nav';
import { navIcons } from '../config/navIcons';
import UserPanel from './UserPanel.vue';

const CAT_W = 200;
const ITEM_W = 176;
const ITEM_H = 36;
const PAD_Y = 24;
const ROW_H = 40;

const props = defineProps<{
  groups: NavGroup[];
  home: string;
  menuPos: MenuPos;
}>();

const NAV_COLLAPSE_KEY = 'dw-ai.proto.0.2.0.navCollapsed';
const icons = navIcons;
const route = useRoute();
const collapsed = ref(typeof localStorage !== 'undefined' && localStorage.getItem(NAV_COLLAPSE_KEY) === '1');
const open = ref(false);
const hoverKey = ref('');
const active = computed(() => activeNavPath(route.path, props.groups));

function keyOf(group: NavGroup, i: number) {
  return group.title || `leaf-${i}`;
}

function isLeaf(group: NavGroup) {
  return !group.title;
}

function railLabel(group: NavGroup) {
  return group.title || group.items[0]?.label || '';
}

function railIcon(group: NavGroup) {
  return group.icon || group.items[0]?.icon || 'AppstoreOutlined';
}

function groupActive(group: NavGroup) {
  return group.items.some((item) => active.value === item.path);
}

const hoverGroup = computed(() => {
  const i = props.groups.findIndex((g, idx) => keyOf(g, idx) === hoverKey.value);
  return i >= 0 ? props.groups[i] : null;
});

function panelCols(n: number) {
  if (n <= 2) return Math.max(1, n);
  if (n <= 6) return 3;
  return 4;
}

const panelStyle = computed(() => {
  const n = hoverGroup.value && !isLeaf(hoverGroup.value) ? hoverGroup.value.items.length : 0;
  const cols = panelCols(n);
  return {
    width: `${cols * ITEM_W}px`,
    gridTemplateColumns: `repeat(${cols}, ${ITEM_W}px)`,
  };
});

const drawerStyle = computed(() => {
  const catsH = PAD_Y + props.groups.length * ROW_H;
  const n = hoverGroup.value && !isLeaf(hoverGroup.value) ? hoverGroup.value.items.length : 0;
  const cols = panelCols(n);
  const rows = cols ? Math.ceil(n / cols) : 0;
  const panelH = n ? PAD_Y + rows * ITEM_H : 0;
  const height = Math.max(catsH, panelH);
  const width = CAT_W + (n ? cols * ITEM_W + 24 : 0);
  return { width: `${width}px`, height: `${height}px` };
});

function pickHover() {
  const i = props.groups.findIndex((g) => groupActive(g));
  const g = props.groups[i >= 0 ? i : 0];
  hoverKey.value = g ? keyOf(g, i >= 0 ? i : 0) : '';
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

.gtitle {
  margin: 12px 10px 6px;
  font-size: 11px;
  color: var(--menu-muted);
  letter-spacing: 0.08em;
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
  white-space: nowrap;
}

.main:hover,
.main.active {
  background: var(--menu-active-bg);
  color: var(--menu-active);
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

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 8px;
  color: var(--menu-muted);
  text-decoration: none;
  font-size: 13px;
  white-space: nowrap;
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

.ico {
  font-size: 14px;
  flex-shrink: 0;
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

.sidebar.collapsed .item {
  justify-content: center;
  padding: 9px 0;
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

.chev,
.go {
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
