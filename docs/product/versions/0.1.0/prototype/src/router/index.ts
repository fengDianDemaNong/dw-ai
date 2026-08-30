import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { app } from '../stores/app';

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('../pages/login.vue'),
    meta: { title: '选择租户' },
  },
  {
    path: '/projects',
    name: 'projects',
    component: () => import('../pages/projects.vue'),
    meta: { title: '项目', tenant: true },
  },
  {
    path: '/w',
    component: () => import('../layouts/ProjectLayout.vue'),
    meta: { project: true },
    children: [
      { path: '', name: 'dashboard', component: () => import('../pages/dashboard.vue'), meta: { title: '工作台' } },
      { path: 'spec/copilot', name: 'spec-copilot', component: () => import('../pages/spec/copilot.vue'), meta: { title: 'AI 设计规范' } },
      { path: 'spec/domains', name: 'domains', component: () => import('../pages/spec/domains.vue'), meta: { title: '主题域' } },
      { path: 'spec/layers', name: 'layers', component: () => import('../pages/spec/layers.vue'), meta: { title: '分层规范' } },
      { path: 'spec/grades', name: 'grades', component: () => import('../pages/spec/grades.vue'), meta: { title: '数据等级' } },
      { path: 'spec/roots', name: 'roots', component: () => import('../pages/spec/roots.vue'), meta: { title: '词根库' } },
      { path: 'spec/io', name: 'spec-io', component: () => import('../pages/spec/io.vue'), meta: { title: '导入导出' } },
      { path: 'model/validate', name: 'validate', component: () => import('../pages/model/validate.vue'), meta: { title: '规范校验' } },
      { path: 'model/ods-dwd', name: 'ods-dwd', component: () => import('../pages/model/ods-dwd.vue'), meta: { title: '从 ODS 生成' } },
      { path: 'model/dwd-dws', name: 'dwd-dws', component: () => import('../pages/model/dwd-dws.vue'), meta: { title: '从 DWD 生成' } },
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
  { path: '/:pathMatch(.*)*', redirect: '/projects' },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  if (to.meta.project && !app.currentProjectId) return { path: '/projects' };
  return true;
});

router.afterEach((to) => {
  const layer = typeof to.params.layer === 'string' ? to.params.layer.toUpperCase() : '';
  const title = layer || (to.meta?.title as string | undefined);
  document.title = title ? `${title} · 产品原型 0.1.0` : '智仓产品原型 0.1.0';
});

export default router;
