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
import { buildAdminNav, buildProjectProductNav, buildProjectSysNav, buildSysNav, buildWorkbenchProductNav } from '../config/sysNav';
import { app, isRealTenantAdmin, projectMenus, projectNavGroups, workbenchMenus, workbenchNavGroups } from '../stores/app';
import { appearanceOf } from '../stores/prefs';

const route = useRoute();
const adminShell = computed(() => route.matched.some((r) => r.meta.shell === 'admin'));
const projectShell = computed(() => route.matched.some((r) => r.meta.shell === 'project'));
/** 项目码从地址里取（`/org/project/{code}/...`）—— 侧栏要按它拼产品页面的完整路由。 */
const projectCode = computed(() => String(route.params.code ?? ''));

// 产品菜单只在租户壳里出现：平台后台的侧栏是平台管理，不该混进业务入口。
// 菜单数据跟着租户走（stores/app.ts 的 loadNav），这里只负责拼装。
//
// 租户侧有<b>两个</b>壳，区别只在取哪一批产品菜单：
//   工作台壳（`/org/workbench/*`）取 `scope === 'workbench'`（进项目之前那一级）；
//   项目壳（`/org/project/{code}/*`）取 `scope === 'project'`（进项目之后那一级）。
// 两者共用这个布局 —— 另起一个布局会让 AppNav / UserPanel / 折叠逻辑各出现第二份。
const groups = computed(() => {
  if (adminShell.value) return buildAdminNav();
  if (projectShell.value) {
    return [
      ...buildProjectSysNav(),
      ...buildProjectProductNav(projectMenus.value, projectCode.value, projectNavGroups.value),
      // 项目壳里保留「设置」等系统项吗？不保留 —— 那些是组织级功能（用户/角色/知识库），
      // 在项目这一层没有意义，而「返回工作台」已经把出口给了。
    ];
  }
  return [
    ...buildWorkbenchProductNav(workbenchMenus.value, workbenchNavGroups.value),
    ...buildSysNav(isRealTenantAdmin.value),
  ];
});
// 品牌图标的落点：项目壳里回项目首页（而不是被弹回工作台）—— 它是「回到起点」，
// 不是「离开这里」，离开由上一条「返回工作台」负责。
const home = computed(() => {
  if (adminShell.value) return ADMIN_HOME;
  if (projectShell.value) return `/org/project/${encodeURIComponent(projectCode.value)}`;
  return SYS_HOME;
});
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
  /*
   * 纵向 flex：页面自己要用 `flex: 1` 撑满内容区时才有得撑（嵌入页就是这么撑的 ——
   * `.embed-page` → `ProductEmbed` 的 `.embed` → `iframe.pane` 三层全是 `flex: 1`）。
   * 少了这两行，`flex: 1` 在 block 容器里静默失效：高度一路退化成 auto，最后 iframe
   * 拿到浏览器默认的 **150px**，嵌入页只剩顶上一条，下面全是空的。
   * dw-model 的 ProjectLayout 与 dw-lineage 的 AppLayout 本来就是这套写法，这里是补齐。
   */
  display: flex;
  flex-direction: column;
}
</style>
