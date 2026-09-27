<template>
  <div class="shell" :class="`pos-${menuPos}`">
    <AppNav
      v-if="menuPos === 'left' || menuPos === 'top'"
      :items="items"
      :home="home"
      :menu-pos="menuPos"
    />
    <div class="frame">
      <!--
        项目壳的顶栏。

        <p>原型里三种菜单模式都有这一条：`left`/`top` 下放租户名与项目切换器，`drawer`
        下还要放汉堡按钮与用户面板 —— 抽屉正是挂在它下面弹出的（`AppNav` 的 `.drawer`
        是 `absolute` + `top:100%`，锚点就是这里）。

        <p>**项目切换器靠右**，挨着用户面板。它是「换个项目看」这种上下文切换，
        与左边的「我现在在哪个租户」（`.bar-ctx`，`flex: 1` 吃掉中间的空档）不是一类；
        贴在一起时用户会把两个下拉当成一组。

        <p>只有项目壳有：原型的工作台壳（它自己的 `SystemLayout.vue`）没有这条栏，
        租户名与切换器都只出现在项目里 —— 工作台本身就是「选项目」的地方。
      -->
      <header v-if="projectShell" class="bar" :class="{ menu: menuPos === 'drawer' }">
        <AppNav
          v-if="menuPos === 'drawer'"
          :items="items"
          :home="home"
          :menu-pos="menuPos"
        />
        <div class="bar-ctx">
          <a v-if="canBackHome" class="home" @click="back">{{ tenantName }}</a>
          <span v-else class="tenant-name">{{ tenantName }}</span>
        </div>
        <ProjectSwitcher />
        <UserPanel v-if="menuPos === 'drawer' && !standalone" menu-pos="top" />
      </header>
      <div class="body">
        <AppShortcut v-if="projectShell && menuPos === 'drawer'" :items="items" />
        <div class="main">
          <router-view />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import AppNav from '../components/AppNav.vue';
import AppShortcut from '../components/AppShortcut.vue';
import ProjectSwitcher from '../components/ProjectSwitcher.vue';
import UserPanel from '../components/UserPanel.vue';
import { ADMIN_HOME, SYS_HOME } from '../config/paths';
import { buildAdminNav, toNavItems } from '../config/sysNav';
import { listenMountTrees, mountOrigins } from '../config/navMount';
import { isStandalone } from '../config/runtime';
import { app, canBackHome, currentTenant, leaveProject, navTree } from '../stores/app';
import { appearanceOf, scopeOfRoute } from '../stores/prefs';

const route = useRoute();
const router = useRouter();
const standalone = isStandalone();
const adminShell = computed(() => route.matched.some((r) => r.meta.shell === 'admin'));
const projectShell = computed(() => route.matched.some((r) => r.meta.shell === 'project'));
/** 项目码从地址里取（`/org/project/{code}/...`）—— 侧栏要按它拼产品页面的完整路由。 */
const projectCode = computed(() => String(route.params.code ?? ''));
const tenantName = computed(() => currentTenant.value?.name ?? '');

// 租户侧有<b>两个</b>壳，区别只在取树里的哪一支：
//   工作台壳（`/org/workbench/*`）取 `scope === 'workbench'`（进项目之前那一级）；
//   项目壳（`/org/project/{code}/*`）取 `scope === 'project'`（进项目之后那一级）。
// 两者共用这个布局 —— 另起一个布局会让 AppNav / UserPanel / 折叠逻辑各出现第二份。
//
// **V23 起这里不再「拼装」菜单**：org 自有菜单与产品菜单现在同在 `nav_nodes` 一张表里，
// 服务端返回的就是这个壳该看到的树（许可、权限词、`admin_only` 都判完了）。
// 前端只剩两件事：按壳取一支、把路径拼成 org 的完整路由（见 toNavItems）。
const items = computed(() => {
  if (adminShell.value) return buildAdminNav();
  if (projectShell.value) return toNavItems(navTree.value, { scope: 'project', projectCode: projectCode.value });
  // 平台后台的侧栏是平台管理，不该混进业务入口 —— 所以上面那个分支直接 return。
  return toNavItems(navTree.value, { scope: 'workbench', projectCode: '' });
});
// 品牌图标的落点：项目壳里回项目首页（而不是被弹回工作台）—— 它是「回到起点」，
// 不是「离开这里」，离开由顶栏的租户名与用户面板里的「返回工作台」负责。
const home = computed(() => {
  if (adminShell.value) return ADMIN_HOME;
  if (projectShell.value) return `/org/project/${encodeURIComponent(projectCode.value)}`;
  return SYS_HOME;
});
/** 这个布局同时服务三个壳（平台后台 / 工作台 / 项目），外观也各取各的。 */
const shell = computed(() => scopeOfRoute(route.matched));
const menuPos = computed(() => {
  const pos = appearanceOf(shell.value, app.currentTenantId).menuPos;
  // `drawer` 是**项目壳专有**的：工作台壳与平台壳没有那条顶栏，而抽屉是 `absolute` +
  // `top:100%` 挂在顶栏下面的 —— 没有锚点就会落到视口外面（全仓没有别的定位祖先）。
  // 服务端与 `setMenuPos` 都已经把非项目壳的 drawer 归一成 left，这里是渲染前最后一道。
  return !projectShell.value && pos === 'drawer' ? 'left' : pos;
});

/** 顶栏上的租户名：点它回工作台（仅「回得去」的人可点，否则只是一行字）。 */
function back() {
  leaveProject();
  router.push(SYS_HOME);
}

/**
 * 收被嵌产品报上来的运行期菜单树（见 `config/navMount.ts`）。
 *
 * <p>挂在这里而不是各自的产品页面上：两个租户壳共用这一个布局，挂一次两个壳都收得到，
 * 而菜单树是**壳级**的状态 —— 挂载节点在哪个壳里、由哪个产品的哪次上报填充，
 * 与用户此刻停在哪一页无关。
 *
 * <p>允许来源现取（传函数不传数组）：服务注册里改了产品地址，下一次消息就能生效。
 */
onMounted(() => listenMountTrees(() => mountOrigins(navTree.value)));
</script>

<style scoped>
.shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--page);
}

/*
 * 位置类带 `pos-` 前缀：`AppNav` 的抽屉元素自己就带 `class="drawer"`，
 * 壳上再挂一个同名的类会让「`.drawer` 是谁」在 CSS 和选择器里都得靠上下文猜。
 */
.shell.pos-top,
.shell.pos-drawer {
  flex-direction: column;
}

/* 顶栏 +（快捷栏 + 内容区）。只做 flex 传递，自己不产生任何间距。 */
.frame {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

/*
 * 顶栏。`position: relative` 是 `AppNav` 抽屉的锚点 —— 少了它，`.drawer` 的
 * `top: 100%` 会相对**视口**算，整个抽屉落到屏幕外面（全仓没有别的定位祖先）。
 */
.bar {
  position: relative;
  z-index: 40;
  height: 48px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 0 16px 0 8px;
  background: var(--card);
  border-bottom: 1px solid var(--line);
}

/* drawer 模式下顶栏就是菜单栏本身，跟着菜单栏颜色走。 */
.bar.menu {
  background: var(--menu-bg);
  color: var(--menu-fg);
  border-bottom-color: var(--menu-line);
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

.bar.menu .home {
  color: var(--menu-active);
}

.tenant-name {
  font-size: 13px;
  color: var(--muted);
}

.bar.menu .tenant-name {
  color: var(--menu-muted);
}

.body {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
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
