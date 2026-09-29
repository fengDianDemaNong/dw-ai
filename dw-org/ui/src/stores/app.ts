import { computed, reactive, readonly, ref } from 'vue';
import { message } from 'ant-design-vue';
import { isEmbed } from '../config/runtime';
import type {
  AppState,
  DataGrade,
  Domain,
  FactoryConfig,
  GradeDraft,
  LayerRule,
  Metric,
  Project,
  ProjectMember,
  ServeFolder,
  ServeMetric,
  ServeScope,
  SpecDomainDraft,
  SpecRootDraft,
  Tenant,
  TenantKnowledgeArticle,
  TenantLicense,
  TableSnapshot,
  WarehouseTable,
  WordRoot,
  AiCap,
  EngineKind,
  KnowledgeSection,
} from '../types';
import {
  DEMO_ACCOUNTS_MULTI,
  DEMO_ACCOUNTS_STANDARD,
  ROLE_LABEL,
  roleHas,
  type Perm,
  type Product,
  type ProductModule,
  type ProjectRole,
} from '../config/iam';
import { createSeed, seedServe } from '../mock/seed';
import { GRADE_TEMPLATES } from '../config/grades';
import { hydrateLayerRule, LAYER_TEMPLATES } from '../config/layerPolicies';
import {
  buildDwdDraft,
  buildDwsDraft,
  tableFromDwdDraft,
  tableFromDwsDraft,
} from '../engine/modeling';
import { clusterLogs, scoreCluster } from '../engine/materialize';
import { checkDuplicate } from '../engine/metrics';
import { assessImpact, tableDependentsOf } from '@dw-ai/engine';
import { buildSpecPack, type SpecPack } from '../engine/specIo';
import { api, authToken, setAuthToken, refreshAccess, setIdleTtlSeconds, useRemoteApi, type KnowledgeArticleDto, type NavNodeRow, type ProductService, type Session, type Snapshot } from '../api/client';
import { ADMIN_HOME, APP_HOME, NO_PROJECT, ORG_HOME, SELECT_TENANT, SYS_HOME } from '../config/paths';
import { isEmbeddable } from '../config/products';
import { configureProductOrigins, isOrgUi } from '../config/product';
import { isMultiTenant, isStandalone } from '../config/runtime';
import { loadPlatformAppearance, loadTenantAppearance, loadTenantLlm } from './prefs';

const STORAGE_KEY = 'dw-ai.state.v1';

function defaultIam(): Pick<AppState, 'members' | 'licenses'> {
  return {
    // 产品维是 project_members 主键的一部分，这几行必须写清楚 product ——
    // 缺了它的行在 projectRoleOf(product) 里永远匹配不上，表现为演示模式下
    //「所有人所有产品都没角色」（菜单全灰、进不去项目），而不会报错。
    // 只补 warehouse：standard/standalone 下数据地图整组本就不可见
    //（otherProductsVisible() 为假），补 metadata 行没有界面会用到。
    members: [
      { projectId: 'p-trade', userId: '张三', product: 'warehouse', role: 'admin' },
      { projectId: 'p-trade', userId: '李四', product: 'warehouse', role: 'modeler' },
      { projectId: 'p-empty', userId: '李四', product: 'warehouse', role: 'admin' },
      { projectId: 'p-empty', userId: '张三', product: 'warehouse', role: 'viewer' },
    ],
    licenses: [
      {
        tenantId: 't-xinghe',
        modules: ['warehouse', 'metadata', 'serve', 'quality', 'materialize', 'dev'],
      },
      { tenantId: 't-qihang', modules: ['warehouse', 'metadata'] },
    ],
  };
}

function hydrateIam(state: AppState) {
  const fresh = defaultIam();
  if (!Array.isArray(state.members) || !state.members.length) state.members = fresh.members;
  if (!Array.isArray(state.licenses) || !state.licenses.length) state.licenses = fresh.licenses;
  for (const p of state.projects) {
    // 判重也按 (项目, 产品)：只要没有仓库产品那一行就补，
    // 不能因为「这个项目已经有行（是数据地图的）」就漏掉负责人的 admin
    if (!state.members.some((m) => m.projectId === p.id && m.product === 'warehouse')) {
      state.members.push({
        projectId: p.id,
        userId: p.owner || state.currentUser,
        product: 'warehouse',
        role: 'admin',
      });
    }
  }
  for (const t of state.tenants) {
    if (!state.licenses.some((l) => l.tenantId === t.id)) {
      state.licenses.push({ tenantId: t.id, modules: ['warehouse', 'metadata'] });
    }
  }
}

function emptyRemoteShell(): AppState {
  const seed = createSeed();
  seed.domains = [];
  seed.layerRules = [];
  seed.grades = [];
  seed.roots = [];
  seed.tables = [];
  seed.drafts = [];
  seed.jobs = [];
  seed.qualityRules = [];
  seed.metrics = [];
  seed.queryLogs = [];
  seed.clusters = [];
  seed.recs = [];
  seed.apiCalls = [];
  seed.serveFolders = [];
  seed.serveMetrics = [];
  seed.currentProjectId = null;
  return seed;
}

function load(): AppState {
  if (useRemoteApi()) return emptyRemoteShell();
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as AppState;
      if (parsed?.tenants?.length) {
        if (!Array.isArray(parsed.grades)) parsed.grades = [];
        if (!Array.isArray(parsed.serveFolders) || !parsed.serveFolders.length) {
          const extra = seedServe('p-trade');
          parsed.serveFolders = extra.serveFolders;
          parsed.serveMetrics = extra.serveMetrics;
        }
        if (!Array.isArray(parsed.knowledgeArticles)) parsed.knowledgeArticles = [];
        hydrateIam(parsed);
        return parsed;
      }
    }
  } catch {
    /* ignore */
  }
  return createSeed();
}

const loaded = load();
const state = reactive({
  ...loaded,
  knowledgeArticles: loaded.knowledgeArticles ?? ([] as TenantKnowledgeArticle[]),
  currentUserId: (sessionStorage.getItem('dw-ai.userId') || null) as string | null,
  currentUsername: sessionStorage.getItem('dw-ai.username') || '',
  platformAdmin: false,
  tenantRole: null as string | null,
  sessionAiCaps: null as AiCap[] | null,
});

function persist(scope?: 'spec' | 'tables' | 'drafts' | 'members') {
  if (useRemoteApi()) {
    if (scope === 'spec') syncSpec();
    if (scope === 'tables') syncTables();
    if (scope === 'drafts') syncDrafts();
    return;
  }
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    /* ignore */
  }
}

function remoteErr(e: unknown) {
  message.error(e instanceof Error ? e.message : String(e));
}

function syncSpec() {
  const pid = state.currentProjectId;
  if (!pid) return;
  void api.spec
    .sync(pid, {
      domains: state.domains.filter((d) => d.projectId === pid),
      layers: state.layerRules.filter((r) => r.projectId === pid),
      grades: state.grades.filter((g) => g.projectId === pid),
      roots: state.roots.filter((r) => r.projectId === pid),
    })
    .catch(remoteErr);
}

function syncTables() {
  const pid = state.currentProjectId;
  if (!pid) return;
  void api.tables.sync(pid, state.tables.filter((t) => t.projectId === pid)).catch(remoteErr);
}

function syncDrafts() {
  const pid = state.currentProjectId;
  if (!pid) return;
  void api.drafts.sync(pid, state.drafts.filter((d) => d.projectId === pid)).catch(remoteErr);
}

function applyMe(user: Session['user'] & {
  platformAdmin?: boolean;
  tenantRole?: string | null;
  deployMode?: string;
  aiCaps?: string[];
}) {
  state.currentUserId = user.userId;
  state.currentUser = user.displayName || user.userId;
  state.currentUsername = user.username || state.currentUsername;
  state.platformAdmin = Boolean(user.platformAdmin);
  state.tenantRole = user.tenantRole ?? null;
  state.sessionAiCaps = Array.isArray(user.aiCaps) ? (user.aiCaps as AiCap[]) : null;
  if (user.tenantId) {
    state.currentTenantId = user.tenantId;
    sessionStorage.setItem('dw-ai.tenantId', user.tenantId);
    if (user.tenantCode) sessionStorage.setItem('dw-ai.tenantCode', user.tenantCode);
  }
  sessionStorage.setItem('dw-ai.userId', user.userId);
  if (state.currentUsername) sessionStorage.setItem('dw-ai.username', state.currentUsername);
  if (user.deployMode === 'multi' || user.deployMode === 'standard' || user.deployMode === 'standalone') {
    sessionStorage.setItem('dw-ai.deployMode', user.deployMode);
  }
}

function applySession(s: Session) {
  state.tenants = s.tenants;
  state.projects = s.projects;
  state.members = s.members;
  state.licenses = s.licenses;
  applyMe(s.user);
  const t = state.tenants.find((x) => x.id === state.currentTenantId);
  if (t?.code) sessionStorage.setItem('dw-ai.tenantCode', t.code);
  if (state.currentTenantId) void loadTenantKnowledge(state.currentTenantId);
  // 菜单跟着租户走：登录、切租户（selectRemoteTenant 也走这里）都在这条路上。
  // 放在 SystemLayout.onMounted 是错的 —— 切租户时 layout 不重建，会拿到上一个租户的菜单。
  //
  // 存下 promise 而不是 `void` 丢掉：成员的落地页由菜单决定（见 `resolveTenantHome`），
  // 谁来问落地页谁就得先把这一次拉取等掉，否则会按「菜单还没到」回落成工作台。
  navPending = loadNav();
  servicesPending = loadServices();
}

/** 会话里的租户列表应是当前用户全部可用租户，不是只含当前这一家。 */
export async function refreshMyTenants() {
  if (!useRemoteApi() || !authToken()) return;
  try {
    const list = await api.listAuthTenants();
    if (list.length) state.tenants = list;
  } catch {
    /* 保持现有列表 */
  }
}

function applySnapshot(pid: string, snap: Snapshot) {
  state.domains = state.domains.filter((d) => d.projectId !== pid).concat(snap.domains);
  state.layerRules = state.layerRules.filter((r) => r.projectId && r.projectId !== pid).concat(snap.layers);
  state.grades = state.grades.filter((g) => g.projectId !== pid).concat(snap.grades);
  state.roots = state.roots.filter((r) => r.projectId !== pid).concat(snap.roots);
  state.tables = state.tables.filter((t) => t.projectId !== pid).concat(snap.tables);
  state.drafts = state.drafts.filter((d) => d.projectId !== pid).concat(snap.drafts);
  const uid = state.currentUserId || state.currentUser;
  const self = state.members.find((m) => m.projectId === pid && m.userId === uid);
  const incoming = snap.members ?? [];
  const kept = state.members.filter((m) => m.projectId !== pid);
  state.members =
    self && uid && !incoming.some((m) => m.userId === uid)
      ? kept.concat(incoming, self)
      : kept.concat(incoming);
}

export async function refreshSession() {
  if (!useRemoteApi()) return;
  if (!authToken() && !isStandalone()) return;
  applySession(await api.session());
}

const BOOT_KEY = 'dw-ai.boot';

