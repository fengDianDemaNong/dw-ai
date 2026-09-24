<template>
  <aside class="sidebar" :class="{ 'sidebar-collapsed': uiState.collapsed }">
    <!-- logo 回本层级的首页：在项目里回项目概况，在工作台里回工作台概况。
         一律回 /lineage 会把工作台上的人莫名甩进某个项目 -->
    <router-link :to="home" class="brand" :title="uiState.collapsed ? 'dw-lineage' : undefined">
      <img src="/logo.png" class="brand-logo" alt="logo" />
      <span v-show="!uiState.collapsed" class="brand-name">dw-lineage</span>
    </router-link>

    <nav class="groups">
      <div v-for="(group, gi) in groups" :key="gi" class="group">
        <!-- 标题为空 = 不分组的顶层项（概览、元数据），不占一行标题。
             收起态下分组名换成一条分隔线：56px 放不下「数据地图」四个字，
             硬塞会被截断成「数据…」，还不如不显示 -->
        <div v-if="group.title && !uiState.collapsed" class="group-title">{{ group.title }}</div>
        <div v-else-if="group.title" class="group-divider" />

        <template v-for="item in group.items" :key="item.path">
          <router-link
            v-if="!item.disabledReason"
            :to="item.path"
            class="item"
            :class="{ 'item-active': active === item.path }"
            :title="uiState.collapsed ? item.label : undefined"
          >
            <component :is="icons[item.icon]" class="item-icon" />
            <span class="item-label">{{ item.label }}</span>
          </router-link>
          <!-- 点不了：渲染成不可点，否则会被路由兜底静默跳回首页，像个 bug。
               原因有两种（页面没做 / 当前角色没权限），所以文案跟着 item 走，
               不写死在这里 —— 「尚未开放」和「去要个角色」要找的人不一样 -->
          <Tooltip v-else :title="item.disabledReason" placement="right">
            <span class="item item-disabled">
              <component :is="icons[item.icon]" class="item-icon" />
              <span class="item-label">{{ item.label }}</span>
            </span>
          </Tooltip>
        </template>
      </div>
    </nav>

    <button type="button" class="collapse-btn" @click="toggleSidebar">
      <span class="collapse-icon">{{ uiState.collapsed ? '›' : '‹' }}</span>
      <span v-show="!uiState.collapsed" class="collapse-text">收起</span>
    </button>
  </aside>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import { Tooltip } from 'ant-design-vue';
import { activeNavPath, visibleNavGroups } from '../../config/nav';
import { navIcons } from '../../config/navIcons';
import { LINEAGE_HOME, WORKBENCH_HOME, isWorkbenchPath } from '../../config/pages';
import { toggleSidebar, uiState } from '../../stores/ui';

const icons = navIcons;

const route = useRoute();

/** 菜单按当前层级选一套（工作台 / 项目），所以要跟着路由变，不能在 setup 里求一次值。 */
const groups = computed(() => visibleNavGroups(route.path));

/** logo 的落点也是本层级首页（见模板注释）。 */
const home = computed(() => (isWorkbenchPath(route.path) ? WORKBENCH_HOME : LINEAGE_HOME));

/** 最长匹配，否则 '/' 会把每一项都点亮（见 config/nav.ts）。 */
const active = computed(() => activeNavPath(route.path));
</script>

<style scoped>
.sidebar {
  width: 200px;
  flex-shrink: 0;
  height: 100vh;
  background: #fff;
  border-right: 1px solid #f0f0f0;
  display: flex;
  flex-direction: column;
  transition: width 0.15s ease;
}

.sidebar-collapsed {
  width: 56px;
}

.brand {
  display: flex;
  align-items: center;
  height: 48px;
  padding: 0 16px;
  flex-shrink: 0;
  border-bottom: 1px solid #f0f0f0;
  color: #1f2937;
}

.sidebar-collapsed .brand {
  padding: 0;
  justify-content: center;
}

.brand-logo {
  height: 24px;
  flex-shrink: 0;
}

.brand-name {
  margin-left: 10px;
  font-size: 15px;
  font-weight: 500;
  white-space: nowrap;
}

.groups {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 0;
}

.group + .group {
  margin-top: 4px;
}

.group-title {
  padding: 8px 16px 4px;
  font-size: 12px;
  color: #9ca3af;
  white-space: nowrap;
}

.group-divider {
  height: 1px;
  margin: 8px 12px;
  background: #f0f0f0;
}

.item {
  display: flex;
  align-items: center;
  height: 36px;
  padding: 0 16px;
  color: #4b5563;
  font-size: 14px;
  white-space: nowrap;
  cursor: pointer;
}

.item:hover {
  background: #f5f7fa;
  color: #1677ff;
}

.item-active {
  background: #e6f4ff;
  color: #1677ff;
  font-weight: 500;
}

.item-disabled,
.item-disabled:hover {
  color: #c0c4cc;
  cursor: not-allowed;
  background: transparent;
}

/* 收起态只剩 56px，文字放不下，只留图标 + title 提示 */
.item-icon {
  font-size: 15px;
  flex-shrink: 0;
  margin-right: 10px;
}

.sidebar-collapsed .item {
  padding: 0;
  justify-content: center;
}

.sidebar-collapsed .item-label {
  display: none;
}

.sidebar-collapsed .item-icon {
  margin-right: 0;
}

.collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  height: 36px;
  flex-shrink: 0;
  border: none;
  border-top: 1px solid #f0f0f0;
  background: transparent;
  color: #9ca3af;
  font-size: 13px;
  cursor: pointer;
}

.collapse-btn:hover {
  color: #1677ff;
  background: #f5f7fa;
}

.collapse-icon {
  font-size: 16px;
  line-height: 1;
}
</style>
