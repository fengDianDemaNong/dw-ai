<template>
  <a-select
    v-if="options.length > 1"
    :value="currentProject?.id"
    :options="options"
    :placeholder="currentProject ? currentProject.name : '切换项目'"
    style="min-width: 180px"
    @change="onPick"
  />
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { currentProject, enterProject, tenantProjects } from '../stores/app';

const router = useRouter();
const options = computed(() =>
  tenantProjects.value
    .filter((p) => (p.status ?? 'active') !== 'disabled')
    .map((p) => ({ value: p.id, label: `${p.name}（${p.code}）` }))
);

async function onPick(id: unknown) {
  if (typeof id !== 'string') return;
  await enterProject(id);
  router.push('/w');
}
</script>
