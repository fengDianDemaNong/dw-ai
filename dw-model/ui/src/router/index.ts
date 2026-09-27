import { createRouter, createWebHistory } from 'vue-router';
import type { RouteRecordRaw } from 'vue-router';
import type { Perm, ProductModule } from '../config/iam';
import { guestAccess, localLoginRequired } from '../config/pages';
import { LOGIN_PATH, MODEL_HOME, NO_PROJECT, SYS_HOME } from '../config/paths';
import { isMultiTenant } from '../config/runtime';
import { consumeBootHash, openOrgLogin } from '../config/product';
import { authToken, useRemoteApi } from '../api/client';
import { app, can, hasModule, resolveTenantHome } from '../stores/app';

consumeBootHash();

declare module 'vue-router' {
  interface RouteMeta {
    title?: string;
    public?: boolean;
    tenant?: boolean;
    project?: boolean;
    member?: boolean;
    perm?: Perm;
    module?: ProductModule;
    /** 与 dw-org/ui 保持同一套声明：布局组件里仍按 admin/sys 分流（本 UI 目前只有 sys 路由）。 */
    shell?: 'admin' | 'sys';
    owner?: 'model' | 'org';
  }
}

const projectChildren: RouteRecordRaw[] = [
  { path: '', name: 'dashboard', component: () => import('../pages/dashboard.vue'), meta: { title: '概况', owner: 'model' } },
  { path: 'forbidden', name: 'forbidden', component: () => import('../pages/forbidden.vue'), meta: { title: '无权访问', owner: 'model' } },
  { path: 'members', name: 'members', component: () => import('../pages/members.vue'), meta: { title: '项目成员', perm: 'iam:member', owner: 'model' } },
  { path: 'knowledge', name: 'project-knowledge', component: () => import('../pages/knowledge.vue'), meta: { title: '知识库', owner: 'model' } },
  { path: 'spec/copilot', name: 'spec-copilot', component: () => import('../pages/spec/copilot.vue'), meta: { title: 'AI 设计规范', module: 'warehouse', perm: 'spec:read', owner: 'model' } },
  { path: 'spec/domains', name: 'domains', component: () => import('../pages/spec/domains.vue'), meta: { title: '主题域', module: 'warehouse', perm: 'spec:read', owner: 'model' } },
  { path: 'spec/layers', name: 'layers', component: () => import('../pages/spec/layers.vue'), meta: { title: '分层规范', module: 'warehouse', perm: 'spec:read', owner: 'model' } },
  { path: 'spec/grades', name: 'grades', component: () => import('../pages/spec/grades.vue'), meta: { title: '数据等级', module: 'warehouse', perm: 'spec:read', owner: 'model' } },
  { path: 'spec/roots', name: 'roots', component: () => import('../pages/spec/roots.vue'), meta: { title: '词根库', module: 'warehouse', perm: 'spec:read', owner: 'model' } },
  { path: 'spec/logic', name: 'spec-logic', component: () => import('../pages/spec/logic.vue'), meta: { title: '加工类型', module: 'warehouse', perm: 'spec:read', owner: 'model' } },
  { path: 'spec/io', name: 'spec-io', component: () => import('../pages/spec/io.vue'), meta: { title: '导入导出', module: 'warehouse', perm: 'spec:read', owner: 'model' } },
  { path: 'validate', name: 'validate', component: () => import('../pages/model/validate.vue'), meta: { title: '规范校验', module: 'warehouse', perm: 'model:read', owner: 'model' } },
  { path: 'ods-dwd', name: 'ods-dwd', component: () => import('../pages/model/ods-dwd.vue'), meta: { title: '从 ODS 生成', module: 'warehouse', perm: 'model:read', owner: 'model' } },
  { path: 'dwd-dws', name: 'dwd-dws', component: () => import('../pages/model/dwd-dws.vue'), meta: { title: '从 DWD 生成', module: 'warehouse', perm: 'model:read', owner: 'model' } },
  { path: ':layer/ai', name: 'layer-ai', component: () => import('../pages/model/copilot.vue'), meta: { title: 'AI 设计表', module: 'warehouse', perm: 'model:read', owner: 'model' } },
  { path: ':layer/:domain/:tableId/versions', name: 'layer-versions', component: () => import('../pages/model/versions.vue'), meta: { title: '模型版本', module: 'warehouse', perm: 'model:read', owner: 'model' } },
  { path: ':layer/:domain/:tableId', name: 'layer-detail', component: () => import('../pages/model/dwd-detail.vue'), meta: { title: '表详情', module: 'warehouse', perm: 'model:read', owner: 'model' } },
  { path: ':layer/:domain', name: 'layer-tables', component: () => import('../pages/model/dwd-tables.vue'), meta: { title: '表列表', module: 'warehouse', perm: 'model:read', owner: 'model' } },
  { path: ':layer', name: 'layer-overview', component: () => import('../pages/model/dwd-overview.vue'), meta: { title: '建模', module: 'warehouse', perm: 'model:read', owner: 'model' } },
];

