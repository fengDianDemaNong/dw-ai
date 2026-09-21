import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import type { Perm, ProductModule } from '../config/iam';
import { ORG_PAGES } from '../config/pages';
import { ADMIN_HOME, LOGIN_PATH, NO_PROJECT, SELECT_TENANT, SYS_HOME } from '../config/paths';
import { consumeBootHash, openLineageApp, openWarehouseApp } from '../config/product';
import { authToken, useRemoteApi } from '../api/client';
import { app, isPlatformAdmin, isRealTenantAdmin, leaveTenant, resolveTenantHome } from '../stores/app';

consumeBootHash();

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    public?: boolean;
    admin?: boolean;
    multi?: boolean;
    tenant?: boolean;
    project?: boolean;
    member?: boolean;
    perm?: Perm;
    module?: ProductModule;
    shell?: 'admin' | 'sys';
    owner?: 'org';
  }
}

const routes: RouteRecordRaw[] = [
  {
    path: LOGIN_PATH,
    name: 'login',
    component: () => import('../pages/login.vue'),
    meta: { title: '登录', public: true, owner: 'org' },
  },
  {
    path: '/auth/callback',
    name: 'auth-callback',
    component: () => import('../pages/auth-callback.vue'),
    meta: { title: '登录回调', public: true, owner: 'org' },
  },
  {
    path: SELECT_TENANT,
    name: 'select-tenant',
    component: () => import('../pages/select-tenant.vue'),
    meta: { title: '选择组织', multi: true, owner: 'org' },
  },
  {
    path: NO_PROJECT,
    name: 'no-project',
    component: () => import('../pages/no-project.vue'),
    meta: { title: '未加入项目', member: true, owner: 'org' },
  },
  {
    path: '/org/platform',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { admin: true, multi: true, shell: 'admin', owner: 'org' },
    children: [
      { path: '', redirect: ADMIN_HOME },
      { path: 'tenants', name: 'admin-tenants', component: () => import('../pages/admin/tenants.vue'), meta: { title: '租户' } },
      { path: 'services', name: 'admin-services', component: () => import('../pages/admin/services.vue'), meta: { title: '服务注册' } },
      { path: 'users', name: 'admin-users', component: () => import('../pages/admin/users.vue'), meta: { title: '平台用户' } },
      { path: 'settings', name: 'admin-settings', component: () => import('../pages/sys/settings.vue'), meta: { title: '外观与布局' } },
    ],
  },
  {
    path: '/org/workbench',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { tenant: true, shell: 'sys', owner: 'org' },
    children: [
      { path: '', redirect: SYS_HOME },
      { path: 'projects', name: 'sys-projects', component: () => import('../pages/projects.vue'), meta: { title: '项目管理' } },
      { path: 'users', name: 'sys-users', component: () => import('../pages/org-users.vue'), meta: { title: '用户管理' } },
      { path: 'roles', name: 'sys-roles', component: () => import('../pages/sys/roles.vue'), meta: { title: '角色管理' } },
      { path: 'knowledge', name: 'sys-knowledge', component: () => import('../pages/sys/knowledge.vue'), meta: { title: '知识库' } },
      { path: 'settings', name: 'sys-settings', component: () => import('../pages/sys/settings.vue'), meta: { title: '设置' } },
    ],
  },
  { path: '/login', redirect: LOGIN_PATH },
  { path: '/select-tenant', redirect: SELECT_TENANT },
  { path: '/no-project', redirect: NO_PROJECT },
  { path: '/platform', redirect: ADMIN_HOME },
  { path: '/platform/:pathMatch(.*)*', redirect: (to) => `/org/platform/${to.params.pathMatch}` },
  { path: '/workbench', redirect: SYS_HOME },
  { path: '/workbench/:pathMatch(.*)*', redirect: (to) => `/org/workbench/${to.params.pathMatch}` },
  { path: '/admin', redirect: ADMIN_HOME },
  { path: '/admin/tenants', redirect: ADMIN_HOME },
  { path: '/admin/users', redirect: ORG_PAGES.platformUsers },
  { path: '/admin/settings', redirect: ORG_PAGES.platformSettings },
  { path: '/sys', redirect: SYS_HOME },
  { path: '/sys/users', redirect: ORG_PAGES.users },
  { path: '/sys/roles', redirect: ORG_PAGES.roles },
  { path: '/sys/knowledge', redirect: ORG_PAGES.knowledge },
  { path: '/sys/settings', redirect: ORG_PAGES.settings },
  { path: '/sys/projects', redirect: SYS_HOME },
  { path: '/org/users', redirect: ORG_PAGES.users },
  { path: '/projects', redirect: SYS_HOME },
  {
    path: '/app',
    redirect: () => {
      openWarehouseApp();
      return SYS_HOME;
    },
  },
  {
    path: '/app/:pathMatch(.*)*',
    redirect: () => {
      openWarehouseApp();
      return SYS_HOME;
    },
  },
  {
    path: '/model',
    redirect: () => {
      openWarehouseApp();
      return SYS_HOME;
    },
  },
  {
    path: '/model/:pathMatch(.*)*',
    redirect: () => {
      openWarehouseApp();
      return SYS_HOME;
    },
  },
  {
    path: '/lineage',
    redirect: () => {
      openLineageApp();
      return SYS_HOME;
    },
  },
  {
    path: '/lineage/:pathMatch(.*)*',
    redirect: () => {
      openLineageApp();
      return SYS_HOME;
    },
  },
  { path: '/', redirect: LOGIN_PATH },
  { path: '/:pathMatch(.*)*', redirect: () => resolveTenantHome() },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  const loggedIn = useRemoteApi() ? Boolean(authToken()) : Boolean(app.currentUserId || app.currentUser);
  const home = () => ({ path: resolveTenantHome() });
  if (to.meta.public) {
    if (!loggedIn) return true;
    if (isPlatformAdmin.value && !app.currentTenantId) return { path: ADMIN_HOME };
    if (!app.currentTenantId && !isPlatformAdmin.value) return { path: SELECT_TENANT };
    return home();
  }
  if (!loggedIn && to.name !== 'auth-callback') return { path: LOGIN_PATH };
  if (to.meta.admin && !isPlatformAdmin.value) return { path: SELECT_TENANT };
  if (to.name === 'select-tenant') return true;
  if ((to.meta.tenant || to.meta.member) && !app.currentTenantId) {
    return { path: SELECT_TENANT };
  }
  if (to.meta.tenant && !isRealTenantAdmin.value) {
    if (to.name === 'sys-projects') return true;
    return home();
  }
  if (to.name === 'no-project') {
    const next = resolveTenantHome();
    if (next !== NO_PROJECT) return { path: next };
    return true;
  }
  return true;
});

router.afterEach((to) => {
  if (to.matched.some((r) => r.meta.admin)) leaveTenant();
  const title = to.meta?.title as string | undefined;
  document.title = title ? `${title} · 租户管理` : '租户管理';
});

export default router;
