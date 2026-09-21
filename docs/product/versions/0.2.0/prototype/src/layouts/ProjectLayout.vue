<template>
  <div class="shell" :class="menuPos">
    <AppNav v-if="menuPos === 'left' || menuPos === 'top'" :groups="groups" home="/app" :menu-pos="menuPos" />
    <div class="main">
      <header class="bar" :class="{ slim: menuPos === 'top', menu: menuPos === 'drawer' }">
        <AppNav v-if="menuPos === 'drawer'" :groups="groups" home="/app" :menu-pos="menuPos" />
        <div class="bar-ctx">
          <a v-if="isRealTenantAdmin" class="home" @click="back">{{ tenant?.name }}</a>
          <span v-else class="tenant-name">{{ tenant?.name }}</span>
          <ProjectSwitcher />
        </div>
        <UserPanel v-if="menuPos === 'drawer'" menu-pos="top" :light="menuLight" />
      </header>
      <div class="body">
        <AppShortcut v-if="menuPos === 'drawer'" :group="shortcutGroup" :groups="groups" />
        <div class="content">
          <router-view />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AppNav from '../components/AppNav.vue';
import AppShortcut from '../components/AppShortcut.vue';
import ProjectSwitcher from '../components/ProjectSwitcher.vue';
import UserPanel from '../components/UserPanel.vue';
import { activeNavGroup, buildNavGroups } from '../config/nav';
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
import { appearanceOf } from '../stores/prefs';
import type { ProductModule } from '../types';

const tenant = currentTenant;
const route = useRoute();
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
const appearance = computed(() => appearanceOf('tenant', tenant.value?.id));
const menuPos = computed(() => appearance.value.menuPos);
const menuLight = computed(() => appearance.value.menuColor === 'light');
const shortcutGroup = computed(() => activeNavGroup(route.path, groups.value));

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

.shell.left {
  flex-direction: row;
}

.shell.top,
.shell.drawer {
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
  position: relative;
  z-index: 40;
  height: 48px;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 16px 0 8px;
  background: var(--card);
  border-bottom: 1px solid var(--line);
  flex-shrink: 0;
}

.shell.left .bar {
  padding: 0 20px;
}

.bar.slim {
  z-index: 1;
}

.bar.menu {
  background: var(--menu-bg);
  color: var(--menu-fg);
  border-bottom-color: var(--menu-line);
}

.bar.menu .tenant-name {
  color: var(--menu-muted);
}

.bar.menu .home {
  color: var(--menu-active);
}

.bar-ctx {
  display: flex;
  align-items: center;
  gap: 12px;
  flex: 1;
  min-width: 0;
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

.body {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
}

.content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}
</style>
