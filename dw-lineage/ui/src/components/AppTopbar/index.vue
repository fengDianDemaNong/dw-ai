<template>
  <header class="topbar">
    <div class="crumbs">
      <template v-for="(part, i) in crumbs" :key="i">
        <span v-if="i > 0" class="crumb-sep">/</span>
        <router-link v-if="part.to" :to="part.to" class="crumb crumb-link">{{ part.text }}</router-link>
        <span v-else class="crumb">{{ part.text }}</span>
      </template>
    </div>
    <div class="topbar-right">
      <ProjectSwitcher v-if="!topNav && !standalone" />
      <!-- standalone 没有本地身份，UserMenu（根节点 `v-if="me"`）整个不渲染，
           连带把它里面的「返回工作台」一起带走了 —— 但工作台在 standalone 下
           **是存在的**（见 config/pages.ts 的 hasWorkbench：standalone 的口径是
           「standard 去掉用户/登录」，工作台那一级它照样有）。少了这个入口，
           人从工作台点进某个项目之后就再也回不去了，除非手改地址。
           只在项目层级出现：站在工作台上再给一个「回工作台」没有意义。 -->
      <button
        v-if="standalone && hasWorkbench() && !inWorkbench"
        type="button"
        class="back-workbench"
        @click="toWorkbench"
      >
        <HomeOutlined />
        <span>返回工作台</span>
      </button>
      <UserMenu />
    </div>
  </header>
</template>

<script lang="ts" setup>
import { computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { HomeOutlined } from '@ant-design/icons-vue';
import { activeNavItem, groupOf } from '../../config/nav';
import { WORKBENCH_HOME, hasWorkbench, isWorkbenchPath } from '../../config/pages';
import { preferences } from '../../stores/preferences';
import { uiState } from '../../stores/ui';
import ProjectSwitcher from '../ProjectSwitcher/index.vue';
import UserMenu from '../UserMenu/index.vue';
import { isStandalone } from '../../config/runtime';

const route = useRoute();
const router = useRouter();
const standalone = isStandalone();
const topNav = computed(() => preferences.navPosition === 'top');

/**
 * 已经在工作台里了就不再给「返回工作台」。
 *
 * 必须跟着路由算，不能在 setup 里求一次值 —— 这一项的出现条件里含当前层级。
 */
const inWorkbench = computed(() => isWorkbenchPath(route.path));

/**
 * 回工作台。只跳路径：两个层级的区别纯粹是地址位置（工作台「概况」看全部项目
 * 口径，项目级「概况」看当前项目口径），没有要清的状态。
 */
function toWorkbench(): void {
  router.push(WORKBENCH_HOME);
}

/**
 * 面包屑：分组 / 菜单项 / 当前对象。
 *
 * 前两段从 config/nav.ts 推导，改菜单等于改面包屑，不用两头维护。
 * 第三段（表名之类）由页面写进 uiState.crumb。
 *
 * 两段都按当前层级取（工作台 / 项目各一套菜单），所以和工作台相关的那几页
 * 面包屑里不会混进项目级的项。
 */
const crumbs = computed(() => {
  const parts: { text: string; to?: string }[] = [];
  const group = groupOf(route.path);
  if (group) parts.push({ text: group });

  const item = activeNavItem(route.path);
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

/* 右侧成组：项目切换器与用户菜单各自有出现条件，分开摆会随宽度变化互相挤压 */
.topbar-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

/* 独立模式专有的「返回工作台」。样式对齐 UserMenu 的 .trigger —— 二者不会同时出现
   （一个要有身份、一个要没身份），但都坐在顶栏右端，外形一致才不会显得是两种东西 */
.back-workbench {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 10px;
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  background: transparent;
  color: #1f2937;
  font-size: 13px;
  cursor: pointer;
}

.back-workbench:hover {
  background: #f5f6f8;
  color: #1677ff;
}
</style>
