<template>
  <div class="shell" :class="menuPos">
    <AppNav :groups="groups" home="/model" :menu-pos="menuPos" />
    <div class="main">
      <header class="bar">
        <!-- 点这里回工作台：multi 下那一级在组织平台（跨进程跳转），其余模式在本进程的 `SYS_HOME`。
             判据见 `config/pages.ts` 的 `canBackToWorkbench`。
             文案写成「← 返回工作台」而不是光秃秃的租户名：租户名（如「本环境」）既看不出
             可点、也看不出点了去哪，而这是进入项目后唯一的回退入口。租户名挪进 title。 -->
        <a
          v-if="canBackHome"
          class="home"
          :title="tenant?.name ? `当前组织：${tenant.name}` : '返回工作台'"
          @click="back"
        >← 返回工作台</a>
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
import { isMultiTenant } from '../config/runtime';
import { canBackToWorkbench } from '../config/pages';
import { openOrgWorkbench } from '../config/product';

const router = useRouter();
const tenant = currentTenant;
/** 见 `canBackToWorkbench`：multi 下所有人都给（上一级在组织平台），其余模式只给租户管理员。 */
const canBackHome = computed(() => canBackToWorkbench(isRealTenantAdmin.value));
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
  // multi 的工作台在组织平台（本进程没有这一级），所以是整页跳转而非 router.push。
  if (isMultiTenant()) {
    openOrgWorkbench();
    return;
  }
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
