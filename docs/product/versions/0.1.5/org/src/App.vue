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
  applyTheme,
  DEFAULT_APPEARANCE,
  themePrimary,
  type Appearance,
} from './stores/prefs';

dayjs.locale('zh-cn');

const route = useRoute();

const active = computed<Appearance>(() => {
  if (route.matched.some((r) => r.meta.shell === 'admin')) return appearanceOf('platform');
  if (app.currentTenantId) return appearanceOf('tenant', app.currentTenantId);
  return DEFAULT_APPEARANCE;
});

watch(
  () => active.value.theme,
  (id) => applyTheme(id),
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
