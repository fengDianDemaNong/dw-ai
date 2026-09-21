<template>
  <div class="shell" :class="menuPos">
    <AppNav :groups="groups" :home="home" :menu-pos="menuPos" />
    <div class="main">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import AppNav from '../components/AppNav.vue';
import { ADMIN_HOME, SYS_HOME } from '../config/paths';
import { buildAdminNav, buildSysNav } from '../config/sysNav';
import { app, isRealTenantAdmin } from '../stores/app';
import { appearanceOf } from '../stores/prefs';

const route = useRoute();
const adminShell = computed(() => route.matched.some((r) => r.meta.shell === 'admin'));
const groups = computed(() => (adminShell.value ? buildAdminNav() : buildSysNav(isRealTenantAdmin.value)));
const home = computed(() => (adminShell.value ? ADMIN_HOME : SYS_HOME));
const menuPos = computed(() => {
  const pos = adminShell.value
    ? appearanceOf('platform').menuPos
    : appearanceOf('tenant', app.currentTenantId).menuPos;
  return pos === 'drawer' ? 'left' : pos;
});
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--page);
}

.shell.top {
  flex-direction: column;
}

.main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}
</style>
