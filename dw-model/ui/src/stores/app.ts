import { computed, reactive, readonly } from 'vue';
import { message } from 'ant-design-vue';
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
  type ProductModule,
  type ProjectRole,
} from '../config/iam';
import { createSeed, seedServe } from '../mock/seed';
import { GRADE_TEMPLATES } from '../config/grades';
import { hydrateLayerRule } from '../config/layerPolicies';
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
import { api, authToken, setAuthToken, refreshAccess, setIdleTtlSeconds, useRemoteApi, type KnowledgeArticleDto, type Session, type Snapshot } from '../api/client';
import { clearDeployMode, isMultiTenant, isStandalone, isStandardMode } from '../config/runtime';
import { hasWorkbench } from '../config/pages';
import { isWarehouseUi } from '../config/product';
import { MODEL_HOME, NO_PROJECT, SYS_HOME } from '../config/paths';
import { loadPlatformAppearance, loadTenantAppearance, loadTenantLlm } from './prefs';

const STORAGE_KEY = 'dw-ai.state.v1';

function defaultIam(): Pick<AppState, 'members' | 'licenses'> {
  return {
    members: [
      { projectId: 'p-trade', userId: '张三', role: 'admin' },
      { projectId: 'p-trade', userId: '李四', role: 'modeler' },
      { projectId: 'p-empty', userId: '李四', role: 'admin' },
      { projectId: 'p-empty', userId: '张三', role: 'viewer' },
    ],
    licenses: [
      { tenantId: 't-xinghe', modules: ['warehouse'] },
      { tenantId: 't-qihang', modules: ['warehouse'] },
    ],
  };
}

