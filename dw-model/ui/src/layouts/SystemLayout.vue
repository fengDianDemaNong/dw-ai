<template>
  <div class="shell" :class="[menuPos, { embed }]">
    <AppNav v-if="!embed" :groups="groups" :home="home" :menu-pos="menuPos" />
    <div class="main">
      <router-view />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, watch } from 'vue';
import { useRoute } from 'vue-router';
import AppNav from '../components/AppNav.vue';
import { ADMIN_HOME, SYS_HOME } from '../config/paths';
import { buildAdminNav, buildSysNav } from '../config/sysNav';
import { PRODUCT, toEmbedNodes } from '../config/navData';
import { postNavTree } from '../config/embed';
import { isEmbed } from '../config/product';
import { app, isRealTenantAdmin } from '../stores/app';
import { appearanceOf } from '../stores/prefs';

/** 被壳嵌入时不画第二条导航栏 —— 与 ProjectLayout 同一套理由，见那边的说明。 */
const embed = isEmbed();

const route = useRoute();
const adminShell = computed(() => route.matched.some((r) => r.meta.shell === 'admin'));
// 账号两页按身份收口：multi 下授权码只租户管理员能签发，普通成员看到只会是 403。
// 判据的两个维度（身份 + 模式）见 `sysNav.ts` 的 `buildSysNav` doc。
const groups = computed(() =>
  adminShell.value ? buildAdminNav() : buildSysNav(isRealTenantAdmin.value)
);
const home = computed(() => (adminShell.value ? ADMIN_HOME : SYS_HOME));
const menuPos = computed(() =>
  adminShell.value ? appearanceOf('platform').menuPos : appearanceOf('tenant', app.currentTenantId).menuPos
);

/**
 * 把工作台壳的菜单报给宿主 —— 与 `ProjectLayout` 那处同一套理由，见那边的说明。
 *
 * <p>`project` 传空串：工作台管的是**租户级**的项目清单与外观 / 大模型 / 提示词，
 * 与「当前是哪个项目」无关。壳据此知道这份菜单不校验项目。
 *
 * <p>平台管理壳（`/org/platform/*`）不报：那是组织平台自己的页面，压根不会被嵌，
 * 而它的路径也不在产品的候选清单里，报过去只会被壳丢弃。
 */
watch(
  groups,
  (list) => {
    if (!embed || adminShell.value) return;
    postNavTree('workbench', '', toEmbedNodes(PRODUCT, 'workbench', list));
  },
  { immediate: true }
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
