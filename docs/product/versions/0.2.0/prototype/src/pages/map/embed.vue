<template>
  <div class="page map-page">
    <ProductEmbed label="数据地图" :hint="hint" :src="src" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import ProductEmbed from '../../components/ProductEmbed.vue';
import { LINEAGE_ORIGIN, lineageEmbedUrl } from '../../config/suite';
import { currentProject, currentTenant } from '../../stores/app';

const route = useRoute();
const src = computed(() => {
  const start = route.query.start;
  const extra = typeof start === 'string' && start ? { start } : undefined;
  return lineageEmbedUrl(String(route.meta.lineagePath || '/search'), extra);
});
const hint = computed(() => {
  const tenant = currentTenant.value?.code || '—';
  const project = currentProject.value?.code || '—';
  return `${LINEAGE_ORIGIN} · 租户 ${tenant} / 项目 ${project} · 已去掉对方菜单壳`;
});
</script>

<style scoped>
.map-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 48px);
  min-height: 0;
  overflow: hidden;
  padding: 12px 16px;
}
</style>