export async function bootstrapRemote() {
  if (!useRemoteApi()) return;
  try {
    const cfg = await api.authConfig();
    if (cfg.idleTtlSeconds || cfg.refreshTtlSeconds) {
      setIdleTtlSeconds(cfg.idleTtlSeconds || cfg.refreshTtlSeconds || 900);
    }
    if (cfg.runMode || cfg.deployMode) {
      sessionStorage.setItem('dw-ai.deployMode', cfg.runMode || cfg.deployMode || '');
    }
    if (cfg.sessionEpoch) {
      const prev = sessionStorage.getItem(BOOT_KEY);
      if (prev && prev !== cfg.sessionEpoch) {
        if (!(await refreshAccess())) setAuthToken(null);
      }
      sessionStorage.setItem(BOOT_KEY, cfg.sessionEpoch);
    }
    if (!authToken() && !isStandalone()) return;
    applySession(await api.session());
    // 菜单要落地页就位（见 navReady）—— 直接刷新 `/` 时路由守卫马上就会问落地页
    await navReady();
    // 服务目录同理，而且这里**必须**等：`/model`、`/lineage` 那几个路径的 redirect
    // 是 vue-router 的同步 redirect（它不接受 async 函数 —— 返回的 Promise 会被当成
    // 路由位置对象），所以「地址已就绪」这件事只能在挂载前保证，见 servicesReady。
    await servicesReady();
    if (!isStandalone()) {
      await refreshMyTenants();
      // 平台外观只有 admin 壳会读（App.vue 按 meta.shell === 'admin' 取），
      // 而它的读接口是 requirePlatform 的：非管理员来问必得 403，且是每个页面加载一次。
      // 与 loadTenantLlmIfAdmin 同一个理由 —— 不问就不会有那条红字。
      if (state.platformAdmin) await loadPlatformAppearance();
    }
    if (state.currentTenantId) {
      await loadTenantAppearance(state.currentTenantId);
      await loadTenantLlmIfAdmin(state.currentTenantId);
    }
    const savedPid = sessionStorage.getItem('dw-ai.projectId');
    if (
      savedPid &&
      state.projects.some((p) => p.id === savedPid && isProjectActive(p))
    ) {
      state.currentProjectId = savedPid;
    }
    if (state.currentProjectId) {
      const p = state.projects.find((x) => x.id === state.currentProjectId);
      if (p?.code) sessionStorage.setItem('dw-ai.projectCode', p.code);
      // 组织侧没有 `/projects/{id}/snapshot` 这个路由（ProjectService.snapshot 无人映射），
      // 而本 UI 也没有任何地方读 snapshot 状态 —— 照调必然 404。下面 enterProject 那处
      // 早就有同一个 `!isOrgUi()` 判断，这里是漏掉的一份。
      if (!isOrgUi()) applySnapshot(state.currentProjectId, await api.snapshot(state.currentProjectId));
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (/401|unauthorized|session expired|登录已过期|未登录|没有登录/i.test(msg)) {
      setAuthToken(null);
    }
  }
}

export async function loginDev(username: string, password = '') {
  const out = await api.login(username, password);
  setAuthToken(out.token, { refreshToken: out.refreshToken, expiresIn: out.expiresIn });
  sessionStorage.setItem('dw-ai.userId', out.userId || '');
  sessionStorage.setItem('dw-ai.username', username);
  state.currentUserId = out.userId ?? null;
  state.currentUsername = username;
  state.currentUser = out.displayName || username;
  state.platformAdmin = Boolean(out.platformAdmin);
  state.currentProjectId = null;
  if (!out.needSelectTenant && out.tenants?.length === 1) {
    sessionStorage.setItem('dw-ai.tenantId', out.tenants[0].id);
    if (out.tenants[0].code) sessionStorage.setItem('dw-ai.tenantCode', out.tenants[0].code);
    applySession(await api.session());
    await refreshMyTenants();
    await loadTenantAppearance(out.tenants[0].id);
    await loadTenantLlmIfAdmin(out.tenants[0].id);
  } else if (out.platformAdmin) {
    sessionStorage.removeItem('dw-ai.tenantId');
    state.currentTenantId = '';
    try {
      applySession(await api.session());
    } catch {
      /* 平台用户未选租户时 session 仍可用 */
    }
    await loadPlatformAppearance();
  }
  return out;
}

export function loginLocal(username: string, password: string) {
  const list = isMultiTenant() ? DEMO_ACCOUNTS_MULTI : DEMO_ACCOUNTS_STANDARD;
  const acc = list.find((a) => a.username === username && a.password === password);
  if (!acc) throw new Error('用户名或密码错误');
  state.currentUserId = username;
  state.currentUsername = username;
  state.currentUser = username;
  state.platformAdmin = username === 'admin';
  state.currentProjectId = null;
  if (username === 'admin') {
    state.currentTenantId = '';
    state.tenantRole = null;
  } else {
    state.currentTenantId = 't-xinghe';
    state.tenantRole = username === '张三' ? 'admin' : 'member';
    sessionStorage.setItem('dw-ai.tenantId', 't-xinghe');
    sessionStorage.setItem('dw-ai.tenantCode', 'xinghe');
  }
  persist();
  return {
    needSelectTenant: isMultiTenant() && (username === 'admin' || username === '李四'),
    platformAdmin: username === 'admin',
    tenants: username === '李四' ? state.tenants.filter((t) => t.id === 't-xinghe' || t.id === 't-qihang') : state.tenants.filter((t) => t.id === state.currentTenantId),
  };
}

export async function selectRemoteTenant(tenantId: string) {
  const me = await api.selectTenant(tenantId);
  sessionStorage.setItem('dw-ai.tenantId', tenantId);
  applyMe(me);
  applySession(await api.session());
  await refreshMyTenants();
  await loadTenantAppearance(tenantId);
  await loadTenantLlmIfAdmin(tenantId);
  return me;
}

/**
 * 拉租户的 LLM 配置，但只对租户管理员。
 *
 * <p>组织侧那个接口是管理员专属（`TenantAdminController.llm`）。成员登录时也会走到
 * 这几个调用点，不判一下就是一条必然 403 的请求 —— 控制台上是红的，排查别的问题时
 * 会被当成线索；而且成员本来也用不到 LLM 配置（设置页是管理员专属路由）。
 *
 * <p>判在调用方而不是 {@code prefs.ts} 里：那边的 `state` 是它自己的一份，没有
 * `tenantRole`；在那边判会永远提前返回，把管理员自己的配置页弄坏。
 */
async function loadTenantLlmIfAdmin(tenantId: string | null | undefined) {
  if (state.tenantRole !== 'admin') return;
  await loadTenantLlm(tenantId);
}

export function logoutRemote() {
  const rt = sessionStorage.getItem('dw-ai.refreshToken');
  if (rt && useRemoteApi()) void api.logout(rt);
  setAuthToken(null);
  sessionStorage.removeItem('dw-ai.tenantId');
  sessionStorage.removeItem('dw-ai.projectId');
  sessionStorage.removeItem('dw-ai.userId');
  sessionStorage.removeItem('dw-ai.username');
  state.currentUserId = null;
  state.currentUsername = '';
  state.currentUser = '';
  state.currentTenantId = '';
  state.currentProjectId = null;
  state.platformAdmin = false;
  state.tenantRole = null;
  navTree.value = [];
}

export function logout() {
  logoutRemote();
}

persist();

function tablesInProject(pid: string | null): WarehouseTable[] {
  if (!pid) return [];
  return state.tables.filter((t) => t.projectId === pid);
}

function tableRefsInProject(pid: string | null) {
  const domains = new Set<string>();
  const layers = new Set<string>();
  const grades = new Set<string>();
  for (const t of tablesInProject(pid)) {
    if (t.domain) domains.add(t.domain);
    if (t.layer) layers.add(t.layer);
    if (t.grade) grades.add(t.grade);
    for (const c of t.columns) {
      if (c.grade) grades.add(c.grade);
    }
  }
  return { domains, layers, grades };
}

function gradeUsedByTables(pid: string | null, code: string): boolean {
  return tablesInProject(pid).some((t) => t.grade === code || t.columns.some((c) => c.grade === code));
}

export const app = readonly(state);

/**
 * 门户菜单**树**（org 自有节点与各个产品挂进来的节点在同一棵树上），
 * 服务端已按租户许可与**这个人**的角色裁过一遍（见 `NavNodeService.treeFor`）。
 *
 * <p>刻意<b>不</b>放进持久化的 {@code AppState}：它是服务端配置的投影，
 * 存进 localStorage 只会让旧值盖住新配置。跟着租户走
 * （{@code applySession} 里加载、{@code leaveTenant} 里清空）。
 *
 * <p><b>V23 起只有这一份</b>：以前这里是 `navItems`（菜单）+ `navGroups`（分组元数据）
 * 两个 ref、两个请求；分组并进菜单表之后，组间顺序与空组策略就是树里那一行自己的
 * 字段（`sortOrder` / `emptyPolicy`），没有第二份要分开拉的东西。
 *
 * <p>整棵树**不按壳预先切开**：切开是渲染那一步的事（`config/sysNav.ts` 的
 * `toNavItems` 按 `ctx.scope` 取顶层），在这里再存两份只会让「哪份是权威」多一个答案。
 */
export const navTree = ref<NavNodeRow[]>([]);

/**
 * 产品服务目录（`GET /api/services`）：产品码 → 它自己的站点根。
 *
 * <p>与菜单一样是「服务端配置的投影」，同样不持久化、跟着租户走。
 * 与菜单分开拉是因为两者的失败后果不同：菜单没了只是侧栏少几项，
 * 目录没了「进入项目」会提示未配置地址 —— 合成一个请求会让这两种症状互相掩盖。
 */
const productServices = ref<ProductService[]>([]);

/** 最近一次服务目录拉取；决定跳转前要等它落定，见 {@link servicesReady}。 */
let servicesPending: Promise<void> = Promise.resolve();

/**
 * 把服务目录接给 `config/product.ts` —— 跨服务跳转的地址只从这一处来。
 *
 * <p>方向必须是 store → config：反过来会成环，因为 config/product.ts 提供
 * `isOrgUi`，本模块要用它。resolver 每次调用都读最新的 ref，所以注册一次就够。
 */
configureProductOrigins(
  (product) =>
    (productServices.value.find((s) => s.product === product)?.frontendUrl ?? '').trim().replace(/\/$/, ''),
  () => productServices.value
);

/** 最近一次菜单拉取；落地页要等它落定，见 {@link navReady}。 */
let navPending: Promise<void> = Promise.resolve();

/**
 * 等最近一次菜单拉取结束。
 *
 * <p>给「决定成员去哪儿」的调用点用：落地页要看菜单（第一个能嵌的产品页面），
 * 而菜单是登录时异步拉的。不等的话会按空菜单回落成工作台 —— 表现是
 * 「有时候进得去产品壳，有时候进不去」，取决于网络快慢。
 */
export function navReady(): Promise<void> {
  return navPending;
}

/**
 * 拉一次菜单树。
 *
 * <p>失败时留空而<b>不</b>抛：侧栏是每个页面都要画的东西，让「菜单接口不通」
 * 把整个壳带下水不划算 —— 用户仍能用「系统管理」那一支（它是前端硬编码的平台壳菜单，
 * 或已进库的 org 自有节点）。留一条 warn 是因为这个失败从界面上看与
 * 「管理员还没配菜单」完全一样。
 *
 * <p>以前这里是一个 `Promise.allSettled([nav(), navGroups()])` —— 两个请求各自兜底。
 * V23 把分组并进菜单表之后只剩一个请求，那层「一坏俱坏」的顾虑也随之消失。
 */
export async function loadNav() {
  if (!useRemoteApi() || !authToken()) {
    navTree.value = [];
    return;
  }
  try {
    navTree.value = await api.nav();
  } catch (e) {
    console.warn('[dw-ai] 拉取门户菜单失败，侧栏将只显示平台/系统菜单:', e);
    navTree.value = [];
  }
}

/**
 * 重新拉一次菜单，并把这次拉取登记到 {@link navReady} 上。
 *
 * <p>`loadNav()` 自己只写 `navTree`、不碰 `navPending` —— 「登记」是调用方的语义：
 * 只有**改变了菜单结论**的动作才该登记（登录、离开项目、切换项目），
 * 否则会把别人「等菜单就位」的等待接到一次与它无关的拉取上。
 *
 * <p>切换项目要用它：菜单的结论依赖当前项目（服务端按这个人**在这个项目下**的角色
 * 过滤权限词），而切换是**就地**完成的 —— 页面组件被 vue-router 复用、不会重新
 * `onMounted`，各处兜底的那次 `loadNav()` 不会替我们跑（见 `ProjectSwitcher.onPick`）。
 */
export function reloadNav(): Promise<void> {
  navPending = loadNav();
  return navPending;
}

/**
 * 等最近一次服务目录拉取结束。
 *
 * <p>「进入项目」要用目录里的地址，而目录是登录后才异步拉的。不等的话，
 * 刷新页面后立刻点「进入项目」会误报「未配置前端地址」—— 与真的没配表现完全一样。
 */
export function servicesReady(): Promise<void> {
  return servicesPending;
}

/**
 * 拉一次产品服务目录。
 *
 * <p>失败时留空而不抛，理由同 {@link loadNav}：让「目录接口不通」把整个壳带下水
 * 不划算。留一条 warn 是因为这个失败从界面上看与「管理员还没登记前端地址」一样。
 */
export async function loadServices() {
  if (!useRemoteApi() || !authToken()) {
    productServices.value = [];
    return;
  }
  try {
    productServices.value = await api.productServices();
  } catch (e) {
    console.warn('[dw-ai] 拉取产品服务目录失败，「进入项目」会提示未配置前端地址:', e);
    productServices.value = [];
  }
}

export const currentTenant = computed(() => state.tenants.find((t) => t.id === state.currentTenantId));

function isProjectActive(p: Project) {
  return (p.status ?? 'active') !== 'disabled';
}

export const tenantProjects = computed(() => {
  const list = state.projects.filter((p) => p.tenantId === state.currentTenantId);
  if (state.tenantRole === 'admin') return list;
  const uid = state.currentUserId || state.currentUser;
  return list.filter(
    (p) => isProjectActive(p) && state.members.some((m) => m.projectId === p.id && m.userId === uid)
  );
});

export const currentProject = computed(() =>
  state.projects.find((p) => p.id === state.currentProjectId)
);

function inProject<T extends { projectId: string }>(list: T[]): T[] {
  return list.filter((x) => x.projectId === state.currentProjectId);
}

export const projectDomains = computed(() => inProject(state.domains));
export const projectGrades = computed(() =>
  inProject(state.grades).slice().sort((a, b) => a.level - b.level)
);
export const projectRoots = computed(() => inProject(state.roots));
export const projectTables = computed(() => inProject(state.tables));
export const projectDrafts = computed(() => inProject(state.drafts));
export const projectJobs = computed(() => inProject(state.jobs));
export const projectMetrics = computed(() => inProject(state.metrics));
export const projectLogs = computed(() => inProject(state.queryLogs));
export const projectClusters = computed(() => inProject(state.clusters));
export const projectRecs = computed(() => inProject(state.recs));
export const projectApiCalls = computed(() => inProject(state.apiCalls));
export const currentUser = computed(() => state.currentUser);

/**
 * 某产品下，当前人在本项目的角色。
 *
 * <p>加了产品维之后「我在本项目的角色」不再唯一：同一个「项目管理员」在仓建设是
 * 规范管理员、在数据地图是目录管理员。所以 product 是必填参数 —— 没有全局默认值，
 * 猜错了就是拿 A 产品的角色去判 B 产品的权。
 *
 * <p>{@code projectId} 缺省时看当前选中的项目；显式传进来是为了判断项目列表里
 * 「那一行」的按钮状态 —— 那时还没 enterProject，currentProjectId 不是它。
 */
export function projectRoleOf(product: Product, projectId?: string): ProjectRole | undefined {
  const pid = projectId ?? state.currentProjectId;
  if (!pid) return undefined;
  const uid = state.currentUserId || state.currentUser;
  const found = state.members.find(
    // 缺 product 的行按仓建设算 —— 与 dw-model/ui 的同名判断保持逐字一致：
    // 同一份后端数据在两个前端里必须给出同一个答案，否则「工作台说你是管理员、
    // 仓建设说你不是」这种故障没有任何单侧日志能看出来。类型上 product 本来就
    // 是可选的（授权码访客的合成行、还没跟上产品维的旧接口不一定带得回来）。
    (m) => m.projectId === pid && m.userId === uid && (m.product ?? 'warehouse') === product
  )?.role;
  if (found) return found;
  // 平台持码进入：会话里合成 viewer，拉快照时可能被冲掉，只读权限仍按访客算。
  // 授权码的 project_roles 不带产品维（后端 roleOf 同样这么兜底），两个产品一视同仁。
  if (state.platformAdmin && state.currentTenantId && state.tenantRole !== 'admin') return 'viewer';
  return undefined;
}

/**
 * 按项目 code 找项目 —— 项目壳的地址里只有 code（`/org/project/{code}/...`）。
 *
 * <p><b>为什么不读 {@link state}.currentProjectId</b>：它来自 sessionStorage
 * （`enterProject` 写入、`bootstrapRemote` 恢复），与地址里的 code **可能不是同一个
 * 项目** —— 粘贴地址、前进/后退、另开标签页进来时都会不一致。项目壳里判权与取数
 * 一律按地址里的 code 解析：地址才是这个页面的唯一真相，sessionStorage 只是「上次
 * 去过哪儿」的记忆。
 *
 * <p>找不到返回 `undefined`（项目被删、code 打错）—— 由调用方决定怎么呈现，
 * 不在这里兜一个「第一个项目」，那会让打错的地址静默变成另一个项目的页面。
 */
export function projectByCode(code: string): Project | undefined {
  if (!code) return undefined;
  return state.projects.find((p) => p.code === code);
}

/** 仓建设下的角色 —— 工作台的「当前角色」标签与几个写权限都看它。 */
export const currentProjectRole = computed<ProjectRole | undefined>(() => projectRoleOf('warehouse'));

export const sessionAccount = computed(() =>
  state.currentUserId
    ? {
        id: state.currentUserId,
        username: state.currentUsername || state.currentUserId,
        displayName: state.currentUser,
        platformAdmin: state.platformAdmin,
        status: 'active' as const,
      }
    : null
);

export function selectableTenants() {
  return state.tenants.filter((t) => t.status !== 'disabled');
}

export function tenantRoleOf(_userId?: string): 'admin' | 'member' | null {
  return state.tenantRole === 'admin' || state.tenantRole === 'member' ? state.tenantRole : null;
}

export const isPlatformAdmin = computed(() => state.platformAdmin);
export const isRealTenantAdmin = computed(() => state.tenantRole === 'admin');
export const isTenantAdmin = computed(() => state.tenantRole === 'admin');
export const canWriteSpec = computed(() => can('warehouse', 'spec:write'));
export const canWriteModel = computed(() => can('warehouse', 'model:write'));
export const canPublishModel = computed(() => can('warehouse', 'model:publish'));

/**
 * 「返回工作台」这个入口对当前用户是否成立。
 *
 * <p>项目壳 bar 里的租户名（可点回工作台）与用户面板下拉里的那一条共用这一份判据。
 * 原先只写在 `UserPanel.vue` 里，bar 也要用 —— 各写一遍迟早走散。
 *
 * <p>multi 下人人都有：项目壳路由的 meta 是 member（见 `router/index.ts` 的 `/org/project/:code`），
 * 普通成员本来就能进去 —— 进得去就得能出来。standard 仍只给租户管理员：那一档里
 * 工作台是管理界面。判据与 model 的 `config/pages.ts` 的 `canBackToWorkbench` 同义。
 *
 * <p>原先这里是 `isRealTenantAdmin && project`，相当于<b>所有</b>模式都只给管理员。
 * 侧栏那条「返回工作台」撤掉之后（见 V24 迁移），过严的判据会把普通成员困在项目里。
 */
export const canBackHome = computed(
  () => Boolean(currentProject.value) && (isMultiTenant() || isRealTenantAdmin.value)
);

/**
 * 项目壳默认落在哪一页：第一条**能嵌**且**已登记页面地址**的项目菜单。
 *
 * <p>不取「第一条项目菜单」：菜单里可能有仓建设这种还没有嵌入接收端的产品，
 * 选它就只能整页跳走，等于又回到老路。一条都没有（没配项目菜单 / 都没登记页面地址）
 * 时返回 `null`，由调用方回落工作台 —— 那里至少有「项目管理」可用，比空白页好。
 */
export function projectMenuHref(code: string): string | null {
  const menu = findEmbeddableNode(navTree.value.filter((n) => n.scope === 'project'));
  if (!menu) return null;
  const sub = menu.path.startsWith('/') ? menu.path : `/${menu.path}`;
  return `${ORG_HOME}/project/${encodeURIComponent(code)}/embed/${menu.product}${sub}`;
}

/**
 * 树里第一个「能嵌、已登记页面地址、并且自己是个可点项」的产品节点（深度优先）。
 *
 * <p>三个判据缺一不可：不能嵌的（如数据质量）只能整页跳走，不是壳里的落点；
 * 没登记地址的点了只会弹回首页；`path` 为空的是目录节点，它自己不是一个页面 ——
 * 前两个判据在旧的平铺列表上是够的，因为那时每一行都是一条菜单项。
 */
function findEmbeddableNode(nodes: NavNodeRow[]): NavNodeRow | null {
  for (const n of nodes) {
    if (n.product && n.path && n.frontendUrl && isEmbeddable(n.product)) return n;
    const hit = findEmbeddableNode(n.children ?? []);
    if (hit) return hit;
  }
  return null;
}

/**
 * 普通租户成员进组织平台后的落地页 —— 项目壳。
 *
 * <p>落点是<b>项目壳</b>，不是 `APP_HOME`（=`/model`）也不是某一个产品页面：
 * `/model` 是整页跳到仓建设自己的站点，那样一来「平台提供壳子、把别的服务嵌进去」
 * 对绝大多数用户（非管理员）根本不可见；而直接落到某个产品页面则绕过了项目这一层，
 * 需求要的正是「项目也是壳，各服务的菜单挂进项目里」。
 *
 * <p>没有项目码、或项目壳里一条能嵌的菜单都没有时回落工作台 —— 工作台的
 * 「项目管理」至少是可用的落点，比把成员丢进一个空的项目壳好。这与项目壳首页
 * 对**主动点进来**的人显示「还没配置页面」是两回事：那是明确动作之后的结果，
 * 这是登录后无处可去。
 */
function memberHome(): string {
  const code = sessionStorage.getItem('dw-ai.projectCode') ?? '';
  if (!code || !projectMenuHref(code)) return SYS_HOME;
  return `${ORG_HOME}/project/${encodeURIComponent(code)}`;
}

export function resolveTenantHome(): string {
  if (state.platformAdmin && !state.currentTenantId) return ADMIN_HOME;
  if (isOrgUi()) {
    if (!state.currentTenantId) return SELECT_TENANT;
    if (state.tenantRole === 'admin') return SYS_HOME;
    const list = tenantProjects.value;
    if (!list.length) return NO_PROJECT;
    const keep = list.find((p) => p.id === state.currentProjectId);
    const id = keep?.id ?? list[0].id;
    if (state.currentProjectId !== id) {
      state.currentProjectId = id;
      sessionStorage.setItem('dw-ai.projectId', id);
    }
    const p = list.find((x) => x.id === id);
    if (p?.code) sessionStorage.setItem('dw-ai.projectCode', p.code);
    return memberHome();
  }
  if (state.tenantRole === 'admin') return SYS_HOME;
  const list = tenantProjects.value;
  if (!list.length) return NO_PROJECT;
  const keep = list.find((p) => p.id === state.currentProjectId);
  const id = keep?.id ?? list[0].id;
  if (state.currentProjectId !== id) {
    state.currentProjectId = id;
    sessionStorage.setItem('dw-ai.projectId', id);
  }
  return APP_HOME;
}

export const currentRoleLabel = computed(() =>
  currentProjectRole.value ? ROLE_LABEL[currentProjectRole.value] : '未加入项目'
);

/**
 * 当前用户在本项目的角色，够不够该产品下的某个权限。
 *
 * <p>产品码必须显式给：工作台要同时判断仓建设与数据地图能不能进
 * （见 `pages/projects.vue` 的 `go()`），这里给个隐含默认值只会帮倒忙。
 *
 * <p>`projectId` 可选，透传给 {@link projectRoleOf}：缺省看当前选中的项目，
 * 项目壳里则必须显式传 —— 它要判的是**地址里那个项目**，不是 sessionStorage
 * 记着的那个（理由见 {@link projectByCode}）。
 */
export function can(product: Product, perm: Perm, projectId?: string): boolean {
  if (isStandalone()) return true;
  return roleHas(product, projectRoleOf(product, projectId), perm);
}

export function hasModule(mod: ProductModule): boolean {
  if (isStandalone()) return mod === 'warehouse' || mod === 'metadata';
  const lic = state.licenses.find((l) => l.tenantId === state.currentTenantId);
  return Boolean(lic?.modules.includes(mod));
}

/**
 * 能不能进某个产品 —— 工作台这一层对「服务展示」的判断。
 *
 * <p>两道门都要过：租户开通了这个产品（平台许可），并且我本人在这项目下被派了这个
 * 产品的角色。只判前一道会给出「点进去被 403 顶回来」的入口，只判后一道则会在未开通
 * 的产品上留一个永远进不去的按钮。standalone 没有登录也没有角色，两道门都不适用。
 */
export function canEnterProduct(product: Product, projectId?: string): boolean {
  if (isStandalone()) return true;
  return hasModule(product) && projectRoleOf(product, projectId) !== undefined;
}

/** 会话里的 aiCaps 已是许可 ∩ 授权码交集；空数组 = 未开通。本地演示无列表时，仓建设即三项全开。 */
export function hasAiCap(cap: AiCap): boolean {
  if (!hasModule('warehouse')) return false;
  const lic = state.licenses.find((l) => l.tenantId === state.currentTenantId);
  const caps = lic?.aiCaps ?? state.sessionAiCaps;
  if (caps == null) return true;
  return caps.includes(cap);
}

export const projectMemberList = computed(() =>
  state.members.filter((m) => m.projectId === state.currentProjectId)
);

export function switchUser(userId: string) {
  state.currentUser = userId;
  persist();
}

export function setMemberRole(userId: string, role: ProjectRole) {
  if (!can('warehouse', 'iam:member')) {
    message.error('没有成员管理权限');
    return;
  }
  const row = state.members.find((m) => m.projectId === state.currentProjectId && m.userId === userId);
  if (!row) {
    if (!state.currentProjectId) return;
    state.members.push({ projectId: state.currentProjectId, userId, role });
  } else {
    row.role = role;
  }
  persist();
  if (useRemoteApi() && state.currentProjectId) {
    void api.putMember(state.currentProjectId, userId, role).catch(remoteErr);
  }
  message.success(`${userId} 现为${ROLE_LABEL[role]}`);
}

function deny(perm: Perm): boolean {
  if (can('warehouse', perm)) return false;
  message.error('当前角色无权执行此操作');
  return true;
}
export const projectServeFolders = computed(() => inProject(state.serveFolders));
export const projectServeMetrics = computed(() => inProject(state.serveMetrics));

export function visibleServeFolders(scope: ServeScope): ServeFolder[] {
  return projectServeFolders.value.filter((f) => {
    if (f.scope !== scope) return false;
    if (scope === 'personal') return f.owner === state.currentUser;
    return true;
  });
}

export function visibleServeMetrics(scope: ServeScope): ServeMetric[] {
  return projectServeMetrics.value.filter((m) => {
    if (m.scope !== scope) return false;
    if (scope === 'personal') return m.owner === state.currentUser;
    if (scope === 'public') return m.status === 'published' || m.status === 'review';
    return m.status !== 'draft';
  });
}
export const projectQuality = computed(() =>
  state.qualityRules.filter((q) => projectTables.value.some((t) => t.name === q.table))
);

export const projectLayerRules = computed(() => {
  const pid = state.currentProjectId;
  return state.layerRules.filter((r) => r.projectId === pid);
});

export async function createTenant(input: { code: string; name: string; owner?: string }): Promise<Tenant> {
  const code = input.code.trim();
  const name = input.name.trim();
  if (useRemoteApi()) {
    const t = await api.platform.createTenant({ code, name, owner: input.owner || state.currentUser });
    if (!state.tenants.some((x) => x.id === t.id)) state.tenants.push(t);
    message.success(`租户「${t.name}」已创建，登录时选它即可进入`);
    return t;
  }
  if (state.tenants.some((x) => x.code === code)) {
    throw new Error('租户编码已存在');
  }
  const t: Tenant = {
    id: `t-${code}`,
    code,
    name,
    owner: input.owner || state.currentUser,
  };
  state.tenants.push(t);
  state.licenses.push({ tenantId: t.id, modules: ['warehouse', 'metadata', 'serve', 'quality', 'materialize', 'dev'] });
  persist();
  message.success(`租户「${t.name}」已创建`);
  return t;
}

export async function switchTenant(tenantId: string) {
  if (useRemoteApi()) {
    try {
      await selectRemoteTenant(tenantId);
      return true;
    } catch (e) {
      remoteErr(e);
      return false;
    }
  }
  state.currentTenantId = tenantId;
  state.currentProjectId = null;
  persist();
  return true;
}

export async function enterProject(projectId: string) {
  const p = state.projects.find((x) => x.id === projectId);
  if (!p) return;
  if (!isProjectActive(p)) {
    message.error('项目已停用，不能进入');
    return;
  }
  state.currentTenantId = p.tenantId;
  state.currentProjectId = p.id;
  sessionStorage.setItem('dw-ai.tenantId', p.tenantId);
  sessionStorage.setItem('dw-ai.projectId', p.id);
  sessionStorage.setItem('dw-ai.projectCode', p.code);
  const t = state.tenants.find((x) => x.id === p.tenantId);
  if (t?.code) sessionStorage.setItem('dw-ai.tenantCode', t.code);
  persistRoles(p.id);
  if (useRemoteApi()) {
    if (!isOrgUi()) applySnapshot(p.id, await api.snapshot(p.id));
    return;
  }
  persist();
}

/**
 * 把「我在这项目下各产品的角色」写进 sessionStorage，供下游服务的 `#boot=` 带走
 * （见 `config/product.ts` 的 `bootPayload`）。
 *
 * <p>下游只拿它把菜单先画对 —— 真正的门禁在各服务后端，它们各自调组织平台的
 * `authz/check` 兜底。所以这一份即便被改，后果也只是看到一个点不进去的入口。
 */
function persistRoles(projectId: string) {
  const uid = state.currentUserId || state.currentUser;
  const roles: Record<string, string> = {};
  for (const m of state.members) {
    if (m.projectId === projectId && m.userId === uid && m.product) roles[m.product] = m.role;
  }
  sessionStorage.setItem('dw-ai.roles', JSON.stringify(roles));
}

/** 成员直接落到 /w 时也要拉快照，否则建模侧栏没有分层。 */
export async function ensureProjectSnapshot() {
  const pid = state.currentProjectId;
  if (!useRemoteApi() || !pid) return;
  if (state.layerRules.some((r) => r.projectId === pid) && state.tables.some((t) => t.projectId === pid)) {
    return;
  }
  await enterProject(pid);
}

export function leaveProject() {
  state.currentProjectId = null;
  sessionStorage.removeItem('dw-ai.projectId');
  sessionStorage.removeItem('dw-ai.projectCode');
  // 菜单的**结论依赖当前项目**（服务端按这个人在这项目下的角色过滤权限词），
  // 所以离开项目后必须重拉：留在手上的那份是按上一个项目的角色算的。
  // 进项目那一侧不用在这里补 —— 项目壳/嵌入页 onMounted 都会 `loadNav()`。
  navPending = loadNav();
  persist();
}

/** 回到平台后台：清掉当前租户，平台壳不切换租户。 */
export function leaveTenant() {
  // 被框起来的那一页（入口页的 Tab 里嵌的 org 页面）不该清租户上下文：下面这几个
  // `dw-ai.*` 是**共享** sessionStorage —— 同源 iframe 与父窗口用的是同一份。子帧清了，
  // 父窗口的租户跟着没了（route 守卫的 `meta.admin` 分支会走到这里）。
  if (isEmbed()) return;
  state.currentTenantId = '';
  state.currentProjectId = null;
  state.tenantRole = null;
  // 平台壳的侧栏是 buildAdminNav()，产品菜单与分组元数据都不该跟过去
  navTree.value = [];
  sessionStorage.removeItem('dw-ai.tenantId');
  sessionStorage.removeItem('dw-ai.projectId');
  sessionStorage.removeItem('dw-ai.tenantCode');
  sessionStorage.removeItem('dw-ai.projectCode');
  persist();
}

/**
 * 建完项目，把创建者自己在**本租户已开通的每个产品**下记成管理员。
 *
 * <p>这是对组织侧那一份的乐观复刻：组织就是这么派的（`AccessService.licensedProducts`
 * 逐个循环），拉一次会话就会用权威数据覆盖这里。两条都必须对上，少一条的表现都是
 * 「刚建完项目，进入项目按钮是灰的，刷新一下才好」（刷新走 `applySession`，拿的正是
 * 组织那份数据，所以看着像刷新修好了）：
 *
 * <ul>
 *   <li><b>产品维按许可逐个补</b>：只补 `warehouse` 的话，只开通数据地图的租户里，
 *       创建者在新项目下没有数据地图角色，`canEnterProduct('metadata')` 的第二道门
 *       （我在本项目有该产品角色）就过不去。</li>
 *   <li><b>userId 取 id 而不是显示名</b>：`projectRoleOf` 查的是
 *       `state.currentUserId || state.currentUser`，而真机上 `currentUser` 是显示名
 *       （「平台管理员」这种），补出来的行永远匹配不上 —— 补了等于没补。</li>
 * </ul>
 *
 * <p>未开通的产品不补：补了也不会让按钮亮（第一道门 `hasModule` 照样是假），
 * 只会留下一条「看着有权限、点进去被拒」的脏行。
 */
function grantSelfProjectAdmin(projectId: string) {
  const licensed = state.licenses.find((l) => l.tenantId === state.currentTenantId)?.modules ?? [];
  const uid = state.currentUserId || state.currentUser;
  for (const product of ['warehouse', 'metadata'] as const) {
    if (!licensed.includes(product)) continue;
    const dup = state.members.some(
      (m) => m.projectId === projectId && m.userId === uid && m.product === product
    );
    if (!dup) state.members.push({ projectId, userId: uid, product, role: 'admin' });
  }
}

export async function createProject(input: {
  code: string;
  name: string;
  description: string;
  owner: string;
  adminUserId?: string;
  bootstrapSpec?: boolean;
  engines?: EngineKind[];
}): Promise<Project> {
  if (useRemoteApi()) {
    const payload = {
      ...input,
      owner: input.adminUserId || input.owner,
      adminUserId: input.adminUserId,
      engines: input.engines,
    };
    const project = isOrgUi()
      ? await api.org.createProject(state.currentTenantId, payload)
      : await api.createProject(payload);
    state.projects.push(project);
    grantSelfProjectAdmin(project.id);
    message.success(
      input.bootstrapSpec
        ? `项目「${project.name}」已创建，并导入通用规范（分层 + 四级等级 + 基础词根）`
        : `项目「${project.name}」已创建`
    );
    return project;
  }
  const project: Project = {
    id: `p-${Date.now()}`,
    tenantId: state.currentTenantId,
    code: input.code,
    name: input.name,
    description: input.description,
    owner: input.owner || state.currentUser,
    createdAt: new Date().toISOString().slice(0, 10),
    status: 'active',
    engines: input.engines ?? [],
  };
  state.projects.push(project);
  grantSelfProjectAdmin(project.id);
  const techTime: WordRoot[] = state.roots
    .filter((r) => r.projectId === 'p-trade' && r.kind !== 'biz')
    .map((r) => ({ ...r, id: `${r.id}-${project.id}`, projectId: project.id }));
  state.roots.push(...techTime);
  if (input.bootstrapSpec) bootstrapProjectSpec(project.id);
  persist();
  message.success(
    input.bootstrapSpec
      ? `项目「${project.name}」已创建，并导入通用规范（分层 + 四级等级 + 基础词根）`
      : `项目「${project.name}」已创建`
  );
  return project;
}

export async function patchProject(projectId: string, body: Record<string, unknown>): Promise<Project | undefined> {
  if (useRemoteApi()) {
    const updated = isOrgUi()
      ? await api.org.patchProject(state.currentTenantId, projectId, body)
      : await api.patchProject(projectId, body);
    const i = state.projects.findIndex((p) => p.id === projectId);
    if (i >= 0) state.projects[i] = { ...state.projects[i], ...updated };
    return updated;
  }
  const p = state.projects.find((x) => x.id === projectId);
  if (!p) return undefined;
  if (typeof body.name === 'string') p.name = body.name;
  if (typeof body.description === 'string') p.description = body.description;
  if (typeof body.owner === 'string') p.owner = body.owner;
  if (typeof body.status === 'string') p.status = body.status;
  if (Array.isArray(body.engines)) p.engines = body.engines as EngineKind[];
  persist();
  return p;
}

export async function setProjectEngines(projectId: string, engines: EngineKind[]) {
  return patchProject(projectId, { engines });
}

function toKnowledgeArticle(d: KnowledgeArticleDto): TenantKnowledgeArticle {
  return {
    id: d.id,
    tenantId: d.tenantId,
    engine: d.engine as EngineKind,
    title: d.title,
    summary: d.summary ?? '',
    body: d.body ?? '',
    sourceUrl: d.sourceUrl,
    sourceLabel: d.sourceLabel,
    sections: ((d.sections ?? []) as unknown as KnowledgeSection[]),
    notes: d.notes,
    importedAt: d.importedAt ?? '',
    importedBy: d.importedBy ?? '',
  };
}

export async function loadTenantKnowledge(tenantId?: string) {
  const tid = tenantId || state.currentTenantId;
  if (!useRemoteApi() || !tid) return;
  try {
    state.knowledgeArticles = (await api.org.knowledge(tid)).map(toKnowledgeArticle);
  } catch {
    /* 保持现有列表 */
  }
}

export const tenantKnowledgeArticles = computed(() =>
  (state.knowledgeArticles ?? []).filter((a) => a.tenantId === state.currentTenantId)
);

export async function importKnowledgeFile(
  text: string,
  filename: string,
  mode: 'merge' | 'replace-engine' = 'merge'
) {
  if (!state.currentTenantId) {
    message.error('请先进入组织');
    return 0;
  }
  if (useRemoteApi()) {
    const r = await api.org.importKnowledge(state.currentTenantId, { text, filename, mode });
    await loadTenantKnowledge(state.currentTenantId);
    message.success(`已导入 ${r.articles.length} 篇`);
    return r.articles.length;
  }
  message.warning('本地演示未持久化导入，请接上 API');
  return 0;
}

export async function removeImportedArticle(articleId: string, engine: EngineKind) {
  if (!state.currentTenantId) return;
  if (useRemoteApi()) {
    await api.org.deleteKnowledge(state.currentTenantId, engine, articleId);
    await loadTenantKnowledge(state.currentTenantId);
    message.success('已删除导入篇');
    return;
  }
  state.knowledgeArticles = (state.knowledgeArticles ?? []).filter(
    (a) => !(a.tenantId === state.currentTenantId && a.engine === engine && a.id === articleId)
  );
  persist();
  message.success('已删除导入篇');
}

export async function removeProject(projectId: string) {
  if (useRemoteApi()) {
    if (isOrgUi()) await api.org.deleteProject(state.currentTenantId, projectId);
    else await api.deleteProject(projectId);
  }
  state.projects = state.projects.filter((p) => p.id !== projectId);
  state.members = state.members.filter((m) => m.projectId !== projectId);
  state.domains = state.domains.filter((d) => d.projectId !== projectId);
  state.layerRules = state.layerRules.filter((r) => r.projectId !== projectId);
  state.grades = state.grades.filter((g) => g.projectId !== projectId);
  state.roots = state.roots.filter((r) => r.projectId !== projectId);
  state.tables = state.tables.filter((t) => t.projectId !== projectId);
  state.drafts = state.drafts.filter((d) => d.projectId !== projectId);
  if (state.currentProjectId === projectId) {
    state.currentProjectId = null;
    sessionStorage.removeItem('dw-ai.projectId');
  }
  persist();
}

function bootstrapProjectSpec(projectId: string) {
  if (!state.layerRules.some((r) => r.projectId === projectId)) {
    const tpl = LAYER_TEMPLATES.find((t) => t.id === 'std-4');
    for (const d of tpl?.layers ?? []) {
      state.layerRules.push({ ...hydrateLayerRule(d), projectId });
    }
  }
  if (!state.grades.some((g) => g.projectId === projectId)) {
    const tpl = GRADE_TEMPLATES.find((t) => t.id === 'std-4');
    tpl?.grades.forEach((draft, i) => {
      state.grades.push({
        ...draft,
        code: draft.code.toUpperCase(),
        id: `g-${projectId}-${i}`,
        projectId,
      });
    });
  }
}

export function addDomain(input: Omit<Domain, 'id' | 'projectId'>): Domain | null {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  if (projectDomains.value.some((d) => d.code === input.code.toUpperCase())) {
    message.error('主题域编码必须项目内唯一');
    return null;
  }
  const d: Domain = {
    ...input,
    id: `d-${Date.now()}`,
    projectId: state.currentProjectId,
    code: input.code.toUpperCase(),
  };
  state.domains.push(d);
  persist('spec');
  message.success(`主题域 ${d.code} 已登记`);
  return d;
}

export function addRoot(input: Omit<WordRoot, 'id' | 'projectId'>): WordRoot | null {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  if (projectRoots.value.some((r) => r.code === input.code)) {
    message.error('词根编码已存在');
    return null;
  }
  const r: WordRoot = { ...input, id: `r-${Date.now()}`, projectId: state.currentProjectId };
  state.roots.push(r);
  persist('spec');
  message.success(`词根 ${r.code} 已入库`);
  return r;
}

export function updateDomain(
  id: string,
  input: Omit<Domain, 'id' | 'projectId'>
): Domain | null {
  if (deny('spec:write')) return null;
  const d = state.domains.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!d) return null;
  const code = input.code.toUpperCase();
  if (projectDomains.value.some((x) => x.code === code && x.id !== id)) {
    message.error('主题域编码必须项目内唯一');
    return null;
  }
  Object.assign(d, { ...input, code });
  persist('spec');
  message.success(`主题域 ${d.code} 已更新`);
  return d;
}

