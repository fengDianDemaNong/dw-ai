<template>
  <header class="topnav">
    <router-link to="/lineage" class="brand">
      <img src="/logo.png" class="brand-logo" alt="logo" />
      <span class="brand-name">sql-tools</span>
    </router-link>

    <nav class="topnav-items">
      <template v-for="(group, gi) in navGroups" :key="gi">
        <!-- 不分组的顶层项（概览、元数据）直接就是一个入口 -->
        <template v-if="!group.title">
          <router-link
            v-for="item in group.items"
            :key="item.path"
            :to="item.path"
            class="top-item"
            :class="{ 'top-item-active': active === item.path }"
          >{{ item.label }}</router-link>
        </template>

        <!-- 分组渲染成下拉：顶部横向排不下七项，硬排会挤成一行小字，也表达不出分组。
             面板是自己写的，没用 antd Menu —— 它放进 Dropdown 的 overlay 插槽渲染不出内容
             （overlay 里只剩两个注释节点），而这里要的只是一列可点的项，自己画更可控 -->
        <div v-else class="top-group" :class="{ 'top-group-open': openGroup === group.title }"
             @mouseenter="openGroup = group.title" @mouseleave="openGroup = ''">
          <span class="top-item" :class="{ 'top-item-active': groupActive(group) }">
            {{ group.title }}
            <DownOutlined class="top-caret" />
          </span>
          <div v-show="openGroup === group.title" class="top-panel">
            <template v-for="item in group.items" :key="item.path">
              <router-link
                v-if="item.ready"
                :to="item.path"
                class="panel-item"
                :class="{ 'panel-item-active': active === item.path }"
              >
                <component :is="icons[item.icon]" class="panel-icon" />
                {{ item.label }}
              </router-link>
              <span v-else class="panel-item panel-item-disabled">
                <component :is="icons[item.icon]" class="panel-icon" />
                {{ item.label }}
                <span class="muted">（未开放）</span>
              </span>
            </template>
          </div>
        </div>
      </template>
    </nav>

    <ProjectSwitcher v-if="!standalone" class="topnav-switcher" />
  </header>
</template>

<script lang="ts" setup>
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { DownOutlined } from '@ant-design/icons-vue';
import { activeNavPath, visibleNavGroups } from '../../config/nav';
import type { NavGroup } from '../../config/nav';
import { navIcons } from '../../config/navIcons';
import ProjectSwitcher from '../ProjectSwitcher/index.vue';
import { isStandalone } from '../../config/runtime';

/**
 * 顶部菜单栏，「设置 › 基本信息」里可以切到这个模式。
 *
 * 侧边栏和它是同一份 `config/nav.ts`，两种模式下菜单内容不会走散。
 * 分组做成下拉而不是二级横排：七个入口横着摊开会挤，也没法表达分组关系。
 */
const route = useRoute();
const standalone = isStandalone();

const icons = navIcons;
const navGroups = visibleNavGroups();
const active = computed(() => activeNavPath(route.path));

/** 当前展开的分组标题，空串表示都收着。 */
const openGroup = ref('');

// 路由一变就收起面板：点完项面板还开着会挡住刚打开的页面
watch(() => route.path, () => (openGroup.value = ''));

const groupActive = (group: NavGroup) => group.items.some((i) => i.path === active.value);
</script>

<style scoped>
.topnav {
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 0 20px;
  background: #fff;
  border-bottom: 1px solid #f0f0f0;
}

.brand {
  display: flex;
  align-items: center;
  color: #1f2937;
  flex-shrink: 0;
}

.brand-logo {
  height: 24px;
}

.brand-name {
  margin-left: 10px;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
}

.topnav-items {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  flex: 1;
}

.topnav-switcher {
  margin-left: auto;
}

.top-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 48px;
  padding: 0 12px;
  color: #4b5563;
  font-size: 14px;
  white-space: nowrap;
  cursor: pointer;
  border-bottom: 2px solid transparent;
}

.top-item:hover {
  color: #1677ff;
}

.top-item-active {
  color: #1677ff;
  border-bottom-color: #1677ff;
}

.top-caret {
  font-size: 10px;
}

.top-group {
  position: relative;
}

.top-panel {
  position: absolute;
  top: 100%;
  left: 0;
  z-index: 1000;
  min-width: 168px;
  padding: 4px;
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
}

.panel-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 12px;
  border-radius: 6px;
  color: #4b5563;
  font-size: 14px;
  white-space: nowrap;
  cursor: pointer;
}

.panel-item:hover {
  background: #f5f7fa;
  color: #1677ff;
}

.panel-item-active {
  background: #e6f4ff;
  color: #1677ff;
  font-weight: 500;
}

.panel-item-disabled,
.panel-item-disabled:hover {
  color: #c0c4cc;
  background: transparent;
  cursor: not-allowed;
}

.panel-icon {
  font-size: 15px;
}
</style>
