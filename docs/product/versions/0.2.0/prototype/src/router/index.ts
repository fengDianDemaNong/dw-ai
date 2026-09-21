import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import { NO_PROJECT } from '../config/paths';
import { ORG_ORIGIN, openOrigin } from '../config/suite';
import { app, resolveTenantHome } from '../stores/app';

const routes: RouteRecordRaw[] = [
  {
    path: NO_PROJECT,
    name: 'no-project',
    component: () => import('../pages/no-project.vue'),
    meta: { title: '未加入项目', member: true },
  },
  {
    path: '/app',
    component: () => import('../layouts/ProjectLayout.vue'),
    meta: { project: true },
    children: [
      { path: '', name: 'dashboard', component: () => import('../pages/dashboard.vue'), meta: { title: '概况' } },
      { path: 'members', name: 'members', component: () => import('../pages/members.vue'), meta: { title: '项目成员' } },
      { path: 'knowledge', name: 'knowledge', component: () => import('../pages/knowledge.vue'), meta: { title: '知识库' } },
      { path: 'spec/copilot', name: 'spec-copilot', component: () => import('../pages/spec/copilot.vue'), meta: { title: 'AI 设计规范' } },
      { path: 'spec/domains', name: 'domains', component: () => import('../pages/spec/domains.vue'), meta: { title: '主题域' } },
      { path: 'spec/layers', name: 'layers', component: () => import('../pages/spec/layers.vue'), meta: { title: '分层规范' } },
      { path: 'spec/grades', name: 'grades', component: () => import('../pages/spec/grades.vue'), meta: { title: '数据等级' } },
      { path: 'spec/roots', name: 'roots', component: () => import('../pages/spec/roots.vue'), meta: { title: '词根库' } },
      { path: 'spec/logic', name: 'spec-logic', component: () => import('../pages/spec/logic.vue'), meta: { title: '加工类型' } },
      { path: 'spec/io', name: 'spec-io', component: () => import('../pages/spec/io.vue'), meta: { title: '导入导出' } },
      { path: 'model/validate', name: 'validate', component: () => import('../pages/model/validate.vue'), meta: { title: '规范校验' } },
      { path: 'model/lineage', name: 'model-lineage', component: () => import('../pages/model/lineage.vue'), meta: { title: '影响与血缘' } },
      { path: 'model/ods-dwd', name: 'ods-dwd', component: () => import('../pages/model/ods-dwd.vue'), meta: { title: '从 ODS 生成' } },
      { path: 'model/dwd-dws', name: 'dwd-dws', component: () => import('../pages/model/dwd-dws.vue'), meta: { title: '从 DWD 生成' } },
      { path: 'model/:layer/ai', name: 'layer-ai', component: () => import('../pages/model/copilot.vue'), meta: { title: 'AI 设计表' } },
      { path: 'model/:layer/:domain/:tableId/versions', name: 'layer-versions', component: () => import('../pages/model/versions.vue'), meta: { title: '模型版本' } },
      { path: 'model/:layer/:domain/:tableId', name: 'layer-detail', component: () => import('../pages/model/dwd-detail.vue'), meta: { title: '表详情' } },
      { path: 'model/:layer/:domain', name: 'layer-tables', component: () => import('../pages/model/dwd-tables.vue'), meta: { title: '表列表' } },
      { path: 'model/:layer', name: 'layer-overview', component: () => import('../pages/model/dwd-overview.vue'), meta: { title: '建模' } },
      { path: 'map/search', name: 'map-search', component: () => import('../pages/map/embed.vue'), meta: { title: '全文检索', lineagePath: '/search' } },
      { path: 'map/tables', name: 'map-tables', component: () => import('../pages/map/embed.vue'), meta: { title: '血缘', lineagePath: '/tables' } },
      { path: 'map/catalogs', name: 'map-catalogs', component: () => import('../pages/map/embed.vue'), meta: { title: '数据目录', lineagePath: '/catalogs' } },
      { path: 'map/temp-rules', name: 'map-temp-rules', component: () => import('../pages/map/embed.vue'), meta: { title: '临时表规则', lineagePath: '/temp-rules' } },
      { path: 'map/analyze', name: 'map-analyze', component: () => import('../pages/map/embed.vue'), meta: { title: 'SQL 解析', lineagePath: '/analyze' } },
      { path: 'map/meta', name: 'map-meta', component: () => import('../pages/map/embed.vue'), meta: { title: '元数据', lineagePath: '/meta' } },
      { path: 'settings/nav', name: 'settings-nav', component: () => import('../pages/settings/nav.vue'), meta: { title: '外观' } },
      { path: 'settings/metadata', name: 'settings-metadata', component: () => import('../pages/map/embed.vue'), meta: { title: '元数据服务', lineagePath: '/settings/metadata' } },
      { path: 'settings/map', name: 'settings-map', component: () => import('../pages/map/embed.vue'), meta: { title: '数据地图设置', lineagePath: '/settings/map' } },
      { path: 'meta/tables', redirect: '/app/map/meta' },
      { path: 'meta/lineage', redirect: '/app/map/tables' },
      { path: 'meta/analyze', redirect: '/app/map/analyze' },
      { path: 'meta/catalogs', redirect: '/app/map/catalogs' },
      { path: 'meta/temp-rules', redirect: '/app/map/temp-rules' },
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
  { path: '/w', redirect: '/app' },
  { path: '/w/:pathMatch(.*)*', redirect: (to) => `/app/${to.params.pathMatch}` },
  { path: '/', redirect: '/app' },
  { path: '/:pathMatch(.*)*', redirect: '/app' },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  if (!app.currentUserId) {
    openOrigin(ORG_ORIGIN, '/login');
    return false;
  }
  if ((to.meta.project || to.meta.member) && !app.currentTenantId) {
    openOrigin(ORG_ORIGIN, '/select-tenant');
    return false;
  }
  if (to.name === 'no-project') {
    const next = resolveTenantHome();
    if (next !== NO_PROJECT) return { path: next };
    return true;
  }
  if (to.meta.project && !app.currentProjectId) {
    openOrigin(ORG_ORIGIN, '/workbench/projects');
    return false;
  }
  return true;
});

router.afterEach((to) => {
  const layer = typeof to.params.layer === 'string' ? to.params.layer.toUpperCase() : '';
  const title = layer || (to.meta?.title as string | undefined);
  document.title = title ? `${title} · 仓建设 0.2.0` : '仓建设原型 0.2.0';
});

export default router;
