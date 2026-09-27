<template>
  <div class="shell" :class="[menuPos, { embed }]">
    <AppNav v-if="!embed" :groups="groups" home="/model" :menu-pos="menuPos" />
    <div class="main">
      <header v-if="!embed" class="bar">
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
        <router-view />
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
import { PRODUCT, toEmbedNodes } from '../config/navData';
import { postNavTree } from '../config/embed';
import { SYS_HOME } from '../config/paths';
import {
  app,
  can,
  currentProject,
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
import { isEmbed } from '../config/product';

/**
 * 被组织平台的壳嵌着时，**这里不再画第二条导航栏**。
 *
 * <p>嵌进来的那一层壳已经有一套完整导航（还带着「返回工作台」与项目切换器），
 * 子应用再画一份就是两条并排的侧栏、两个 logo、两个「收起」—— 用户看到的是
 * 同一套菜单被画了两遍，还会以为是重复的入口。
 *
 * <p>判据取自 `config/product.ts` 的 {@link isEmbed}（URL 上的 `?embed=1`，
 * 由壳拼地址时带上、`consumeBootHash` 记进会话）。dw-lineage 的 AppLayout 一直是
 * 这么做的（`layout-embed` 只留内容区），这里补齐 —— 同一件事在两个被嵌产品上
 * 必须是同一个行为，否则「接进来的菜单」长什么样要看接的是谁。
 */
const embed = isEmbed();

const router = useRouter();
const tenant = currentTenant;
/** 见 `canBackToWorkbench`：multi 与 standalone 下所有人都给，standard 只给租户管理员。 */
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

/**
 * 把自己这份菜单报给壳。
 *
 * <p>`groups` 就是本组件画侧栏用的那一份，**嵌入态下照样算**（上面的 `embed` 只决定
 * 要不要画 `<AppNav>`）—— 所以这里没有第二套规则，报的就是用户在独立打开本产品时
 * 会看到的东西。壳那边拿它补「挂载节点」下缺的运行期项（典型是「分层规范」里登记的
 * 那些层，静态清单 `menu.json` 表达不了）。
 *
 * <p>`immediate`：首屏就报一次，不等分层数据加载完 —— 那时报的是默认项，加载完
 * 再报一次覆盖。壳收到两次没有代价（按 id 合并，不是追加）。
 *
 * <p>`project` 带上项目 code：壳拿它跟当前项目比对，不一致就丢弃。切项目时 iframe
 * 不重建，消息可能在切换的空档里到达，不比对会把 A 项目的分层画到 B 项目上。
 */
watch(
  groups,
  (list) => {
    if (!embed) return;
    postNavTree('project', currentProject.value?.code ?? '', toEmbedNodes(PRODUCT, 'project', list));
  },
  { immediate: true }
);

function back() {
  leaveProject();
  // 工作台在本进程，三种模式都有（见 `config/pages.ts` 的 `ownsProjects`）—— 所以是
  // 进程内跳转。此前 multi 下这里整页跳到组织平台的工作台，与 `UserPanel.vue` 那处
  // 是同一个判据、同一个落点。
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

/* 被壳嵌入：只剩 `.main` 一个子项，纵向排（见 script 里 `embed` 的说明）。 */
.shell.embed {
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