const routes: RouteRecordRaw[] = [
  {
    path: LOGIN_PATH,
    name: 'login',
    component: () => import('../pages/login.vue'),
    meta: { title: '登录', public: true, owner: 'model' },
  },
  {
    path: '/login',
    redirect: LOGIN_PATH,
  },
  {
    path: '/auth/callback',
    name: 'auth-callback',
    component: () => import('../pages/auth-callback.vue'),
    meta: { title: '登录回调', public: true, owner: 'model' },
  },
  {
    path: NO_PROJECT,
    name: 'no-project',
    component: () => import('../pages/no-project.vue'),
    meta: { title: '未加入项目', member: true, owner: 'model' },
  },
  {
    path: SYS_HOME,
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { tenant: true, shell: 'sys', owner: 'model' },
    children: [
      { path: '', name: 'sys-projects', component: () => import('../pages/projects.vue'), meta: { title: '项目管理' } },
      // 「用户管理」是**租户**级那一页（含平台授权码、转让管理员），数据源随模式而异：
      // standard 是本地账号，multi 是组织平台（`api.org.*`）。原来这里错挂了
      // `admin/users.vue`（那是「平台用户」页，调 `/api/platform/users`），
      // 与本条路由的标题「用户管理」和授权码区块都对不上。
      { path: 'users', name: 'sys-users', component: () => import('../pages/org-users.vue'), meta: { title: '用户管理' } },
      { path: 'roles', name: 'sys-roles', component: () => import('../pages/sys/roles.vue'), meta: { title: '角色管理' } },
      { path: 'knowledge', name: 'sys-knowledge', component: () => import('../pages/sys/knowledge.vue'), meta: { title: '知识库' } },
      { path: 'settings', name: 'sys-settings', component: () => import('../pages/sys/settings.vue'), meta: { title: '设置' } },
    ],
  },
  {
    path: MODEL_HOME,
    component: () => import('../layouts/ProjectLayout.vue'),
    meta: { project: true, owner: 'model' },
    children: projectChildren,
  },
  { path: '/app', redirect: MODEL_HOME },
  { path: '/app/model/:pathMatch(.*)*', redirect: (to) => `${MODEL_HOME}/${to.params.pathMatch}` },
  { path: '/app/spec/:pathMatch(.*)*', redirect: (to) => `${MODEL_HOME}/spec/${to.params.pathMatch}` },
  { path: '/app/:pathMatch(.*)*', redirect: (to) => `${MODEL_HOME}/${to.params.pathMatch}` },
  { path: '/w', redirect: MODEL_HOME },
  { path: '/w/:pathMatch(.*)*', redirect: (to) => `${MODEL_HOME}/${to.params.pathMatch}` },
  { path: '/', redirect: MODEL_HOME },
  { path: '/:pathMatch(.*)*', redirect: () => resolveTenantHome() },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

router.beforeEach((to) => {
  const standalone = guestAccess();
  const standard = localLoginRequired();
  const multi = isMultiTenant();
  const loggedIn = standalone || (useRemoteApi() ? Boolean(authToken()) : Boolean(app.currentUserId || app.currentUser));
  const home = () => ({ path: resolveTenantHome() });

  if (standalone && (to.name === 'login' || to.path.startsWith('/select-tenant') || to.path.startsWith('/admin'))) {
    return home();
  }
  if (to.meta.public) {
    if (standalone) return home();
    if (!loggedIn) {
      if (multi && to.name === 'login') {
        openOrgLogin();
        return false;
      }
      return true;
    }
    return home();
  }
  if (!loggedIn && to.name !== 'auth-callback') {
    if (multi) {
      // 把「他本来要去哪」一起带过去：登录（以及必要的选租户）之后组织平台会把他送回
      // 这一页，而不是丢在门户首页 —— 与统一认证的回跳是同一件事（见 openOrgLogin）。
      openOrgLogin(to.fullPath);
      return false;
    }
    if (standard) return { path: LOGIN_PATH };
    return home();
  }
  if ((to.meta.tenant || to.meta.project || to.meta.member) && !app.currentTenantId && !standalone) {
    // platformAdmin 进 SYS_HOME（工作台）不需要 tenantId —— 工作台本身就是"没选租户"的管理员入口。
    if (app.platformAdmin && to.path.startsWith(SYS_HOME)) return true;
    if (multi) {
      // 同一件事：登录页会先让他选租户，再把身份连同这一页送回来。
      openOrgLogin(to.fullPath);
      return false;
    }
    return { path: LOGIN_PATH };
  }
  // 工作台路径一律放行 —— 三种模式都有这一级（见 `config/pages.ts` 的 `ownsProjects`）。
  // 原先这里把 multi 下的工作台整片打回、只留「设置」一个白名单；工作台回到本进程之后，
  // 那个白名单就是多余的了。
  //
  // 工作台里**能做什么**由页面自己收口：multi 不提供项目的新建 / 改 / 删（项目号的
  // 真源在组织平台），用户 / 角色两页由 `sysNav.ts` 按「租户管理员 + 非 standalone」收起。
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
  const layer = typeof to.params.layer === 'string' ? to.params.layer.toUpperCase() : '';
  const title = layer || (to.meta?.title as string | undefined);
  document.title = title ? `${title} · 智仓 DW-AI` : '智仓 DW-AI';
});

export default router;
