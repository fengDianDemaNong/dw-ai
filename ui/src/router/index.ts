import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import type { Perm, ProductModule } from '../config/iam';
import { ADMIN_HOME, NO_PROJECT, SYS_HOME } from '../config/paths';
import { isMultiTenant } from '../config/runtime';
import { authToken, useRemoteApi } from '../api/client';
import { app, can, hasModule, isPlatformAdmin, isRealTenantAdmin, leaveTenant, resolveTenantHome } from '../stores/app';

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
  }
}

const projectChildren: RouteRecordRaw[] = [
  { path: '', name: 'dashboard', component: () => import('../pages/dashboard.vue'), meta: { title: '概况' } },
  { path: 'forbidden', name: 'forbidden', component: () => import('../pages/forbidden.vue'), meta: { title: '无权访问' } },
  { path: 'members', name: 'members', component: () => import('../pages/members.vue'), meta: { title: '项目成员', perm: 'iam:member' } },
  { path: 'knowledge', name: 'project-knowledge', component: () => import('../pages/knowledge.vue'), meta: { title: '知识库' } },
  { path: 'spec/copilot', name: 'spec-copilot', component: () => import('../pages/spec/copilot.vue'), meta: { title: 'AI 设计规范', module: 'warehouse', perm: 'spec:read' } },
  { path: 'spec/domains', name: 'domains', component: () => import('../pages/spec/domains.vue'), meta: { title: '主题域', module: 'warehouse', perm: 'spec:read' } },
  { path: 'spec/layers', name: 'layers', component: () => import('../pages/spec/layers.vue'), meta: { title: '分层规范', module: 'warehouse', perm: 'spec:read' } },
  { path: 'spec/grades', name: 'grades', component: () => import('../pages/spec/grades.vue'), meta: { title: '数据等级', module: 'warehouse', perm: 'spec:read' } },
  { path: 'spec/roots', name: 'roots', component: () => import('../pages/spec/roots.vue'), meta: { title: '词根库', module: 'warehouse', perm: 'spec:read' } },
  { path: 'spec/io', name: 'spec-io', component: () => import('../pages/spec/io.vue'), meta: { title: '导入导出', module: 'warehouse', perm: 'spec:read' } },
  { path: 'model/validate', name: 'validate', component: () => import('../pages/model/validate.vue'), meta: { title: '规范校验', module: 'warehouse', perm: 'model:read' } },
  { path: 'model/ods-dwd', name: 'ods-dwd', component: () => import('../pages/model/ods-dwd.vue'), meta: { title: '从 ODS 生成', module: 'warehouse', perm: 'model:read' } },
  { path: 'model/dwd-dws', name: 'dwd-dws', component: () => import('../pages/model/dwd-dws.vue'), meta: { title: '从 DWD 生成', module: 'warehouse', perm: 'model:read' } },
  { path: 'model/:layer/ai', name: 'layer-ai', component: () => import('../pages/model/copilot.vue'), meta: { title: 'AI 设计表', module: 'warehouse', perm: 'model:read' } },
  { path: 'model/:layer/:domain/:tableId/versions', name: 'layer-versions', component: () => import('../pages/model/versions.vue'), meta: { title: '模型版本', module: 'warehouse', perm: 'model:read' } },
  { path: 'model/:layer/:domain/:tableId', name: 'layer-detail', component: () => import('../pages/model/dwd-detail.vue'), meta: { title: '表详情', module: 'warehouse', perm: 'model:read' } },
  { path: 'model/:layer/:domain', name: 'layer-tables', component: () => import('../pages/model/dwd-tables.vue'), meta: { title: '表列表', module: 'warehouse', perm: 'model:read' } },
  { path: 'model/:layer', name: 'layer-overview', component: () => import('../pages/model/dwd-overview.vue'), meta: { title: '建模', module: 'warehouse', perm: 'model:read' } },
];

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../pages/login.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/auth/callback',
    name: 'auth-callback',
    component: () => import('../pages/auth-callback.vue'),
    meta: { title: '登录回调', public: true },
  },
  {
    path: NO_PROJECT,
    name: 'no-project',
    component: () => import('../pages/no-project.vue'),
    meta: { title: '未加入项目', member: true },
  },
  {
    path: '/projects',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { tenant: true, shell: 'sys' },
    children: [{ path: '', name: 'sys-projects', component: () => import('../pages/projects.vue'), meta: { title: '项目管理' } }],
  },
  {
    path: '/sys',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { tenant: true, shell: 'sys' },
    children: [
      { path: '', redirect: SYS_HOME },
      { path: 'users', name: 'sys-users', component: () => import('../pages/org-users.vue'), meta: { title: '用户管理' } },
      { path: 'roles', name: 'sys-roles', component: () => import('../pages/sys/roles.vue'), meta: { title: '角色管理' } },
      { path: 'projects', redirect: SYS_HOME },
      { path: 'knowledge', name: 'sys-knowledge', component: () => import('../pages/sys/knowledge.vue'), meta: { title: '知识库' } },
      { path: 'settings', name: 'sys-settings', component: () => import('../pages/sys/settings.vue'), meta: { title: '设置' } },
    ],
  },
  {
    path: '/w',
    component: () => import('../layouts/ProjectLayout.vue'),
    meta: { project: true },
    children: projectChildren,
  },
  { path: '/', redirect: '/login' },
  { path: '/:pathMatch(.*)*', redirect: () => resolveTenantHome() },
];

