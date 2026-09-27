<template>
  <a-config-provider :locale="zhCN" :theme="antdTheme">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import { theme as antTheme } from 'ant-design-vue';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import { app } from './stores/app';
import {
  appearanceOf,
  applyMenuColor,
  applyTheme,
  DEFAULT_APPEARANCE,
  scopeOfRoute,
  themePrimary,
  type Appearance,
} from './stores/prefs';

dayjs.locale('zh-cn');

const route = useRoute();

/**
 * 当前生效的那套外观。三个壳各取各的（平台 / 工作台 / 项目），
 * 所以同一个浏览器标签页里换壳，主题与菜单栏颜色会跟着换。
 */
const active = computed<Appearance>(() => {
  const scope = scopeOfRoute(route.matched);
  if (scope === 'platform') return appearanceOf('platform');
  // 租户壳要先有租户上下文才谈得上一份外观（登录页、选租户页都还没有）。
  if (!app.currentTenantId) return DEFAULT_APPEARANCE;
  return appearanceOf(scope, app.currentTenantId);
});

watch(
  () => active.value.theme,
  (id) => applyTheme(id),
  { immediate: true }
);

watch(
  () => active.value.menuColor,
  (id) => applyMenuColor(id),
  { immediate: true }
);

const antdTheme = computed(() => ({
  algorithm: active.value.theme === 'dark' ? antTheme.darkAlgorithm : antTheme.defaultAlgorithm,
  token: {
    colorPrimary: themePrimary(active.value.theme),
    colorInfo: themePrimary(active.value.theme),
    borderRadius: 8,
    fontFamily:
      "ui-sans-serif, system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
}));
</script>
