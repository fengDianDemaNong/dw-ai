<template>
  <header class="topbar">
    <div class="crumbs">
      <template v-for="(part, i) in crumbs" :key="i">
        <span v-if="i > 0" class="crumb-sep">/</span>
        <router-link v-if="part.to" :to="part.to" class="crumb crumb-link">{{ part.text }}</router-link>
        <span v-else class="crumb">{{ part.text }}</span>
      </template>
    </div>
    <ProjectSwitcher v-if="!topNav && !standalone" />
  </header>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { activeNavPath, groupOf, navItems } from '../../config/nav';
import { preferences } from '../../stores/preferences';
import { uiState } from '../../stores/ui';
import ProjectSwitcher from '../ProjectSwitcher/index.vue';
import { isStandalone } from '../../config/runtime';

const route = useRoute();
const standalone = isStandalone();
const topNav = computed(() => preferences.navPosition === 'top');

/**
 * 面包屑：分组 / 菜单项 / 当前对象。
 *
 * 前两段从 config/nav.ts 推导，改菜单等于改面包屑，不用两头维护。
 * 第三段（表名之类）由页面写进 uiState.crumb。
 */
const crumbs = computed(() => {
  const parts: { text: string; to?: string }[] = [];
  const group = groupOf(route.path);
  if (group) parts.push({ text: group });

  const activePath = activeNavPath(route.path);
  const item = navItems.find((n) => n.path === activePath);
  if (item) {
    // 已经站在这一项上时不给链接，点自己没有意义
    parts.push({ text: item.label, to: uiState.crumb ? item.path : undefined });
  }

  if (uiState.crumb) parts.push({ text: uiState.crumb });

  // 不在菜单里的页面（比如搜索结果）在菜单里找不到落点，退回路由标题，
  // 否则整条面包屑是空的，看着像坏了
  if (!parts.length && route.meta?.title) parts.push({ text: route.meta.title });
  return parts;
});
</script>

<style scoped>
.topbar {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}

.crumbs {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  overflow: hidden;
  flex: 1;
}

.crumb {
  font-size: 14px;
  color: #6b7280;
  white-space: nowrap;
}

.crumb:last-child {
  color: #1f2937;
}

.crumb-link:hover {
  color: #1677ff;
}

.crumb-sep {
  color: #d1d5db;
}

</style>
