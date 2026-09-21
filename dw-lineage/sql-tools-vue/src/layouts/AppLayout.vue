<template>
  <div class="app-layout" :class="layoutClass">
    <AppTopnav v-if="!embed && topNav" />
    <AppSidebar v-else-if="!embed" />

    <div class="app-main">
      <AppTopbar v-if="!embed" />
      <div ref="contentRef" class="app-content">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import AppSidebar from '../components/AppSidebar/index.vue';
import AppTopnav from '../components/AppTopnav/index.vue';
import AppTopbar from '../components/AppTopbar/index.vue';
import { uiState } from '../stores/ui';
import { preferences } from '../stores/preferences';
import { isEmbed } from '../config/runtime';

/**
 * 应用骨架。
 *
 * 菜单位置两种，在「设置 › 基本信息」里切：
 * - side：左侧分组导航 + 顶部细栏。分组层级看得见，画布拿到全部高度
 * - top：顶部菜单栏（分组收成下拉）+ 下面一条细栏。矮屏幕下不占左边那一竖条
 *
 * 两种模式的菜单都来自 `config/nav.ts`，不会走散。所有页面都挂在这里，
 * 页面自己不再各带一份导航 —— 之前管理页走 AppNav、SQL 解析页走带解析控件的
 * Header，两套导航要靠 navItems.filter 硬凑着对齐，租户切换器也被迫挂两份。
 */
const route = useRoute();

const embed = isEmbed();
const topNav = computed(() => preferences.navPosition === 'top');
const layoutClass = computed(() => {
  if (embed) return 'layout-embed';
  return topNav.value ? 'layout-top' : 'layout-side';
});
const contentRef = ref<HTMLElement>();

/**
 * 量内容区实际尺寸写进 store。
 *
 * G6 画布必须显式给宽高。页面以前拿的是 document.documentElement.clientWidth ——
 * 那时导航固定在顶部、内容区就是整屏宽；现在菜单位置可切，按整屏算会溢出。
 */
function measure() {
  const el = contentRef.value;
  if (!el) return;
  uiState.contentWidth = el.clientWidth;
  uiState.contentHeight = el.clientHeight;
}

let observer: ResizeObserver | undefined;

onMounted(() => {
  measure();
  observer = new ResizeObserver(measure);
  if (contentRef.value) observer.observe(contentRef.value);
});

onUnmounted(() => observer?.disconnect());

watch(
  () => route.path,
  () => {
    // 详情页填的那一段面包屑不能跨页面留着
    uiState.crumb = '';
  },
  { immediate: true }
);
</script>

<style scoped>
.app-layout {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: #f5f6f8;
}

/* 顶部菜单栏模式：菜单栏与主区是上下关系，不是左右 */
.layout-top {
  flex-direction: column;
}

/* 被仓建设 iframe 嵌入：只留内容区，仍走纵向 flex，否则 SQL 解析左栏高度为 0 */
.layout-embed {
  display: flex;
  flex-direction: column;
  height: 100vh;
}

.app-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.app-content {
  flex: 1;
  min-height: 0;
  overflow: hidden;
}
</style>
