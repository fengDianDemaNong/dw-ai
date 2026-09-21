<template>
  <aside v-if="group && group.title" class="shortcut" :class="{ collapsed }">
    <template v-if="!collapsed">
      <div class="stitle">{{ group.title }}</div>
      <nav>
        <a-tooltip
          v-for="item in group.items"
          :key="item.path"
          :title="item.disabled ? item.disabledReason : ''"
        >
          <span v-if="item.disabled" class="item off">
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
          </span>
          <router-link v-else :to="item.path" class="item" :class="{ active: active === item.path }">
            <component :is="icons[item.icon]" class="ico" />
            <span>{{ item.label }}</span>
          </router-link>
        </a-tooltip>
      </nav>
    </template>
    <button type="button" class="fold" :title="collapsed ? '展开菜单' : '收起菜单'" @click="toggle">
      {{ collapsed ? '>' : '<' }}
    </button>
  </aside>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import type { NavGroup } from '../config/nav';
import { activeNavPath } from '../config/nav';
import { navIcons } from '../config/navIcons';

const FOLD_KEY = 'dw-ai.proto.0.2.0.shortcutCollapsed';

const props = defineProps<{
  group?: NavGroup | null;
  groups: NavGroup[];
}>();

const icons = navIcons;
const route = useRoute();
const collapsed = ref(typeof localStorage !== 'undefined' && localStorage.getItem(FOLD_KEY) === '1');
const active = computed(() => activeNavPath(route.path, props.groups));

function toggle() {
  collapsed.value = !collapsed.value;
  localStorage.setItem(FOLD_KEY, collapsed.value ? '1' : '0');
}
</script>

<style scoped>
.shortcut {
  position: relative;
  z-index: 2;
  width: 200px;
  flex-shrink: 0;
  height: 100%;
  padding: 16px 10px;
  background: var(--card);
  border-right: 1px solid var(--line);
  overflow: visible;
}

.shortcut.collapsed {
  width: 0;
  padding: 0;
  border-right: 0;
  background: transparent;
}

.stitle {
  margin: 0 8px 10px;
  font-size: 13px;
  font-weight: 650;
  color: var(--text);
}

.item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  color: #475569;
  text-decoration: none;
  font-size: 13px;
  white-space: nowrap;
}

.item:hover {
  background: #f1f5f9;
  color: var(--text);
}

.item.active {
  background: #e0f2fe;
  color: #0e7490;
  font-weight: 600;
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
  position: absolute;
  top: 50%;
  right: -12px;
  z-index: 5;
  width: 16px;
  height: 40px;
  margin: 0;
  padding: 0;
  border: 1px solid var(--line);
  border-left: 0;
  border-radius: 0 8px 8px 0;
  background: var(--card);
  color: #64748b;
  font-size: 12px;
  line-height: 40px;
  cursor: pointer;
  transform: translateY(-50%);
  box-shadow: 2px 0 8px rgba(15, 23, 42, 0.06);
}

.shortcut.collapsed .fold {
  right: -16px;
  border-left: 1px solid var(--line);
}

.fold:hover {
  color: var(--primary);
  background: #fff;
}
</style>