export function removeDomain(id: string) {
  if (deny('spec:write')) return;
  const d = state.domains.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!d) return;
  const pid = state.currentProjectId;
  if (tablesInProject(pid).some((t) => t.domain === d.code)) {
    message.error(`主题域 ${d.code} 仍被表引用，无法删除`);
    return;
  }
  state.domains = state.domains.filter((x) => x.id !== id);
  for (const other of state.domains) {
    if (other.projectId === pid) {
      other.related = other.related.filter((c) => c !== d.code);
    }
  }
  for (const r of state.roots) {
    if (r.projectId === pid && r.domain === d.code) r.domain = undefined;
  }
  persist('spec');
  message.success(`主题域 ${d.code} 已删除`);
}

export function updateRoot(id: string, input: Omit<WordRoot, 'id' | 'projectId'>): WordRoot | null {
  if (deny('spec:write')) return null;
  const r = state.roots.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!r) return null;
  if (projectRoots.value.some((x) => x.code === input.code && x.id !== id)) {
    message.error('词根编码已存在');
    return null;
  }
  Object.assign(r, input);
  persist('spec');
  message.success(`词根 ${r.code} 已更新`);
  return r;
}

export function removeRoot(id: string) {
  if (deny('spec:write')) return;
  const r = state.roots.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!r) return;
  state.roots = state.roots.filter((x) => x.id !== id);
  persist('spec');
  message.success(`词根 ${r.code} 已删除`);
}

