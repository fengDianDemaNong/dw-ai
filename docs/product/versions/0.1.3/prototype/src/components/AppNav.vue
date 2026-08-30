<template>
  <aside v-if="menuPos === 'left'" class="sidebar" :class="{ collapsed }">
    <router-link :to="home" class="brand" :title="collapsed ? '智仓 DW-AI' : undefined">
      <img src="/logo.svg" alt="" class="logo" />
      <span v-if="!collapsed">
        <b>智仓</b>
        <small>DW-AI</small>
      </span>
    </router-link>
    <nav class="groups">
      <div v-for="(group, i) in groups" :key="i" class="group">
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
    <button type="button" class="fold" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggle">
      <MenuUnfoldOutlined v-if="collapsed" />
      <MenuFoldOutlined v-else />
      <span v-if="!collapsed">收起</span>
    </button>
    <UserPanel :menu-pos="menuPos" :collapsed="collapsed" />
  </aside>
  <div v-else class="topbar">
    <router-link :to="home" class="brand">
      <img src="/logo.svg" alt="" class="logo" />
      <b>智仓</b>
    </router-link>
    <nav class="h-groups">
      <a-dropdown v-for="(group, i) in groups" :key="i" :trigger="['hover', 'click']">
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
    <UserPanel :menu-pos="menuPos" />
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import { DownOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons-vue';
import type { MenuPos } from '../stores/prefs';
import type { NavGroup } from '../config/nav';
import { activeNavPath } from '../config/nav';
import { navIcons } from '../config/navIcons';
import UserPanel from './UserPanel.vue';

const props = defineProps<{
  groups: NavGroup[];
  home: string;
  menuPos: MenuPos;
}>();

const NAV_COLLAPSE_KEY = 'dw-ai.proto.0.1.3.navCollapsed';
const icons = navIcons;
const route = useRoute();
const collapsed = ref(typeof localStorage !== 'undefined' && localStorage.getItem(NAV_COLLAPSE_KEY) === '1');
const active = computed(() => activeNavPath(route.path, props.groups));

function toggle() {
  collapsed.value = !collapsed.value;
  localStorage.setItem(NAV_COLLAPSE_KEY, collapsed.value ? '1' : '0');
}

function groupActive(group: NavGroup) {
  return group.items.some((item) => active.value === item.path);
}
</script>

<style scoped>
.sidebar {
  width: 212px;
  flex-shrink: 0;
  height: 100vh;
  background: var(--ink);
  color: #cbd5e1;
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
  background: var(--ink);
  color: #cbd5e1;
  flex-shrink: 0;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 56px;
  padding: 0 16px;
  color: #fff;
  text-decoration: none;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.topbar .brand {
  height: auto;
  padding: 0 8px 0 0;
  border-bottom: 0;
}

.logo {
  width: 28px;
  height: 28px;
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
  color: #67e8f9;
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
  color: #64748b;
  letter-spacing: 0.08em;
}

.main {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: 8px;
  color: #94a3b8;
  cursor: pointer;
  font-size: 13px;
  white-space: nowrap;
}

.main:hover,
.main.active {
  background: rgba(34, 211, 238, 0.12);
  color: #67e8f9;
}

.caret {
  font-size: 10px;
}

.sub {
  min-width: 160px;
  padding: 6px;
  background: var(--ink);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-radius: 8px;
  color: #94a3b8;
  text-decoration: none;
  font-size: 13px;
  white-space: nowrap;
}

.item:hover {
  background: rgba(255, 255, 255, 0.04);
  color: #e2e8f0;
}

.item.active {
  background: rgba(34, 211, 238, 0.12);
  color: #67e8f9;
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
  color: #94a3b8;
  cursor: pointer;
  font-size: 13px;
}

.fold:hover {
  background: rgba(255, 255, 255, 0.04);
  color: #e2e8f0;
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
</style>
