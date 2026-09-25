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
import { isOrgUi } from '../config/product';
import { currentProject, enterProject, navReady, servicesReady, tenantProjects } from '../stores/app';

const router = useRouter();
const options = computed(() =>
  tenantProjects.value
    .filter((p) => (p.status ?? 'active') !== 'disabled')
    .map((p) => ({ value: p.id, label: `${p.name}（${p.code}）` }))
);

async function onPick(id: unknown) {
  if (typeof id !== 'string') return;
  await enterProject(id);
  if (isOrgUi()) {
    // 同 projects.vue 的 go()：进的是**项目壳**，不是某个产品自己的站点。
    // 菜单与服务目录都要等 —— 项目壳的默认落地页要靠菜单挑「第一条能嵌的」。
    await Promise.all([servicesReady(), navReady()]);
    const code = tenantProjects.value.find((p) => p.id === id)?.code;
    if (code) router.push(`/org/project/${encodeURIComponent(code)}`);
    return;
  }
  router.push('/model');
}
</script>