function ensureProjectLayers() {
  /* 分层按项目隔离：空项目保持空，由导入模板或新增写入 */
}

export function addLayer(input: Omit<LayerRule, 'projectId'>): LayerRule | null {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  ensureProjectLayers();
  const layer = input.layer.toUpperCase();
  if (projectLayerRules.value.some((r) => r.layer === layer)) {
    message.error(`分层 ${layer} 已存在`);
    return null;
  }
  const row: LayerRule = { ...input, layer, projectId: state.currentProjectId };
  state.layerRules.push(row);
  persist('spec');
  message.success(`分层 ${layer} 已新增`);
  return row;
}

export function updateLayer(prevLayer: string, input: Omit<LayerRule, 'projectId'>): LayerRule | null {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  ensureProjectLayers();
  const row = state.layerRules.find(
    (r) => r.projectId === state.currentProjectId && r.layer === prevLayer
  );
  if (!row) return null;
  const layer = input.layer.toUpperCase();
  if (layer !== prevLayer && projectLayerRules.value.some((r) => r.layer === layer)) {
    message.error(`分层 ${layer} 已存在`);
    return null;
  }
  Object.assign(row, { ...input, layer });
  if (layer !== prevLayer) {
    for (const t of state.tables) {
      if (t.projectId === state.currentProjectId && t.layer === prevLayer) t.layer = layer;
    }
  }
  persist('spec');
  message.success(`分层 ${layer} 已更新`);
  return row;
}

