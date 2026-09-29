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
    /** 哪套侧栏：平台后台 / 工作台壳 / 项目壳（后两者共用 `SystemLayout`）。 */
    shell?: 'admin' | 'sys' | 'project';
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
      { path: 'nav-items', name: 'admin-nav-items', component: () => import('../pages/admin/nav-items.vue'), meta: { title: '菜单管理' } },
      { path: 'product-roles', name: 'admin-product-roles', component: () => import('../pages/admin/product-roles.vue'), meta: { title: '产品角色' } },
      { path: 'users', name: 'admin-users', component: () => import('../pages/admin/users.vue'), meta: { title: '平台用户' } },
      // 平台后台的「设置」**保持单页**：它本来就只有外观与布局，没有大模型/AI 提示词。
      // 与工作台的「设置 → 外观」共用同一个组件（那页按 `scope` 分叉标题与作用域）。
      { path: 'settings', name: 'admin-settings', component: () => import('../pages/sys/settings-appearance.vue'), meta: { title: '外观与布局' } },
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
      // 顺序跟侧栏（nav_nodes 的 sort_order 35/36）对齐，可读性而已 —— 路由表本身不排序。
      { path: 'modules', name: 'sys-modules', component: () => import('../pages/sys/modules.vue'), meta: { title: '模块管理' } },
      { path: 'compute', name: 'sys-compute', component: () => import('../pages/sys/compute.vue'), meta: { title: '计算资源' } },
      { path: 'knowledge', name: 'sys-knowledge', component: () => import('../pages/sys/knowledge.vue'), meta: { title: '知识库' } },
      // 「设置」拆成三个子页（V28）。路径写成「扁平的一段带斜杠」而不是真嵌套 children ——
      // 与项目壳 `settings/nav` 同一个写法（见该处注释：这样排在 `embed/*` 通配之前）。
      { path: 'settings', redirect: ORG_PAGES.settingsAppearance },
      { path: 'settings/appearance', name: 'sys-settings-appearance', component: () => import('../pages/sys/settings-appearance.vue'), meta: { title: '外观' } },
      { path: 'settings/llm', name: 'sys-settings-llm', component: () => import('../pages/sys/settings-llm.vue'), meta: { title: '大模型' } },
      { path: 'settings/prompts', name: 'sys-settings-prompts', component: () => import('../pages/sys/settings-prompts.vue'), meta: { title: 'AI 提示词' } },
    ],
  },
  /**
   * 门户嵌入页：`/org/embed/{产品}{子应用路径}`。
   *
   * <p>刻意<b>顶层</b>定义、且<b>不</b>带 `meta.tenant`。带上的话会撞上守卫里的
   * `to.meta.tenant && !isRealTenantAdmin` —— 那条只放行 `sys-projects`，
   * 会把<b>普通租户成员</b>静默弹回首页。而「让平台成员用上被嵌入的服务」正是
   * 门户集成的目的，弹回等于整个功能对多数人不可见。`meta.member` 只要求
   * 选定了租户，恰好是这里需要的最小门槛。
   */
  {
    path: '/org/embed/:product/:pathMatch(.*)*',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { title: '产品页面', member: true, shell: 'sys', owner: 'org' },
    children: [{ path: '', name: 'portal-embed', component: () => import('../pages/embed.vue') }],
  },
  /**
   * 菜单扩展的两页（V29）：**入口页**（一张列出被挂菜单的表格）与**外链内嵌页**。
   *
   * <p>与上面的门户嵌入页同理，刻意<b>顶层</b>定义、<b>不</b>带 `meta.tenant` ——
   * 那一条只放行 `sys-projects`，会把普通成员（这两种页面的主要使用者）静默弹回首页。
   * 挂进 `/org/workbench` 的 `children` 里就会继承父路由的 `meta.tenant`，正是这个坑。
   *
   * <p>两条都是**两段**路径（`entry/{id}`），而 `/org/workbench` 的 children 里没有能匹配
   * 它们的项，所以不会被上面那个顶层路由先吃掉。
   */
  {
    path: '/org/workbench/entry/:id',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { title: '目录', member: true, shell: 'sys', owner: 'org' },
    children: [{ path: '', name: 'sys-entry', component: () => import('../pages/entry.vue') }],
  },
  {
    path: '/org/workbench/external/:id',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { title: '外链页面', member: true, shell: 'sys', owner: 'org' },
    children: [{ path: '', name: 'sys-external', component: () => import('../pages/external.vue') }],
  },
  /**
   * 项目壳：`/org/project/{项目code}/*` —— 进项目**之后**那一级。
   *
   * <p>与上面的门户嵌入页同理，刻意<b>不</b>带 `meta.tenant`：那一条只放行 `sys-projects`，
   * 会把普通成员（项目壳的主要使用者）静默弹回首页。`meta.member` 只要求选定了租户。
   *
   * <p>两条路由<b>平铺</b>而不做嵌套 `pathMatch`：嵌套通配下的子路由匹配顺序在
   * vue-router 里很容易写出「刷新能进、点侧栏进不去」这类只在一条路径上出问题的 bug。
   */
  {
    path: '/org/project/:code',
    component: () => import('../layouts/SystemLayout.vue'),
    meta: { member: true, shell: 'project', owner: 'org' },
    children: [
      {
        path: '',
        name: 'project-home',
        // 用组件而不是 redirect：没有配菜单时要**留在原地说明原因**，
        // 弹回工作台会让刚点「进入项目」的人以为自己点错了（见该组件的说明）。
        component: () => import('../pages/project-home.vue'),
      },
      {
        // 壳自己的功能页，**不是**产品页面 —— 所以它排在 `embed/...` 之前，
        // 且路径只占一段（`members`），不与 `embed/{产品}` 争前缀。
        path: 'members',
        name: 'project-members',
        component: () => import('../pages/project-members.vue'),
        meta: { title: '成员管理' },
      },
      {
        // 项目「设置 → 外观」（PRD §4 第 7 条）。与 `members` 一样只占固定的一段，
        // 排在 `embed/...` 之前 —— 否则会先被通配吃掉。
        path: 'settings/nav',
        name: 'project-appearance',
        component: () => import('../pages/project-appearance.vue'),
        meta: { title: '外观' },
      },
      {
        // 入口页与外链内嵌页（V29）。与 `members` / `settings/nav` 一样**排在 `embed/...`
        // 之前**：通配路由会吃掉后面的一切，而这两条是壳自己的页面，不是产品页面。
        // 项目壳不带 `meta.tenant`（见父路由的说明），所以普通成员进得来。
        path: 'entry/:id',
        name: 'project-entry',
        component: () => import('../pages/entry.vue'),
        meta: { title: '目录' },
      },
      {
        path: 'external/:id',
        name: 'project-external',
        component: () => import('../pages/external.vue'),
        meta: { title: '外链页面' },
      },
      {
        path: 'embed/:product/:pathMatch(.*)*',
        name: 'project-embed',
        component: () => import('../pages/embed.vue'),
        meta: { title: '产品页面' },
      },
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
  { path: '/sys/settings', redirect: ORG_PAGES.settingsAppearance },
  { path: '/sys/projects', redirect: SYS_HOME },
  { path: '/org/users', redirect: ORG_PAGES.users },
  { path: '/projects', redirect: SYS_HOME },
  // 下面这几个是**跨进程**跳转（仓建设 / 数据地图各是独立前端）。地址不从环境变量来，
  // 而是 `GET /api/services` 拿到的服务目录，所以必须是同步 redirect ——
  // vue-router 的 redirect 不接受 async 函数（返回的 Promise 会被当成路由位置对象）。
  // 「目录已就绪」由 bootstrapRemote 在挂载前 await servicesReady() 保证。
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
