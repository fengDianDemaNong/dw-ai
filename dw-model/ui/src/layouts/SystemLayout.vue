<template>
  <div class="shell" :class="[menuPos, { embed }]">
    <AppNav v-if="!embed" :groups="groups" :home="home" :menu-pos="menuPos" />
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
import { isEmbed } from '../config/product';
import { app } from '../stores/app';
import { appearanceOf } from '../stores/prefs';

/** 被壳嵌入时不画第二条导航栏 —— 与 ProjectLayout 同一套理由，见那边的说明。 */
const embed = isEmbed();

const route = useRoute();
const adminShell = computed(() => route.matched.some((r) => r.meta.shell === 'admin'));
const groups = computed(() => (adminShell.value ? buildAdminNav() : buildSysNav()));
const home = computed(() => (adminShell.value ? ADMIN_HOME : SYS_HOME));
const menuPos = computed(() =>
  adminShell.value ? appearanceOf('platform').menuPos : appearanceOf('tenant', app.currentTenantId).menuPos
);
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

/* 被壳嵌入：只剩 `.main` 一个子项，纵向排。 */
.shell.embed {
  flex-direction: column;
}

.main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: auto;
}
</style>
