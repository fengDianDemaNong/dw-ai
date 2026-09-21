<template>
  <div class="map-page">
    <ProductEmbed product="metadata" label="数据地图" :src="src" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import ProductEmbed from '../../components/ProductEmbed.vue';
import { lineageEmbedUrl } from '../../config/product';
import { app, currentProject, currentTenant } from '../../stores/app';

defineOptions({ name: 'MapEmbed' });

const route = useRoute();

const src = computed(() => {
  const start = route.query.start;
  const extra = typeof start === 'string' && start ? { start } : undefined;
  return lineageEmbedUrl(String(route.meta.lineagePath || '/lineage/search'), extra, {
    tenant: app.currentTenantId ?? '',
    project: app.currentProjectId ?? '',
    tenantCode: currentTenant.value?.code ?? '',
    projectCode: currentProject.value?.code ?? '',
    tenantName: currentTenant.value?.name ?? '',
    projectName: currentProject.value?.name ?? '',
    userId: app.currentUserId ?? '',
  });
});
</script>

<style scoped>
.map-page {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
</style>
