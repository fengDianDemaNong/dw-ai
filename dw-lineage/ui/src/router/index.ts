import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import AppLayout from '../layouts/AppLayout.vue';
import Overview from '../pages/overview.vue';
import {
  LINEAGE_HOME,
  LINEAGE_PAGES,
  LOGIN_PATH,
  PROJECT_SETTINGS_PAGES,
  WORKBENCH_HOME,
  WORKBENCH_PAGES,
  defaultHome,
  hasLocalAccounts,
  isWorkbenchPath,
  ownsProjects,
  requiresLocalLogin,
} from '../config/pages';
import { authState, hasSession } from '../stores/auth';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    /** 只有管理员能进。当前仅「账号管理」用。判权仍在后端，这里只是别让人点进一个必然 403 的页。 */
    adminOnly?: boolean;
  }
}

/**
 * 「基本信息」页的落点：工作台那一页。
 *
 * <p>三种模式都落在工作台 —— 这一页上的「当前数据范围」就是项目切换器，
 * 站在项目里再摆一个和右上角那个重复，所以它只在工作台那一级有意义。
 * 此前 multi 没有工作台，那时它被送去项目级的「数据地图设置」（那些模式下唯一
 * 合用的设置页）；工作台回到本进程之后 multi 也有这一页了，落点统一。
 *
 * <p>老地址 `/settings/preferences` 与项目层级的 `/lineage/settings/preferences`
 * 都走它，写法保持一处。
 */
const preferencesHome = (): string => WORKBENCH_PAGES.preferences;

/**
 * 路由表。本进程页面统一挂 /lineage。
 *
 * 路径不要带点号：后端 WebStaticConfig 的 SPA fallback 会把「最后一段含 .」的路径
 * 判定为静态资源并直接返回 404，不会回落到 index.html。
 */
const routes: RouteRecordRaw[] = [
  {
    // 登录页是 LINEAGE_HOME 的<b>兄弟</b>，不是子路由 —— 它不能带 AppLayout 的
    // 侧边栏与顶栏：那时还没有身份，菜单点开全是 401，看了只会让人以为系统坏了。
    path: LOGIN_PATH,
    name: 'login',
    component: () => import('../pages/login.vue'),
    meta: { title: '登录' },
  },
  {
    // 租户工作台：进项目**之前**的层级（概况 / 项目 / 设置）。
    //
    // 与下面的 LINEAGE_HOME 是两条**并列**记录，不是父子嵌套 —— 虽然路径上
    // /lineage/workbench ⊂ /lineage 前缀。Vue Router 按静态段的优先级打分，
    // /lineage/workbench/* 会命中这条，/lineage 的 children 里没有 workbench 段，
    // 不会互相抢。两边共用 AppLayout（菜单位置、G6 尺寸测量都在那里）。
    //
    // 三种模式都能到（原先守卫里有一条把 multi 挡回项目概况，2026-09-26 已删）。
    path: WORKBENCH_HOME,
    component: AppLayout,
    children: [
      {
        path: '',
        name: 'workbench-overview',
        component: Overview,
        meta: { title: '概况' },
      },
      {
        path: 'projects',
        name: 'workbench-projects',
        component: () => import('../pages/settings/projects.vue'),
        meta: { title: '项目' },
      },
      {
        path: 'settings/metadata',
        name: 'workbench-metadata',
        component: () => import('../pages/settings/metadata-sources.vue'),
        meta: { title: '元数据服务' },
      },
      {
        path: 'settings/preferences',
        name: 'workbench-preferences',
        component: () => import('../pages/settings/preferences.vue'),
        meta: { title: '基本信息' },
      },
      {
        path: 'settings/users',
        name: 'workbench-users',
        component: () => import('../pages/settings/users.vue'),
        meta: { title: '账号管理', adminOnly: true },
      },
    ],
  },
  {
    path: LINEAGE_HOME,
    component: AppLayout,
    children: [
      {
        path: '',
        name: 'overview',
        component: Overview,
        meta: { title: '概况' },
      },
      {
        path: 'search',
        name: 'search',
        component: () => import('../pages/search.vue'),
        meta: { title: '全文检索' },
      },
      {
        path: 'tables',
        name: 'lineage-tables',
        component: () => import('../pages/map/lineage-list.vue'),
        meta: { title: '血缘' },
      },
      {
        path: 'tables/:id',
        name: 'lineage-table-detail',
        component: () => import('../pages/map/lineage-detail.vue'),
        meta: { title: '表详情' },
      },
      {
        path: 'catalogs',
        name: 'catalogs',
        component: () => import('../pages/map/catalogs.vue'),
        meta: { title: '数据目录' },
      },
      {
        path: 'temp-rules',
        name: 'temp-rules',
        component: () => import('../pages/map/temp-rules.vue'),
        meta: { title: '临时表规则' },
      },
      {
        path: 'analyze',
        name: 'analyze',
        component: () => import('../pages/map/analyze.vue'),
        meta: { title: 'SQL 解析' },
      },
      {
        path: 'meta',
        name: 'meta',
        component: () => import('../pages/meta.vue'),
        meta: { title: '元数据' },
      },
      // 设置页的**项目层级**地址。它们不是工作台的替身，是嵌入契约本身
      // （dw-model 的「元数据服务」「数据地图设置」推的就是这两个路径，而嵌入
      // 发生在项目页里），所以这里必须是真实路由。详见
      // `config/pages.ts` 的 `PROJECT_SETTINGS_PAGES`。
      {
        path: 'settings/metadata',
        name: 'settings-metadata',
        component: () => import('../pages/settings/metadata-sources.vue'),
        meta: { title: '元数据服务' },
      },
      {
        path: 'settings/map',
        name: 'settings-map',
        component: () => import('../pages/settings/map.vue'),
        meta: { title: '数据地图设置' },
      },
      // 基本信息只在工作台那一级（见 `preferencesHome`）
      { path: 'settings/preferences', redirect: preferencesHome },
      // 这两项是工作台专有的（项目 CRUD、本地账号），项目层级没有对应的页面。
      // 项目 CRUD 只在「本进程说了算」的模式下去工作台那一页，其余模式回项目概况；
      // 账号页更是只对 standard 存在。
      {
        path: 'settings/projects',
        redirect: () => (ownsProjects() ? WORKBENCH_PAGES.projects : LINEAGE_HOME),
      },
      {
        path: 'settings/users',
        redirect: () => (hasLocalAccounts() ? WORKBENCH_PAGES.users : LINEAGE_HOME),
      },
      // 「租户」三种模式都到不了，没有可去的层级，回项目概况
      { path: 'settings/tenants', redirect: LINEAGE_HOME },
      {
        path: 'graph',
        redirect: (to) => ({ path: LINEAGE_PAGES.tables, query: to.query }),
      },
    ],
  },

  { path: '/catalog/tables', redirect: (to) => ({ path: LINEAGE_PAGES.tables, query: to.query }) },
  { path: '/search', redirect: LINEAGE_PAGES.search },
  { path: '/tables/:pathMatch(.*)*', redirect: (to) => `${LINEAGE_PAGES.tables}${to.path.slice('/tables'.length)}` },
  { path: '/tables', redirect: LINEAGE_PAGES.tables },
  { path: '/catalogs', redirect: LINEAGE_PAGES.catalogs },
  { path: '/temp-rules', redirect: LINEAGE_PAGES.tempRules },
  { path: '/analyze', redirect: LINEAGE_PAGES.analyze },
  { path: '/meta/tables', redirect: LINEAGE_PAGES.meta },
  { path: '/meta/sources', redirect: PROJECT_SETTINGS_PAGES.metadata },
  { path: '/meta', redirect: LINEAGE_PAGES.meta },
  { path: '/settings/catalogs', redirect: LINEAGE_PAGES.catalogs },
  { path: '/settings/temp-rules', redirect: LINEAGE_PAGES.tempRules },
  { path: '/settings/tenants', redirect: LINEAGE_HOME },
  { path: '/settings/projects', redirect: PROJECT_SETTINGS_PAGES.projects },
  { path: '/settings/metadata', redirect: PROJECT_SETTINGS_PAGES.metadata },
  { path: '/settings/preferences', redirect: preferencesHome },
  { path: '/settings/map', redirect: PROJECT_SETTINGS_PAGES.map },
  { path: '/settings/users', redirect: PROJECT_SETTINGS_PAGES.users },
  { path: '/app', redirect: LINEAGE_HOME },
  { path: '/app/:pathMatch(.*)*', redirect: (to) => `${LINEAGE_HOME}/${to.params.pathMatch}` },
  { path: '/', redirect: () => defaultHome() },
  { path: '/:pathMatch(.*)*', redirect: () => defaultHome() },
];

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
});

