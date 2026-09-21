<template>
  <div class="shell" :class="menuPos">
    <AppNav :groups="groups" home="/app" :menu-pos="menuPos" />
    <div class="main">
      <header class="bar">
        <a v-if="isRealTenantAdmin" class="home" @click="back">{{ tenant?.name }}</a>
        <span v-else class="tenant-name">{{ tenant?.name }}</span>
        <ProjectSwitcher />
      </header>
      <div class="content">
        <router-view />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import AppNav from '../components/AppNav.vue';
import ProjectSwitcher from '../components/ProjectSwitcher.vue';
import { buildNavGroups } from '../config/nav';
import { ORG_ORIGIN, openOrigin } from '../config/suite';
import {
  currentTenant,
  hasAiCap,
  isProjectAdmin,
  isRealTenantAdmin,
  leaveProject,
  licensedProducts,
  openedProducts,
  platformServices,
  productRoleOf,
  projectLayerRules,
  schedulerReady,
  tenantScheduler,
  visibleProducts,
  app,
} from '../stores/app';
import type { ProductModule } from '../types';
import { appearanceOf } from '../stores/prefs';

const tenant = currentTenant;
const groups = computed(() => {
  const licensed = licensedProducts();
  const opened = openedProducts();
  const visible = visibleProducts();
  const uid = app.currentUserId;
  const myRoles: Partial<Record<ProductModule, string>> = {};
  if (uid) {
    for (const code of opened) {
      const r = productRoleOf(uid, code);
      if (r) myRoles[code] = r;
    }
  }
  return buildNavGroups(projectLayerRules.value, {
    specAiDisabled: !hasAiCap('spec_design') && !hasAiCap('spec_ask'),
    licensed,
    opened,
    visible,
    services: platformServices(),
    schedulerConfigured: Boolean(tenantScheduler()?.enabled && tenantScheduler()?.baseUrl),
    schedulerReady: schedulerReady(),
    projectAdmin: isProjectAdmin(),
    myRoles,
  });
});
const menuPos = computed(() => appearanceOf('tenant', tenant.value?.id).menuPos);

function back() {
  leaveProject();
  openOrigin(ORG_ORIGIN, '/projects');
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
  overflow: auto;
}
</style>
