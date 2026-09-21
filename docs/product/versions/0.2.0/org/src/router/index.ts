import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { ADMIN_HOME, NO_PROJECT, SYS_HOME } from '../config/paths';
import { WAREHOUSE_ORIGIN, openOrigin } from '../config/suite';
import { isMultiTenant } from '../config/runtime';
import { app, isPlatformAdmin, isRealTenantAdmin, resolveTenantHome } from '../stores/app';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../pages/login.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/select-tenant',
    name: 'select-tenant',
    component: () => import('../pages/select-tenant.vue'),
    meta: { title: '选择租户', multi: true },
  },
  {
    path: '/platform',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { admin: true, multi: true, shell: 'admin' },
    children: [
      { path: '', redirect: ADMIN_HOME },
      { path: 'tenants', name: 'admin-tenants', component: () => import('../pages/admin/tenants.vue'), meta: { title: '租户' } },
      { path: 'services', name: 'admin-services', component: () => import('../pages/admin/services.vue'), meta: { title: '服务注册' } },
      { path: 'users', name: 'admin-users', component: () => import('../pages/admin/users.vue'), meta: { title: '平台用户' } },
      { path: 'settings', name: 'admin-settings', component: () => import('../pages/sys/settings.vue'), meta: { title: '外观与布局' } },
    ],
  },
  {
    path: '/workbench',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { tenant: true, shell: 'sys' },
    children: [
      { path: '', redirect: SYS_HOME },
      { path: 'projects', name: 'sys-projects', component: () => import('../pages/projects.vue'), meta: { title: '项目管理' } },
      { path: 'users', name: 'sys-users', component: () => import('../pages/org-users.vue'), meta: { title: '用户管理' } },
      { path: 'roles', name: 'sys-roles', component: () => import('../pages/sys/roles.vue'), meta: { title: '角色管理' } },
      { path: 'modules', name: 'sys-modules', component: () => import('../pages/sys/modules.vue'), meta: { title: '模块管理' } },
      { path: 'compute', name: 'sys-compute', component: () => import('../pages/sys/compute.vue'), meta: { title: '计算资源' } },
      { path: 'knowledge', name: 'sys-knowledge', component: () => import('../pages/sys/knowledge.vue'), meta: { title: '知识库' } },
      { path: 'settings', name: 'sys-settings', component: () => import('../pages/sys/settings.vue'), meta: { title: '设置' } },
    ],
  },
  { path: '/admin', redirect: ADMIN_HOME },
  { path: '/admin/:pathMatch(.*)*', redirect: (to) => `/platform/${to.params.pathMatch}` },
  { path: '/sys', redirect: SYS_HOME },
  { path: '/sys/:pathMatch(.*)*', redirect: (to) => `/workbench/${to.params.pathMatch}` },
  { path: '/projects', redirect: SYS_HOME },
  { path: '/org/users', redirect: '/workbench/users' },
  {
    path: NO_PROJECT,
    name: 'no-project',
    component: () => import('../pages/no-project.vue'),
    meta: { title: '未加入项目', member: true },
  },
  { path: '/', redirect: '/login' },
  { path: '/:pathMatch(.*)*', redirect: () => resolveTenantHome() },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  const loggedIn = Boolean(app.currentUserId);
  const multi = isMultiTenant();
  const home = () => ({ path: resolveTenantHome() });
  if (to.meta.multi && !multi) return loggedIn ? home() : { path: '/login' };
  if (to.meta.public) {
    if (!loggedIn) return true;
    if (!multi) {
      if (isRealTenantAdmin.value) return home();
      openOrigin(WAREHOUSE_ORIGIN, '/app');
      return false;
    }
    if (isPlatformAdmin.value) return { path: ADMIN_HOME };
    if (!app.currentTenantId) return { path: '/select-tenant' };
    if (isRealTenantAdmin.value) return home();
    openOrigin(WAREHOUSE_ORIGIN, '/app');
    return false;
  }
  if (!loggedIn) return { path: '/login' };
  if (to.meta.admin && !isPlatformAdmin.value) return { path: '/select-tenant' };
  if (to.name === 'select-tenant') return multi ? true : home();
  if ((to.meta.tenant || to.meta.member) && !app.currentTenantId) {
    return multi ? { path: '/select-tenant' } : { path: '/login' };
  }
  if (to.meta.tenant && !isRealTenantAdmin.value) {
    openOrigin(WAREHOUSE_ORIGIN, '/app');
    return false;
  }
  if (to.name === 'no-project') {
    const next = resolveTenantHome();
    if (next !== NO_PROJECT) return { path: next };
    return true;
  }
  return true;
});

router.afterEach((to) => {
  const title = to.meta?.title as string | undefined;
  document.title = title ? `${title} · 组织平台 0.2.0` : '组织平台原型 0.2.0';
});

export default router;