function hydrateIam(state: AppState) {
  const fresh = defaultIam();
  if (!Array.isArray(state.members) || !state.members.length) state.members = fresh.members;
  if (!Array.isArray(state.licenses) || !state.licenses.length) state.licenses = fresh.licenses;
  for (const p of state.projects) {
    if (!state.members.some((m) => m.projectId === p.id)) {
      state.members.push({
        projectId: p.id,
        userId: p.owner || state.currentUser,
        role: 'admin',
      });
    }
  }
  for (const t of state.tenants) {
    if (!state.licenses.some((l) => l.tenantId === t.id)) {
      state.licenses.push({ tenantId: t.id, modules: ['warehouse'] });
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
  seed.licenses = [{ tenantId: seed.tenants[0]?.id ?? 't-xinghe', modules: ['warehouse'] }];
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
  const landingPid = s.user.landingProjectId;
  if (landingPid && state.projects.some((p) => p.id === landingPid)) {
    state.currentProjectId = landingPid;
    sessionStorage.setItem('dw-ai.projectId', landingPid);
    const p = state.projects.find((x) => x.id === landingPid);
    if (p?.code) sessionStorage.setItem('dw-ai.projectCode', p.code);
  }
  const t = state.tenants.find((x) => x.id === state.currentTenantId);
  if (t?.code) sessionStorage.setItem('dw-ai.tenantCode', t.code);
  if (state.currentTenantId) void loadTenantKnowledge(state.currentTenantId);
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
  if (!useRemoteApi() || !authToken()) return;
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
    const mode = cfg.runMode || cfg.deployMode;
    if (mode === 'multi' || mode === 'standard' || mode === 'standalone') {
      sessionStorage.setItem('dw-ai.deployMode', mode);
    } else {
      // 后端答了，但没答出模式（老后端不带 runMode）—— 同样不能留上一次的值
      clearDeployMode();
    }
    const standalone = mode === 'standalone' || cfg.allowLogin === false;
    if (cfg.allowLogin !== false && cfg.sessionEpoch) {
      const prev = sessionStorage.getItem(BOOT_KEY);
      if (prev && prev !== cfg.sessionEpoch) {
        if (!(await refreshAccess())) setAuthToken(null);
      }
      sessionStorage.setItem(BOOT_KEY, cfg.sessionEpoch);
    }
    if (!authToken() && !standalone) return;
    applySession(await api.session());
    await refreshMyTenants();
    await loadPlatformAppearance();
    if (state.currentTenantId) {
      await loadTenantAppearance(state.currentTenantId);
      await loadTenantLlm(state.currentTenantId);
    }
    const savedPid = sessionStorage.getItem('dw-ai.projectId');
    if (
      savedPid &&
      state.projects.some((p) => p.id === savedPid && isProjectActive(p))
    ) {
      state.currentProjectId = savedPid;
      const p = state.projects.find((x) => x.id === savedPid);
      if (p?.code) sessionStorage.setItem('dw-ai.projectCode', p.code);
    }
    if (state.currentProjectId) {
      applySnapshot(state.currentProjectId, await api.snapshot(state.currentProjectId));
    }
  } catch (e) {
    // 后端没答上来（没起来 / 网络不通）—— 把上次问到的模式作废。
    // 留着它最典型的翻车：上次后端跑 standard，这次只起了独立模式的前端，
    // 前端照着残留的 standard 出登录页，而这次根本没有后端可以登录。
    clearDeployMode();
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
    applySession(await api.session());
    await refreshMyTenants();
    await loadTenantAppearance(out.tenants[0].id);
    await loadTenantLlm(out.tenants[0].id);
  } else if (out.platformAdmin) {
    // 后端 api.session() 会根据模式返回正确的 tenantId：
    // - multi ：platformAdmin 还没选租户 → tenantId=null
    // - standard：TenantFilter 自动用 implicitTenantId() → tenantId 有值
    // 这里先不硬清空，让 applyMe 从 session 响应里决定。
    try {
      applySession(await api.session());
    } catch {
      /* 平台用户未选租户时 session 仍可用 */
      sessionStorage.removeItem('dw-ai.tenantId');
      state.currentTenantId = '';
      state.tenantRole = null;
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
  await loadTenantLlm(tenantId);
  return me;
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

export const currentProjectRole = computed<ProjectRole | undefined>(() => {
  if (isStandalone()) return 'admin';
  if (!state.currentProjectId) return undefined;
  const uid = state.currentUserId || state.currentUser;
  // 必须带产品过滤：加产品维之后同一个人在同一项目下有多行（仓建设一行、数据地图一行），
  // 只按 (projectId, userId) 找会拿到随机一行的角色。本进程只认仓建设那行。
  // `?? 'warehouse'` 兼容不带 product 的行（授权码访客的合成行、旧接口）。
  const found = state.members.find(
    (m) =>
      m.projectId === state.currentProjectId &&
      m.userId === uid &&
      (m.product ?? 'warehouse') === 'warehouse'
  )?.role;
  if (found) return found;
  // 平台持码进入：会话里合成 viewer，拉快照时可能被冲掉，只读权限仍按访客算
  if (state.platformAdmin && state.currentTenantId && state.tenantRole !== 'admin') return 'viewer';
  // 仓建设 multi 成员表在组织侧，本地会话可能还没合成角色
  if (isWarehouseUi() && isMultiTenant() && state.currentProjectId) return 'modeler';
  return undefined;
});

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
export const canWriteSpec = computed(() => can('spec:write'));
export const canWriteModel = computed(() => can('model:write'));
export const canPublishModel = computed(() => can('model:publish'));

export function resolveTenantHome(): string {
  const list = tenantProjects.value;
  if (!list.length) return isStandalone() ? MODEL_HOME : NO_PROJECT;
  // tenant admin 优先进入系统管理工作台（尚未选项目时）。原先只判 standard，
  // 但工作台 standalone 也有（见 `config/pages.ts` 的 `hasWorkbench`）——
  // 后端 `TenantFilter` 对 standalone 直接给 `tenantRole=admin`，这个条件本来就成立。
  if (hasWorkbench() && state.tenantRole === 'admin' && !state.currentProjectId) return SYS_HOME;
  const keep = list.find((p) => p.id === state.currentProjectId);
  const id = keep?.id ?? list[0].id;
  if (state.currentProjectId !== id) {
    state.currentProjectId = id;
    sessionStorage.setItem('dw-ai.projectId', id);
  }
  const p = list.find((x) => x.id === id);
  if (p?.code) sessionStorage.setItem('dw-ai.projectCode', p.code);
  return MODEL_HOME;
}

export const currentRoleLabel = computed(() =>
  currentProjectRole.value ? ROLE_LABEL[currentProjectRole.value] : '未加入项目'
);

/** 本进程（仓建设）的判权。产品码是常量 —— 这个前端只会画仓建设的菜单。 */
export function can(perm: Perm): boolean {
  return roleHas('warehouse', currentProjectRole.value, perm);
}

export function hasModule(mod: ProductModule): boolean {
  if (isStandalone() && mod === 'warehouse') return true;
  if (!isMultiTenant() && mod !== 'warehouse') return false;
  const lic = state.licenses.find((l) => l.tenantId === state.currentTenantId);
  return Boolean(lic?.modules.includes(mod));
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
  if (!can('iam:member')) {
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
  if (can(perm)) return false;
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
  const own = state.layerRules.filter((r) => r.projectId === pid);
  if (own.length) return own;
  return state.layerRules.filter((r) => !r.projectId);
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
  const tenant = state.tenants.find((x) => x.id === p.tenantId);
  if (tenant?.code) sessionStorage.setItem('dw-ai.tenantCode', tenant.code);
  if (p.code) sessionStorage.setItem('dw-ai.projectCode', p.code);
  if (useRemoteApi()) {
    applySnapshot(p.id, await api.snapshot(p.id));
    return;
  }
  persist();
}

/** 成员直接落到 /model 时也要拉快照，否则建模侧栏没有分层。 */
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
  persist();
}

/** 回到平台后台：清掉当前租户，平台壳不切换租户。 */
export function leaveTenant() {
  state.currentTenantId = '';
  state.currentProjectId = null;
  state.tenantRole = null;
  sessionStorage.removeItem('dw-ai.tenantId');
  sessionStorage.removeItem('dw-ai.projectId');
  persist();
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
    const project = await api.createProject({
      ...input,
      owner: input.adminUserId || input.owner,
      adminUserId: input.adminUserId,
      engines: input.engines,
    });
    state.projects.push(project);
    if (!state.members.some((m) => m.projectId === project.id && m.userId === state.currentUser)) {
      state.members.push({ projectId: project.id, userId: state.currentUser, role: 'admin' });
    }
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
  state.members.push({ projectId: project.id, userId: state.currentUser, role: 'admin' });
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
    const updated = await api.patchProject(projectId, body);
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
    await api.deleteProject(projectId);
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
    const defaults = state.layerRules.filter((r) => !r.projectId);
    for (const d of defaults) {
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
  const pid = state.currentProjectId;
  if (!pid) return;
  if (state.layerRules.some((r) => r.projectId === pid)) return;
  const defaults = state.layerRules.filter((r) => !r.projectId);
  for (const d of defaults) {
    state.layerRules.push({ ...d, projectId: pid });
  }
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