export function removeLayer(layer: string) {
  if (deny('spec:write')) return;
  if (!state.currentProjectId) return;
  ensureProjectLayers();
  if (tablesInProject(state.currentProjectId).some((t) => t.layer === layer)) {
    message.error(`分层 ${layer} 仍被表引用，无法删除`);
    return;
  }
  const before = state.layerRules.length;
  state.layerRules = state.layerRules.filter(
    (r) => !(r.projectId === state.currentProjectId && r.layer === layer)
  );
  if (state.layerRules.length === before) return;
  persist('spec');
  message.success(`分层 ${layer} 已删除`);
}

export function addGrade(input: GradeDraft): DataGrade | null {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  const code = input.code.toUpperCase();
  if (projectGrades.value.some((g) => g.code === code)) {
    message.error('等级编码必须项目内唯一');
    return null;
  }
  const g: DataGrade = { ...input, code, id: `g-${Date.now()}`, projectId: state.currentProjectId };
  state.grades.push(g);
  persist('spec');
  message.success(`等级 ${g.code} 已新增`);
  return g;
}

export function updateGrade(id: string, input: GradeDraft): DataGrade | null {
  if (deny('spec:write')) return null;
  const g = state.grades.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!g) return null;
  const code = input.code.toUpperCase();
  if (projectGrades.value.some((x) => x.code === code && x.id !== id)) {
    message.error('等级编码必须项目内唯一');
    return null;
  }
  const prev = g.code;
  Object.assign(g, { ...input, code });
  if (prev !== code) {
    for (const t of state.tables) {
      if (t.projectId !== state.currentProjectId) continue;
      if (t.grade === prev) t.grade = code;
      for (const c of t.columns) if (c.grade === prev) c.grade = code;
    }
  }
  persist('spec');
  message.success(`等级 ${g.code} 已更新`);
  return g;
}

export function removeGrade(id: string) {
  if (deny('spec:write')) return;
  const g = state.grades.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!g) return;
  if (gradeUsedByTables(state.currentProjectId, g.code)) {
    message.error(`等级 ${g.code} 仍被表或字段引用，无法删除，只可改策略与说明`);
    return;
  }
  state.grades = state.grades.filter((x) => x.id !== id);
  persist('spec');
  message.success(`等级 ${g.code} 已删除`);
}

