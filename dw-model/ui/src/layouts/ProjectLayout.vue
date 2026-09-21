<template>
  <div class="shell" :class="menuPos">
    <AppNav :groups="groups" home="/model" :menu-pos="menuPos" />
    <div class="main">
      <header class="bar">
        <a v-if="isRealTenantAdmin && !standalone" class="home" @click="back">{{ tenant?.name }}</a>
        <span v-else class="tenant-name">{{ tenant?.name }}</span>
        <ProjectSwitcher />
      </header>
      <div class="content">
        <router-view v-slot="{ Component }">
          <keep-alive include="MapEmbed">
            <component :is="Component" />
          </keep-alive>
        </router-view>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, watch } from 'vue';
import { useRouter } from 'vue-router';
import AppNav from '../components/AppNav.vue';
import ProjectSwitcher from '../components/ProjectSwitcher.vue';
import { buildNavGroups } from '../config/nav';
import { SYS_HOME } from '../config/paths';
import {
  app,
  can,
  currentTenant,
  ensureProjectSnapshot,
  hasAiCap,
  hasModule,
  isRealTenantAdmin,
  leaveProject,
  projectLayerRules,
} from '../stores/app';
import { appearanceOf } from '../stores/prefs';
import { isStandalone } from '../config/runtime';

const router = useRouter();
const tenant = currentTenant;
const standalone = isStandalone();
const groups = computed(() =>
  buildNavGroups(projectLayerRules.value, {
    hasModule,
    can,
    specAiDisabled: !hasAiCap('spec_design') && !hasAiCap('spec_ask'),
  })
);
const menuPos = computed(() => appearanceOf('tenant', tenant.value?.id).menuPos);

onMounted(() => {
  void ensureProjectSnapshot();
});
watch(
  () => app.currentProjectId,
  () => {
    void ensureProjectSnapshot();
  }
);

function back() {
  leaveProject();
  router.push(SYS_HOME);
}
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
}

.shell.top {
  flex-direction: column;
}

.main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.bar {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  background: var(--card);
  border-bottom: 1px solid var(--line);
}

.home {
  font-size: 13px;
  color: var(--primary);
  cursor: pointer;
}

.tenant-name {
  font-size: 13px;
  color: var(--muted);
}

.content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: auto;
}
</style>