/**
 * 登录门禁。
 *
 * <h2>为什么只看「有没有会话凭据」，不看令牌过没过期</h2>
 *
 * <p>access token 只有 15 分钟，页面开着不动就会过期，但 refresh 还在、换一枚就继续用。
 * 在这里按 access 的有效期拦人，会把「其实完全不需要重登」的用户赶回登录页。
 * 真过期了也不是这里兜——`utils/request` 的 401 拦截器会负责换令牌或送去登录页，
 * 那边才有准确的信息（一次真实的 401 响应）。
 *
 * <h2>运行模式从哪来</h2>
 *
 * <p>`loadRuntime()` 在 main.ts 里 await 在 `app.use(router)` 之前，所以首次导航时
 * 模式已经定了。这里不重复探测：gate 里再发一次请求，只会让首屏多一次往返，
 * 且在「后端还没起来」时会得出错误的模式判断。
 */
router.beforeEach((to) => {
  /** 越界时退回**本层级**的首页，而不是一律退回项目概况。 */
  const levelHome = () => (isWorkbenchPath(to.path) ? WORKBENCH_HOME : LINEAGE_HOME);

  // 非 standard 模式不该出现登录页：standalone 没有账号体系（拦了是无门可入），
  // multi 的身份由组织平台负责，本地再拦一次只会和组织打架。
  if (to.name === 'login' && !requiresLocalLogin()) {
    return { path: LINEAGE_HOME };
  }

  if (requiresLocalLogin()) {
    const loggedIn = hasSession();
    if (!loggedIn && to.name !== 'login') {
      // 把原目标带上，登录后能回到他本来要去的地方，而不是每次都掉回首页
      return { path: LOGIN_PATH, query: to.fullPath === defaultHome() ? {} : { redirect: to.fullPath } };
    }
    if (loggedIn && to.name === 'login') {
      // standard 的首页是工作台（先看全部项目，再点进某一个）
      return { path: defaultHome() };
    }
  }

  // 管理员页的软门禁。`me` 为空（还没拉回来 / 拉失败）时按「不是管理员」处理：
  // 宁可让人先回本层级首页，也不要渲染一个所有请求都 403 的页面。
  // 真正的判权在后端 LocalUserController.requireAdmin()。
  if (to.meta.adminOnly && !authState.me?.admin) {
    return { path: levelHome() };
  }

  return true;
});

router.afterEach((to) => {
  const title = to.meta?.title;
  document.title = title ? `${title} · dw-lineage` : 'dw-lineage';
});

export default router;