export function applyGradeTemplate(templateId: string, replace: boolean) {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  const tpl = GRADE_TEMPLATES.find((t) => t.id === templateId);
  if (!tpl) {
    message.error('模板不存在');
    return null;
  }
  const pid = state.currentProjectId;
  const incoming = new Set(tpl.grades.map((g) => g.code.toUpperCase()));
  const warnings: string[] = [];
  if (replace) {
    const kept: string[] = [];
    state.grades = state.grades.filter((g) => {
      if (g.projectId !== pid) return true;
      if (gradeUsedByTables(pid, g.code) && !incoming.has(g.code)) {
        kept.push(g.code);
        return true;
      }
      return false;
    });
    if (kept.length) {
      warnings.push(`等级 ${kept.join('、')} 仍被表或字段引用，已保留`);
    }
  }
  let added = 0;
  let updated = 0;
  let skipped = 0;
  const skippedCodes: string[] = [];
  tpl.grades.forEach((draft, i) => {
    const code = draft.code.toUpperCase();
    const exist = state.grades.find((g) => g.projectId === pid && g.code === code);
    if (exist) {
      if (replace) {
        Object.assign(exist, { ...draft, code, projectId: pid });
        updated++;
      } else {
        skipped++;
        skippedCodes.push(code);
      }
      return;
    }
    state.grades.push({
      ...draft,
      code,
      id: `g-${Date.now()}-${i}`,
      projectId: pid,
    });
    added++;
  });
  persist('spec');
  message.success(
    replace
      ? `已替换为「${tpl.name}」，写入 ${added} 个等级${updated ? `，更新 ${updated}` : ''}`
      : `已导入「${tpl.name}」：新增 ${added}${skipped ? `，跳过 ${skipped}（冲突：${skippedCodes.join('、')}）` : ''}`
  );
  if (warnings.length) message.warning(warnings.join('；'));
  return { added, updated, skipped, warnings, conflictCodes: skippedCodes };
}

export function applyLayerTemplate(templateId: string, replace: boolean) {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  const tpl = LAYER_TEMPLATES.find((t) => t.id === templateId);
  if (!tpl) {
    message.error('模板不存在');
    return null;
  }
  const pid = state.currentProjectId;
  if (replace) {
    const used = new Set(tablesInProject(pid).map((t) => t.layer));
    state.layerRules = state.layerRules.filter((r) => r.projectId !== pid || used.has(r.layer));
  }
  let added = 0;
  let skipped = 0;
  for (const draft of tpl.layers) {
    const layer = draft.layer.toUpperCase();
    if (state.layerRules.some((r) => r.projectId === pid && r.layer === layer)) {
      skipped++;
      continue;
    }
    state.layerRules.push({ ...hydrateLayerRule({ ...draft, layer }), projectId: pid });
    added++;
  }
  persist('spec');
  message.success(
    replace
      ? `已替换为「${tpl.name}」，写入 ${added} 个分层`
      : `已导入「${tpl.name}」：新增 ${added}${skipped ? `，跳过 ${skipped}` : ''}`
  );
  return { added, skipped };
}

export async function applyModelProposal(
  drafts: {
    mode: 'create' | 'update';
    tableId?: string;
    layer: string;
    name: string;
    comment: string;
    domain?: string;
    grain?: string;
    period?: string;
    partition?: string;
    grade?: string;
    columns: WarehouseTable['columns'];
  }[]
) {
  if (deny('model:write') || !state.currentProjectId) return 0;
  const layer = drafts[0]?.layer;
  if (useRemoteApi() && layer) {
    const saved = await api.ai.apply(
      state.currentProjectId,
      layer,
      drafts.map((d) => ({
        mode: d.mode,
        tableId: d.tableId,
        layer: d.layer,
        name: d.name,
        comment: d.comment,
        domain: d.domain,
        grain: d.grain,
        period: d.period,
        partition: d.partition,
        grade: d.grade,
        columns: d.columns,
      }))
    );
    for (const t of saved) {
      const i = state.tables.findIndex((x) => x.id === t.id);
      if (i >= 0) state.tables[i] = t;
      else state.tables.push(t);
    }
    message.success(`已写入 ${saved.length} 张表`);
    return saved.length;
  }
  let n = 0;
  for (const d of drafts) {
    if (d.mode === 'update' && d.tableId) {
      if (updateTable(d.tableId, { ...d, status: 'draft', columns: d.columns })) n++;
    } else if (addTable({ ...d, status: 'draft', columns: d.columns })) n++;
  }
  return n;
}

export async function restoreTableVersion(tableId: string, versionId: string) {
  if (deny('model:write') || !state.currentProjectId) return;
  if (useRemoteApi()) {
    const t = await api.versions.restore(state.currentProjectId, tableId, versionId);
    const i = state.tables.findIndex((x) => x.id === t.id);
    if (i >= 0) state.tables[i] = t;
    else state.tables.push(t);
    message.success('已写回为草稿，发布后才会生成新版本');
    return;
  }
  message.info('本地演示请连接 API 后使用版本恢复');
}

export async function updateOwnProfile(input: { displayName?: string }) {
  const name = input.displayName?.trim();
  if (!name) {
    message.warning('请填写显示名');
    return false;
  }
  if (useRemoteApi()) await api.updateMe(name);
  state.currentUser = name;
  message.success('个人信息已保存');
  return true;
}

export async function changeOwnPassword(current: string, next: string) {
  if (!current || !next) {
    message.warning('请填写当前密码和新密码');
    return false;
  }
  if (useRemoteApi()) await api.changePassword(current, next);
  message.success('密码已更新');
  return true;
}

export function addTable(input: Omit<WarehouseTable, 'id' | 'projectId'>): WarehouseTable | null {
  if (deny('model:write')) return null;
  if (!state.currentProjectId) return null;
  if (projectTables.value.some((t) => t.name === input.name)) {
    message.error('项目内表名必须唯一');
    return null;
  }
  const t: WarehouseTable = {
    ...input,
    status: 'draft',
    id: `tbl-${Date.now()}`,
    projectId: state.currentProjectId,
    columns: input.columns.map((c) => ({ ...c })),
  };
  state.tables.push(t);
  persist('tables');
  message.success(`${t.name} 已保存为草稿，发布后才会生成版本`);
  return t;
}

export function updateTable(
  id: string,
  input: Omit<WarehouseTable, 'id' | 'projectId'>
): WarehouseTable | null {
  if (deny('model:write')) return null;
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return null;
  if (projectTables.value.some((x) => x.name === input.name && x.id !== id)) {
    message.error('项目内表名必须唯一');
    return null;
  }
  Object.assign(t, { ...input, status: 'draft', columns: input.columns.map((c) => ({ ...c })) });
  persist('tables');
  message.success(`${t.name} 已保存为草稿，发布后才会生成新版本`);
  return t;
}

export function impactForUpdate(id: string, next: Omit<WarehouseTable, 'id' | 'projectId'>) {
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return null;
  return assessImpact(t, { ...t, ...next }, projectTables.value);
}

/** 当前工作副本相对上一发布版，对下游表字段的影响。 */
export function impactOfWorkingCopy(id: string, lastSnap?: TableSnapshot | null) {
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return null;
  if (!lastSnap) return assessImpact(t, t, projectTables.value);
  const before: WarehouseTable = {
    ...t,
    ...lastSnap,
    id: t.id,
    projectId: t.projectId,
    layer: t.layer,
    columns: lastSnap.columns ?? t.columns,
  };
  return assessImpact(before, t, projectTables.value);
}

export async function publishTable(id: string, note: string): Promise<boolean> {
  if (deny('model:publish')) return false;
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return false;
  if (useRemoteApi() && state.currentProjectId) {
    try {
      const saved = await api.tables.publish(state.currentProjectId, id, { note });
      const i = state.tables.findIndex((x) => x.id === saved.id);
      if (i >= 0) state.tables[i] = saved;
      else state.tables.push(saved);
      message.success(`${saved.name} 已发布为 v${saved.currentVersion ?? ''}`);
      return true;
    } catch (e) {
      remoteErr(e);
      return false;
    }
  }
  t.status = 'published';
  persist('tables');
  message.success(`${t.name} 已发布`);
  return true;
}

export function tableDependents(id: string): { kind: 'table' | 'draft'; id: string; name: string }[] {
  const tables = tableDependentsOf(id, projectTables.value).map((t) => ({
    kind: 'table' as const,
    id: t.id,
    name: t.name,
  }));
  const drafts = projectDrafts.value
    .filter((d) => d.sourceTableId === id)
    .map((d) => ({
      kind: 'draft' as const,
      id: d.id,
      name: `${d.targetLayer} 草案（${d.domainCode} / ${d.grain}）`,
    }));
  return [...tables, ...drafts];
}

export function removeTable(id: string) {
  if (deny('model:write')) return;
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return;
  const deps = tableDependents(id);
  if (deps.length) {
    message.warning(`该表有下游引用：${deps.map((d) => d.name).join('、')}。下游未删除。`);
  }
  state.tables = state.tables.filter((x) => x.id !== id);
  state.qualityRules = state.qualityRules.filter((q) => q.table !== t.name);
  persist('tables');
  message.success(`${t.name} 已删除`);
}

export function applySpecProposal(input: {
  domains: SpecDomainDraft[];
  layers: LayerRule[];
  roots: SpecRootDraft[];
  grades?: GradeDraft[];
  overwrite: boolean;
}) {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  const pid = state.currentProjectId;
  const stamp = Date.now();
  let addedDomains = 0;
  let updatedDomains = 0;
  let skippedDomains = 0;
  let addedRoots = 0;
  let updatedRoots = 0;
  let skippedRoots = 0;

  input.domains.forEach((d, i) => {
    const code = d.code.toUpperCase();
    const exist = state.domains.find((x) => x.projectId === pid && x.code === code);
    if (exist) {
      if (input.overwrite) {
        exist.name = d.name;
        exist.definition = d.definition;
        exist.related = d.related;
        exist.coreEntities = d.coreEntities;
        updatedDomains++;
      } else {
        skippedDomains++;
      }
      return;
    }
    const row: Domain = {
      ...d,
      id: `d-${stamp}-${i}`,
      projectId: pid,
      code,
    };
    state.domains.push(row);
    addedDomains++;
  });

  let addedLayers = 0;
  let updatedLayers = 0;
  let skippedLayers = 0;
  if (input.layers.length) {
    ensureProjectLayers();
    for (const l of input.layers) {
      const layer = l.layer.toUpperCase();
      const exist = state.layerRules.find((x) => x.projectId === pid && x.layer === layer);
      if (exist) {
        if (input.overwrite) {
          Object.assign(exist, { ...l, layer, projectId: pid });
          updatedLayers++;
        } else {
          skippedLayers++;
        }
        continue;
      }
      state.layerRules.push({ ...l, layer, projectId: pid });
      addedLayers++;
    }
  }

  input.roots.forEach((r, i) => {
    const exist = state.roots.find((x) => x.projectId === pid && x.code === r.code);
    if (exist) {
      if (input.overwrite) {
        exist.zh = r.zh;
        exist.en = r.en;
        exist.kind = r.kind;
        exist.domain = r.domain;
        exist.formula = r.formula;
        exist.dataType = r.dataType;
        exist.format = r.format;
        updatedRoots++;
      } else {
        skippedRoots++;
      }
      return;
    }
    const row: WordRoot = {
      ...r,
      id: `r-${stamp}-${i}`,
      projectId: pid,
    };
    state.roots.push(row);
    addedRoots++;
  });

  let addedGrades = 0;
  let updatedGrades = 0;
  let skippedGrades = 0;
  (input.grades ?? []).forEach((g, i) => {
    const code = g.code.toUpperCase();
    const exist = state.grades.find((x) => x.projectId === pid && x.code === code);
    if (exist) {
      if (input.overwrite) {
        Object.assign(exist, { ...g, code, projectId: pid });
        updatedGrades++;
      } else {
        skippedGrades++;
      }
      return;
    }
    state.grades.push({ ...g, code, id: `g-${stamp}-${i}`, projectId: pid });
    addedGrades++;
  });

  persist('spec');
  const parts = [
    `主题域 +${addedDomains}`,
    updatedDomains ? `更新${updatedDomains}` : '',
    skippedDomains ? `跳过${skippedDomains}` : '',
    input.layers.length ? `分层 +${addedLayers}` : '',
    updatedLayers ? `更新${updatedLayers}` : '',
    skippedLayers ? `跳过${skippedLayers}` : '',
    `词根 +${addedRoots}`,
    updatedRoots ? `更新${updatedRoots}` : '',
    skippedRoots ? `跳过${skippedRoots}` : '',
    input.grades?.length ? `等级 +${addedGrades}` : '',
    updatedGrades ? `更新${updatedGrades}` : '',
    skippedGrades ? `跳过${skippedGrades}` : '',
  ].filter(Boolean);
  message.success(`已同步进当前项目：${parts.join('，')}`);
  return {
    addedDomains,
    updatedDomains,
    skippedDomains,
    addedLayers,
    updatedLayers,
    skippedLayers,
    addedRoots,
    updatedRoots,
    skippedRoots,
    addedGrades,
  };
}

