import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import AppLayout from '../layouts/AppLayout.vue';
import Overview from '../pages/overview.vue';
import { LINEAGE_HOME, LINEAGE_PAGES, showLocalTenantSettings } from '../config/pages';

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
  }
}

/**
 * 路由表。本进程页面统一挂 /lineage。
 *
 * 路径不要带点号：后端 WebStaticConfig 的 SPA fallback 会把「最后一段含 .」的路径
 * 判定为静态资源并直接返回 404，不会回落到 index.html。
 */
const routes: RouteRecordRaw[] = [
  {
    path: LINEAGE_HOME,
    component: AppLayout,
    children: [
      {
        path: '',
        name: 'overview',
        component: Overview,
        meta: { title: '概览' },
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
      {
        path: 'settings/tenants',
        name: 'settings-tenants',
        component: () => import('../pages/settings/tenants.vue'),
        meta: { title: '租户' },
      },
      {
        path: 'settings/projects',
        name: 'settings-projects',
        component: () => import('../pages/settings/projects.vue'),
        meta: { title: '项目' },
      },
      {
        path: 'settings/metadata',
        name: 'settings-metadata',
        component: () => import('../pages/settings/metadata-sources.vue'),
        meta: { title: '元数据服务' },
      },
      {
        path: 'settings/preferences',
        name: 'settings-preferences',
        component: () => import('../pages/settings/preferences.vue'),
        meta: { title: '基本信息' },
      },
      {
        path: 'settings/map',
        name: 'settings-map',
        component: () => import('../pages/settings/preferences.vue'),
        meta: { title: '数据地图设置' },
      },
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
  { path: '/meta/sources', redirect: LINEAGE_PAGES.metadata },
  { path: '/meta', redirect: LINEAGE_PAGES.meta },
  { path: '/settings/catalogs', redirect: LINEAGE_PAGES.catalogs },
  { path: '/settings/temp-rules', redirect: LINEAGE_PAGES.tempRules },
  { path: '/settings/tenants', redirect: LINEAGE_PAGES.tenants },
  { path: '/settings/projects', redirect: LINEAGE_PAGES.projects },
  { path: '/settings/metadata', redirect: LINEAGE_PAGES.metadata },
  { path: '/settings/preferences', redirect: LINEAGE_PAGES.preferences },
  { path: '/settings/map', redirect: LINEAGE_PAGES.map },
  { path: '/app', redirect: LINEAGE_HOME },
  { path: '/app/:pathMatch(.*)*', redirect: (to) => `${LINEAGE_HOME}/${to.params.pathMatch}` },
  { path: '/', redirect: LINEAGE_HOME },
  { path: '/:pathMatch(.*)*', redirect: LINEAGE_HOME },
];

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
});

router.beforeEach((to) => {
  if (!showLocalTenantSettings() && (to.name === 'settings-tenants' || to.name === 'settings-projects')) {
    return { path: LINEAGE_HOME };
  }
  return true;
});

router.afterEach((to) => {
  const title = to.meta?.title;
  document.title = title ? `${title} - sql-tools` : 'sql-tools';
});

export default router;
