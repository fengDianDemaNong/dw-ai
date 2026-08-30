import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { ADMIN_HOME, NO_PROJECT, SYS_HOME } from '../config/paths';
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
      { path: 'settings', name: 'sys-settings', component: () => import('../pages/sys/settings.vue'), meta: { title: '设置' } },
    ],
  },
  { path: '/org/users', redirect: '/sys/users' },
  {
    path: NO_PROJECT,
    name: 'no-project',
    component: () => import('../pages/no-project.vue'),
    meta: { title: '未加入项目', member: true },
  },
  {
    path: '/w',
    component: () => import('../layouts/ProjectLayout.vue'),
    meta: { project: true },
    children: [
      { path: '', name: 'dashboard', component: () => import('../pages/dashboard.vue'), meta: { title: '概况' } },
      { path: 'members', name: 'members', component: () => import('../pages/members.vue'), meta: { title: '项目成员' } },
      { path: 'spec/copilot', name: 'spec-copilot', component: () => import('../pages/spec/copilot.vue'), meta: { title: 'AI 设计规范' } },
      { path: 'spec/domains', name: 'domains', component: () => import('../pages/spec/domains.vue'), meta: { title: '主题域' } },
      { path: 'spec/layers', name: 'layers', component: () => import('../pages/spec/layers.vue'), meta: { title: '分层规范' } },
      { path: 'spec/grades', name: 'grades', component: () => import('../pages/spec/grades.vue'), meta: { title: '数据等级' } },
      { path: 'spec/roots', name: 'roots', component: () => import('../pages/spec/roots.vue'), meta: { title: '词根库' } },
      { path: 'spec/io', name: 'spec-io', component: () => import('../pages/spec/io.vue'), meta: { title: '导入导出' } },
      { path: 'model/validate', name: 'validate', component: () => import('../pages/model/validate.vue'), meta: { title: '规范校验' } },
      { path: 'model/ods-dwd', name: 'ods-dwd', component: () => import('../pages/model/ods-dwd.vue'), meta: { title: '从 ODS 生成' } },
      { path: 'model/dwd-dws', name: 'dwd-dws', component: () => import('../pages/model/dwd-dws.vue'), meta: { title: '从 DWD 生成' } },
      { path: 'model/:layer/ai', name: 'layer-ai', component: () => import('../pages/model/copilot.vue'), meta: { title: 'AI 设计表' } },
      { path: 'model/:layer/:domain/:tableId/versions', name: 'layer-versions', component: () => import('../pages/model/versions.vue'), meta: { title: '模型版本' } },
      { path: 'model/:layer/:domain/:tableId', name: 'layer-detail', component: () => import('../pages/model/dwd-detail.vue'), meta: { title: '表详情' } },
      { path: 'model/:layer/:domain', name: 'layer-tables', component: () => import('../pages/model/dwd-tables.vue'), meta: { title: '表列表' } },
      { path: 'model/:layer', name: 'layer-overview', component: () => import('../pages/model/dwd-overview.vue'), meta: { title: '建模' } },
      { path: 'dev/jobs', name: 'jobs', component: () => import('../pages/dev/jobs.vue'), meta: { title: '任务调度' } },
      { path: 'dev/quality', name: 'quality', component: () => import('../pages/dev/quality.vue'), meta: { title: '数据质量' } },
      { path: 'dev/lineage', name: 'lineage', component: () => import('../pages/dev/lineage.vue'), meta: { title: '血缘追踪' } },
      { path: 'materialize', name: 'materialize', component: () => import('../pages/materialize.vue'), meta: { title: '沉淀优化' } },
      { path: 'service/catalog', name: 'catalog', component: () => import('../pages/service/catalog.vue'), meta: { title: '指标目录' } },
      { path: 'service/factory', name: 'factory', component: () => import('../pages/service/factory.vue'), meta: { title: '指标工厂' } },
      { path: 'service/gateway', name: 'gateway', component: () => import('../pages/service/gateway.vue'), meta: { title: 'API 网关' } },
      { path: 'service/market', name: 'market', component: () => import('../pages/service/market.vue'), meta: { title: '指标市场' } },
      { path: 'serve/market', name: 'serve-market', component: () => import('../pages/serve/market.vue'), meta: { title: '数据市场' } },
      { path: 'serve/factory', name: 'serve-factory', component: () => import('../pages/serve/factory.vue'), meta: { title: '指标工厂（新）' } },
    ],
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
    if (!multi) return home();
    if (isPlatformAdmin.value) return { path: ADMIN_HOME };
    if (!app.currentTenantId) return { path: '/select-tenant' };
    return home();
  }
  if (!loggedIn) return { path: '/login' };
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
  return true;
});

router.afterEach((to) => {
  const layer = typeof to.params.layer === 'string' ? to.params.layer.toUpperCase() : '';
  const title = layer || (to.meta?.title as string | undefined);
  const mode = isMultiTenant() ? '多租户' : '普通模式';
  document.title = title ? `${title} · 产品原型 0.1.1` : `智仓产品原型 0.1.1 · ${mode}`;
});

export default router;