export function buildCurrentSpecPack(): SpecPack {
  const tenant = currentTenant.value;
  const project = currentProject.value;
  return buildSpecPack(
    {
      tenantName: tenant?.name ?? '',
      tenantCode: tenant?.code ?? '',
      projectName: project?.name ?? '',
      projectCode: project?.code ?? '',
    },
    {
      domains: projectDomains.value.map(({ id: _id, projectId: _pid, ...rest }) => rest),
      layers: projectLayerRules.value.map(({ projectId: _pid, ...rest }) => rest),
      roots: projectRoots.value.map(({ id: _id, projectId: _pid, ...rest }) => rest),
      grades: projectGrades.value.map(({ id: _id, projectId: _pid, ...rest }) => rest),
    }
  );
}

export function importSpecPack(
  pack: SpecPack,
  opts: { overwrite: boolean; replace: boolean }
) {
  if (deny('spec:write')) return null;
  if (!state.currentProjectId) return null;
  const pid = state.currentProjectId;
  const stamp = Date.now();
  const warnings: string[] = [];
  const refs = tableRefsInProject(pid);

  if (opts.replace) {
    const incomingDomains = new Set(pack.domains.map((d) => d.code.toUpperCase()));
    const incomingLayers = new Set(pack.layers.map((l) => l.layer.toUpperCase()));
    const incomingGrades = new Set((pack.grades ?? []).map((g) => g.code.toUpperCase()));
    const keptDomains: string[] = [];
    const keptLayers: string[] = [];
    const keptGrades: string[] = [];

    state.domains = state.domains.filter((d) => {
      if (d.projectId !== pid) return true;
      if (refs.domains.has(d.code)) {
        if (!incomingDomains.has(d.code)) keptDomains.push(d.code);
        return true;
      }
      return false;
    });
    state.layerRules = state.layerRules.filter((r) => {
      if (r.projectId !== pid) return true;
      if (refs.layers.has(r.layer)) {
        if (!incomingLayers.has(r.layer)) keptLayers.push(r.layer);
        return true;
      }
      return false;
    });
    state.grades = state.grades.filter((g) => {
      if (g.projectId !== pid) return true;
      if (refs.grades.has(g.code)) {
        if (!incomingGrades.has(g.code)) keptGrades.push(g.code);
        return true;
      }
      return false;
    });
    state.roots = state.roots.filter((r) => r.projectId !== pid);

    if (keptDomains.length) {
      warnings.push(`主题域 ${keptDomains.join('、')} 仍被表引用，已跳过删除`);
    }
    if (keptLayers.length) {
      warnings.push(`分层 ${keptLayers.join('、')} 仍被表引用，已跳过删除`);
    }
    if (keptGrades.length) {
      warnings.push(`等级 ${keptGrades.join('、')} 仍被表或字段引用，已跳过删除`);
    }
  } else {
    ensureProjectLayers();
  }

  let addedDomains = 0;
  let updatedDomains = 0;
  let skippedDomains = 0;
  let addedLayers = 0;
  let updatedLayers = 0;
  let skippedLayers = 0;
  let addedRoots = 0;
  let updatedRoots = 0;
  let skippedRoots = 0;
  const skippedDomainCodes: string[] = [];
  const skippedLayerCodes: string[] = [];
  const skippedRootCodes: string[] = [];
  const skippedGradeCodes: string[] = [];

  pack.domains.forEach((d, i) => {
    const code = d.code.toUpperCase();
    const exist = state.domains.find((x) => x.projectId === pid && x.code === code);
    if (exist) {
      if (opts.overwrite) {
        exist.name = d.name;
        exist.definition = d.definition;
        exist.bizOwner = d.bizOwner;
        exist.techOwner = d.techOwner;
        exist.dataOwner = d.dataOwner;
        exist.related = d.related;
        exist.coreEntities = d.coreEntities;
        updatedDomains++;
      } else {
        skippedDomains++;
        skippedDomainCodes.push(code);
      }
      return;
    }
    state.domains.push({
      ...d,
      id: `d-${stamp}-${i}`,
      projectId: pid,
      code,
    });
    addedDomains++;
  });

  pack.layers.forEach((l) => {
    const layer = l.layer.toUpperCase();
    const exist = state.layerRules.find((x) => x.projectId === pid && x.layer === layer);
    if (exist) {
      if (opts.overwrite) {
        Object.assign(exist, { ...l, layer, projectId: pid });
        updatedLayers++;
      } else {
        skippedLayers++;
        skippedLayerCodes.push(layer);
      }
      return;
    }
    state.layerRules.push({ ...l, layer, projectId: pid });
    addedLayers++;
  });

  pack.roots.forEach((r, i) => {
    const exist = state.roots.find((x) => x.projectId === pid && x.code === r.code);
    if (exist) {
      if (opts.overwrite) {
        exist.zh = r.zh;
        exist.en = r.en;
        exist.kind = r.kind;
        exist.domain = r.domain;
        exist.formula = r.formula;
        exist.dataType = r.dataType;
        exist.format = r.format;
        updatedRoots++;
      } else {
        skippedRoots++;
        skippedRootCodes.push(r.code);
      }
      return;
    }
    state.roots.push({ ...r, id: `r-${stamp}-${i}`, projectId: pid });
    addedRoots++;
  });

  let addedGrades = 0;
  let updatedGrades = 0;
  let skippedGrades = 0;
  (pack.grades ?? []).forEach((g, i) => {
    const code = g.code.toUpperCase();
    const exist = state.grades.find((x) => x.projectId === pid && x.code === code);
    if (exist) {
      if (opts.overwrite) {
        Object.assign(exist, { ...g, code, projectId: pid });
        updatedGrades++;
      } else {
        skippedGrades++;
        skippedGradeCodes.push(code);
      }
      return;
    }
    state.grades.push({ ...g, code, id: `g-${stamp}-${i}`, projectId: pid });
    addedGrades++;
  });

  const conflictParts = [
    skippedDomainCodes.length ? `主题域 ${skippedDomainCodes.join('、')}` : '',
    skippedLayerCodes.length ? `分层 ${skippedLayerCodes.join('、')}` : '',
    skippedRootCodes.length ? `词根 ${skippedRootCodes.join('、')}` : '',
    skippedGradeCodes.length ? `等级 ${skippedGradeCodes.join('、')}` : '',
  ].filter(Boolean);
  if (conflictParts.length) {
    warnings.push(`编码冲突已跳过：${conflictParts.join('；')}`);
  }

  persist('spec');
  const skipLabel = (n: number, codes: string[]) =>
    n ? (codes.length ? `跳过${n}（冲突：${codes.join('、')}）` : `跳过${n}`) : '';
  const parts = [
    `主题域 +${addedDomains}`,
    updatedDomains ? `更新${updatedDomains}` : '',
    skipLabel(skippedDomains, skippedDomainCodes),
    `分层 +${addedLayers}`,
    updatedLayers ? `更新${updatedLayers}` : '',
    skipLabel(skippedLayers, skippedLayerCodes),
    `词根 +${addedRoots}`,
    updatedRoots ? `更新${updatedRoots}` : '',
    skipLabel(skippedRoots, skippedRootCodes),
    `等级 +${addedGrades}`,
    updatedGrades ? `更新${updatedGrades}` : '',
    skipLabel(skippedGrades, skippedGradeCodes),
  ].filter(Boolean);
  const summary = `已导入当前项目：${parts.join('，')}`;
  message.success(summary);
  if (warnings.length) message.warning(warnings.join('；'));
  return {
    addedDomains,
    updatedDomains,
    skippedDomains,
    addedLayers,
    updatedLayers,
    skippedLayers,
    addedRoots,
    updatedRoots,
    skippedRoots,
    addedGrades,
    updatedGrades,
    skippedGrades,
    warnings,
    conflictCodes: {
      domains: skippedDomainCodes,
      layers: skippedLayerCodes,
      roots: skippedRootCodes,
      grades: skippedGradeCodes,
    },
  };
}

export function runOdsToDwd(sourceId: string) {
  if (deny('model:write')) return null;
  if (!state.currentProjectId) return null;
  const source = state.tables.find((t) => t.id === sourceId);
  if (!source) {
    message.error('找不到源表');
    return null;
  }
  if (source.status === 'deprecated') {
    message.warning(`源表「${source.name}」已下线，仍将生成草案`);
  }
  const dwdRule = projectLayerRules.value.find((r) => r.layer === 'DWD');
  const draft = buildDwdDraft({
    projectId: state.currentProjectId,
    source,
    domains: projectDomains.value,
    roots: projectRoots.value,
    layerRule: hydrateLayerRule(dwdRule ?? { layer: 'DWD', naming: '', retention: '', serve: 'forbid', note: '' }),
  });
  state.drafts.unshift(draft);
  persist('drafts');
  message.success('AI 已生成 DWD 草案，请审核');
  return draft;
}

export function runDwdToDws(sourceId: string | string[], dims?: string[]) {
  if (deny('model:write')) return null;
  if (!state.currentProjectId) return null;
  const ids = Array.isArray(sourceId) ? sourceId : [sourceId];
  const sources = ids
    .map((id) => state.tables.find((t) => t.id === id))
    .filter((t): t is WarehouseTable => Boolean(t));
  const source = sources[0];
  if (!source) return null;
  if (sources.some((t) => t.status === 'deprecated')) {
    message.warning('部分源表已下线，仍将生成草案');
  }
  const dwsRule = projectLayerRules.value.find((r) => r.layer === 'DWS');
  const draft = buildDwsDraft({
    projectId: state.currentProjectId,
    sources,
    domains: projectDomains.value,
    preferredDims: dims,
    layerRule: hydrateLayerRule(dwsRule ?? { layer: 'DWS', naming: '', retention: '', serve: 'approval', note: '' }),
    grades: projectGrades.value,
    roots: projectRoots.value,
  });
  state.drafts.unshift(draft);
  persist('drafts');
  message.success('AI 已生成 DWS 草案，请审核');
  return draft;
}