if (isMultiTenant()) {
  routes.splice(2, 0,
    {
      path: '/select-tenant',
      name: 'select-tenant',
      component: () => import('../pages/select-tenant.vue'),
      meta: { title: '选择组织', multi: true },
    },
    {
      path: '/admin',
      component: () => import('../layouts/SystemLayout.vue'),
      meta: { admin: true, multi: true, shell: 'admin' },
      children: [
        { path: '', name: 'admin-tenants', component: () => import('../pages/admin/tenants.vue'), meta: { title: '租户' } },
        { path: 'users', name: 'admin-users', component: () => import('../pages/admin/users.vue'), meta: { title: '平台用户' } },
        { path: 'tenants', redirect: '/admin' },
        { path: 'settings', name: 'admin-settings', component: () => import('../pages/sys/settings.vue'), meta: { title: '外观与布局' } },
      ],
    },
  );
}

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  const loggedIn = useRemoteApi() ? Boolean(authToken()) : Boolean(app.currentUserId || app.currentUser);
  const multi = isMultiTenant();
  const home = () => ({ path: resolveTenantHome() });
  if (to.meta.multi && !multi) return loggedIn ? home() : { path: '/login' };
  if (to.meta.public) {
    if (!loggedIn) return true;
    if (!multi) return home();
    if (isPlatformAdmin.value && !app.currentTenantId) return { path: ADMIN_HOME };
    if (multi && !app.currentTenantId && !isPlatformAdmin.value) return { path: '/select-tenant' };
    return home();
  }
  if (!loggedIn && to.name !== 'auth-callback') return { path: '/login' };
  if (to.meta.admin && !isPlatformAdmin.value) return { path: '/select-tenant' };
  if (to.name === 'select-tenant') return multi ? true : home();
  if ((to.meta.tenant || to.meta.project || to.meta.member) && !app.currentTenantId) {
    return multi ? { path: '/select-tenant' } : { path: '/login' };
  }
  if (to.meta.tenant && !isRealTenantAdmin.value) return home();
  if (to.name === 'no-project') {
    const next = resolveTenantHome();
    if (next !== NO_PROJECT) return { path: next };
    return true;
  }
  if (to.meta.project && !app.currentProjectId) return home();
  if (to.name === 'forbidden') return true;
  if (to.meta.module && !hasModule(to.meta.module)) return { name: 'forbidden' };
  if (to.meta.perm && !can(to.meta.perm)) return { name: 'forbidden' };
  return true;
});

router.afterEach((to) => {
  if (to.matched.some((r) => r.meta.admin)) leaveTenant();
  const layer = typeof to.params.layer === 'string' ? to.params.layer.toUpperCase() : '';
  const title = layer || (to.meta?.title as string | undefined);
  document.title = title ? `${title} · 智仓 DW-AI` : '智仓 DW-AI';
});

export default router;