export async function approveDraft(draftId: string) {
  if (deny('model:publish')) return;
  const draft = state.drafts.find((d) => d.id === draftId);
  if (!draft || !state.currentProjectId) return;
  const errors = draft.specIssues.filter((i) => i.level === 'error');
  if (errors.length) {
    message.error('规范校验未通过，禁止发布。请打回重生成。');
    return;
  }
  draft.status = 'approved';
  const source = state.tables.find((t) => t.id === draft.sourceTableId);
  let table: WarehouseTable;
  if (draft.targetLayer === 'DWD' && source) {
    table = tableFromDwdDraft(draft, source);
  } else {
    table = tableFromDwsDraft(draft);
  }
  table.status = 'draft';
  if (!state.tables.some((t) => t.name === table.name)) state.tables.push(table);
  for (const q of draft.qualityRules) {
    if (!state.qualityRules.some((x) => x.id === q.id)) state.qualityRules.push({ ...q, status: 'ok' });
  }
  const jobId = `job-${table.name}`;
  if (!state.jobs.some((j) => j.id === jobId)) {
    const upstream = state.jobs.find((j) => j.table === source?.name);
    state.jobs.push({
      id: jobId,
      projectId: state.currentProjectId,
      name: `${table.name}.etl`,
      type: table.layer === 'DWS' ? 'etl' : 'etl',
      engine: 'Spark SQL',
      dependsOn: upstream ? [upstream.id] : [],
      status: 'success',
      lastRun: new Date().toISOString().slice(0, 16).replace('T', ' '),
      durationMs: 90000,
      table: table.name,
    });
  }
  persist('drafts');
  if (useRemoteApi()) {
    try {
      const saved = await api.tables.save(state.currentProjectId, { ...table, projectId: state.currentProjectId });
      const published = await api.tables.publish(state.currentProjectId, saved.id, { note: '审核通过并发布' });
      const i = state.tables.findIndex((x) => x.id === published.id || x.name === published.name);
      if (i >= 0) state.tables[i] = published;
      else state.tables.push(published);
      message.success(`${published.name} 已发布为 v${published.currentVersion ?? ''}`);
    } catch (e) {
      remoteErr(e);
    }
    return;
  }
  persist('tables');
  message.success(`${table.name} 已发布，ETL 任务已挂入调度`);
}

export function rejectDraft(draftId: string) {
  if (deny('model:write')) return;
  const draft = state.drafts.find((d) => d.id === draftId);
  if (!draft) return;
  draft.status = 'rejected';
  persist('drafts');
  message.info('草案已打回');
}

export function registerMetric(input: Omit<Metric, 'id' | 'projectId' | 'changelog' | 'score' | 'favorites'>): Metric | null {
  if (!state.currentProjectId) return null;
  const check = checkDuplicate(input, projectMetrics.value);
  if (check.action === 'reject') {
    message.error(check.reason);
    return null;
  }
  if (check.action === 'reuse' && check.match) {
    message.warning(check.reason);
    return check.match;
  }
  const m: Metric = {
    ...input,
    id: `m-${Date.now()}`,
    projectId: state.currentProjectId,
    changelog: [{ version: input.version, date: new Date().toISOString().slice(0, 10), change: '初始创建' }],
    score: 0,
    favorites: 0,
  };
  state.metrics.push(m);
  persist();
  message.success(`指标「${m.name}」已${m.status === 'published' ? '发布' : '保存为草稿'}`);
  return m;
}

export function favoriteMetric(id: string) {
  const m = state.metrics.find((x) => x.id === id);
  if (!m) return;
  m.favorites += 1;
  persist();
}

export function rateMetric(id: string, score: number) {
  const m = state.metrics.find((x) => x.id === id);
  if (!m) return;
  m.score = Number(((m.score * m.favorites + score) / (m.favorites + 1)).toFixed(1));
  persist();
}

export function refreshClusters() {
  if (!state.currentProjectId) return;
  const logs = projectLogs.value;
  const clusters = clusterLogs(logs);
  state.clusters = state.clusters.filter((c) => c.projectId !== state.currentProjectId).concat(clusters);
  const recs = clusters.map((c) => scoreCluster(c, projectTables.value));
  const old = projectRecs.value;
  for (const rec of recs) {
    const prev = old.find((r) => r.clusterId === rec.clusterId);
    if (prev) rec.status = prev.status, rec.grayPercent = prev.grayPercent;
  }
  state.recs = state.recs.filter((r) => r.projectId !== state.currentProjectId).concat(recs);
  persist();
  message.success('已按 SQL 指纹重算查询簇与物化推荐');
}

export function decideRec(id: string, status: 'approved' | 'rejected') {
  const rec = state.recs.find((r) => r.id === id);
  if (!rec) return;
  rec.status = status;
  persist();
  message.success(status === 'approved' ? '已进入灰度队列' : '已拒绝该推荐');
}

export function setGray(id: string, percent: number) {
  const rec = state.recs.find((r) => r.id === id);
  if (!rec || rec.status === 'rejected') return;
  rec.grayPercent = percent;
  rec.status = percent >= 100 ? 'online' : 'gray';
  if (percent >= 100 && !state.tables.some((t) => t.name === rec.targetTable)) {
    const dims = projectClusters.value.find((c) => c.id === rec.clusterId)?.dimensions ?? ['dt'];
    const measures = projectClusters.value.find((c) => c.id === rec.clusterId)?.measures ?? [];
    state.tables.push({
      id: `tbl-${rec.id}`,
      projectId: rec.projectId,
      layer: 'DWS',
      name: rec.targetTable,
      comment: '查询簇沉淀物化表',
      domain: 'TRD',
      period: 'di',
      partition: 'dt',
      status: 'published',
      columns: [
        ...dims.map((d) => ({ name: d, type: 'STRING', comment: d })),
        ...measures.map((m) => ({
          name: m.alias,
          type: m.agg.includes('SUM') ? 'DECIMAL(18,2)' : 'BIGINT',
          comment: m.alias,
        })),
      ],
    });
    state.jobs.push({
      id: `job-${rec.targetTable}`,
      projectId: rec.projectId,
      name: `${rec.targetTable}.materialize`,
      type: 'materialize',
      engine: 'Spark SQL',
      dependsOn: ['job-etl-dwd'],
      status: 'success',
      lastRun: new Date().toISOString().slice(0, 16).replace('T', ' '),
      table: rec.targetTable,
    });
  }
  persist();
}

export function addQueryFromFactory(cfg: FactoryConfig) {
  if (!state.currentProjectId) return;
  state.queryLogs.unshift({
    id: `q_${Date.now()}`,
    projectId: state.currentProjectId,
    userId: 'u_123',
    timestamp: new Date().toISOString().slice(0, 19).replace('T', ' '),
    datasource: cfg.table,
    dimensions: cfg.dimensions,
    measures: cfg.measures,
    filters: cfg.filters,
    sqlFingerprint: `SELECT ${cfg.dimensions.join(',')},${cfg.measures.map((m) => `${m.agg}(${m.field})`).join(',')} FROM ${cfg.table} WHERE dt>=? GROUP BY ${cfg.dimensions.join(',')}`,
    executionTimeMs: 180 + Math.round(Math.random() * 800),
    scanRows: cfg.layer === 'DWD' ? 80000000 : 2000000,
    costScore: cfg.layer === 'DWD' ? 7.2 : 1.4,
  });
  persist();
}

export function addServeFolder(input: { scope: ServeScope; name: string }): ServeFolder | null {
  if (!state.currentProjectId || !input.name.trim()) return null;
  if (input.scope === 'public') {
    message.error('公共分类请走规范/口径管理，个人不能直接新建');
    return null;
  }
  const row: ServeFolder = {
    id: `sf-${Date.now()}`,
    projectId: state.currentProjectId,
    scope: input.scope,
    name: input.name.trim(),
    owner: input.scope === 'personal' ? state.currentUser : undefined,
  };
  state.serveFolders.push(row);
  persist();
  message.success(`目录「${row.name}」已创建`);
  return row;
}

export function addServeMetric(input: {
  name: string;
  definition: string;
  calculationLogic: string;
  sourceTable: string;
  folderId: string;
  measure?: string;
  aggregation?: string;
  unit?: string;
  dimensions?: string[];
}): ServeMetric | null {
  if (!state.currentProjectId) return null;
  const folder = state.serveFolders.find((f) => f.id === input.folderId);
  if (!folder || folder.scope !== 'personal' || folder.owner !== state.currentUser) {
    message.error('只能在个人空间的目录下新建指标');
    return null;
  }
  if (!input.name.trim() || !input.calculationLogic.trim()) {
    message.warning('名称和计算逻辑必填');
    return null;
  }
  const row: ServeMetric = {
    id: `sm-${Date.now()}`,
    projectId: state.currentProjectId,
    name: input.name.trim(),
    definition: input.definition.trim(),
    calculationLogic: input.calculationLogic.trim(),
    sourceTable: input.sourceTable,
    dimensions: input.dimensions ?? ['dt'],
    measure: input.measure ?? '',
    aggregation: input.aggregation ?? 'SUM',
    unit: input.unit ?? '',
    owner: state.currentUser,
    scope: 'personal',
    folderId: input.folderId,
    status: 'draft',
  };
  state.serveMetrics.push(row);
  persist();
  message.success(`个人指标「${row.name}」已保存`);
  return row;
}

export function copyServeMetricToPersonal(id: string, folderId: string): ServeMetric | null {
  const src = state.serveMetrics.find((m) => m.id === id);
  const folder = state.serveFolders.find((f) => f.id === folderId);
  if (!src || !folder || !state.currentProjectId) return null;
  if (folder.scope !== 'personal' || folder.owner !== state.currentUser) {
    message.error('请选择自己的个人目录');
    return null;
  }
  const row: ServeMetric = {
    ...src,
    id: `sm-${Date.now()}`,
    projectId: state.currentProjectId,
    owner: state.currentUser,
    scope: 'personal',
    folderId,
    status: 'draft',
    clonedFrom: src.id,
    name: src.name,
  };
  state.serveMetrics.push(row);
  persist();
  message.success(`已复制到个人空间：${row.name}`);
  return row;
}

export function publishServeMetric(id: string, target: 'business' | 'public', folderId: string): ServeMetric | null {
  const src = state.serveMetrics.find((m) => m.id === id);
  if (!src || !state.currentProjectId) return null;
  if (src.scope !== 'personal' || src.owner !== state.currentUser) {
    message.error('只能发布自己的个人指标');
    return null;
  }
  const folder = state.serveFolders.find((f) => f.id === folderId && f.scope === target);
  if (!folder) {
    message.error('请选择目标目录');
    return null;
  }
  if (target === 'public') {
    const clash = state.serveMetrics.find(
      (m) =>
        m.projectId === state.currentProjectId &&
        m.scope === 'public' &&
        m.status === 'published' &&
        m.name === src.name
    );
    if (clash) {
      message.error(`公共指标已存在同名「${src.name}」，禁止覆盖公司口径`);
      return null;
    }
  }
  const row: ServeMetric = {
    ...src,
    id: `sm-${Date.now()}`,
    scope: target,
    folderId,
    status: target === 'public' ? 'review' : 'published',
    clonedFrom: src.id,
  };
  src.status = 'published';
  state.serveMetrics.push(row);
  persist();
  message.success(target === 'public' ? '已提交公共口径审核，通过前不会出现在市场货架' : `已发布到业务空间「${folder.name}」`);
  return row;
}

export function approveServeMetric(id: string) {
  const m = state.serveMetrics.find((x) => x.id === id);
  if (!m || m.scope !== 'public' || m.status !== 'review') return;
  m.status = 'published';
  persist();
  message.success(`「${m.name}」已通过，进入公共指标`);
}

export function rejectServeMetric(id: string) {
  const m = state.serveMetrics.find((x) => x.id === id);
  if (!m || m.status !== 'review') return;
  const home = state.serveFolders.find((f) => f.scope === 'personal' && f.owner === m.owner);
  m.status = 'draft';
  m.scope = 'personal';
  if (home) m.folderId = home.id;
  persist();
  message.info(`「${m.name}」已打回个人空间`);
}

export function resetDemo() {
  if (useRemoteApi()) {
    message.info('远程模式数据在 PostgreSQL，请在库里重置，控制台不再写 localStorage');
    return;
  }
  const fresh = createSeed();
  Object.assign(state, fresh);
  persist();
  try {
    const drop: string[] = [];
    for (let i = 0; i < localStorage.length; i++) {
      const k = localStorage.key(i);
      if (k?.startsWith('dw-ai.specChat.')) drop.push(k);
    }
    drop.forEach((k) => localStorage.removeItem(k));
  } catch {
    /* ignore */
  }
  message.success('已重置为演示数据');
}
