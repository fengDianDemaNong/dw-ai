import { computed, reactive, readonly } from 'vue';
import { message } from 'ant-design-vue';
import type {
  Account,
  AccountStatus,
  AiCap,
  AppState,
  GrantKind,
  PlatformAccess,
  TenantGrant,
  DataGrade,
  Domain,
  FactoryConfig,
  GradeDraft,
  LayerRule,
  Metric,
  ModelTableDraft,
  EngineKind,
  ModuleVisibleTo,
  PlatformService,
  ProductEndpoint,
  ProductModule,
  Project,
  ProjectBindTarget,
  ProjectModuleBind,
  ProjectRole,
  ServeFolder,
  ServeMetric,
  ServeScope,
  SpecDomainDraft,
  SpecRootDraft,
  Tenant,
  TenantEngineBind,
  TenantKnowledgeArticle,
  TenantScheduler,
  TenantOrgRole,
  WarehouseTable,
  WordRoot,
} from '../types';
import { ALL_AI_CAPS, ALL_MODULES } from '../config/iam';
import { defaultModulePolicy, productOf, SYNC_TARGET_LABEL } from '../config/products';
import { ALL_ENGINES } from '../config/knowledge';
import { DEFAULT_TENANT_ID, isMultiTenant } from '../config/runtime';
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
import { buildSpecPack, type SpecPack } from '../engine/specIo';
import { sameSnapshot, snapshotTable } from '../engine/modelVersion';
import { assessImpact, tableDependentsOf } from '../engine/impact';
import type { DwsMeasureInput } from '../engine/modeling';

const STORAGE_KEY = 'dw-ai.proto.0.1.5';

export function grantIsValid(g: TenantGrant): boolean {
  if (g.revoked) return false;
  if (g.kind === 'permanent') return true;
  if (!g.expiresAt) return false;
  return Date.parse(g.expiresAt) > Date.now();
}

function accessValidOn(
  grants: TenantGrant[],
  access: PlatformAccess[],
  userId: string,
  tenantId: string
): boolean {
  return access.some((a) => {
    if (a.userId !== userId || a.tenantId !== tenantId) return false;
    const g = grants.find((x) => x.id === a.grantId);
    return Boolean(g && grantIsValid(g));
  });
}

function hydrateIam(parsed: AppState): AppState {
  const fresh = createSeed();
  if (!Array.isArray(parsed.accounts) || !parsed.accounts.length) {
    parsed.accounts = fresh.accounts;
    parsed.userTenants = fresh.userTenants;
    parsed.members = Array.isArray(parsed.members) && parsed.members.length ? parsed.members : fresh.members;
    parsed.currentUserId = null;
    parsed.currentUser = '';
    parsed.currentTenantId = null;
    parsed.currentProjectId = null;
  }
  if (!Array.isArray(parsed.userTenants)) parsed.userTenants = fresh.userTenants;
  if (!Array.isArray(parsed.members)) parsed.members = fresh.members;
  if (!Array.isArray(parsed.grants)) parsed.grants = fresh.grants;
  if (!Array.isArray(parsed.platformAccess)) parsed.platformAccess = [];
  if (!parsed.grants.some((g) => g.code === 'XINGHE-DEMO')) {
    const demo = fresh.grants.find((g) => g.code === 'XINGHE-DEMO');
    if (demo) parsed.grants.push(demo);
  }
  if (parsed.currentUserId === undefined) {
    parsed.currentUserId = null;
    parsed.currentUser = '';
    parsed.currentTenantId = null;
  }
  if (!Array.isArray(parsed.services) || !parsed.services.length) {
    parsed.services = fresh.services ?? [];
  } else {
    parsed.services = parsed.services.filter((s) => s.product !== 'scheduler');
  }
  for (const t of parsed.tenants) {
    if (!t.status) t.status = 'active';
    if (!t.modules?.length) t.modules = [...ALL_MODULES];
    if (!Array.isArray(t.aiCaps)) t.aiCaps = [];
    if (!Array.isArray(t.endpoints)) t.endpoints = [];
    if (t.id === 't-xinghe') {
      for (const extra of ['metadata', 'quality', 'serve'] as ProductModule[]) {
        if (!t.modules.includes(extra)) t.modules = [...t.modules, extra];
      }
    }
    if (!t.modulePolicies?.length) {
      const seeded = fresh.tenants.find((x) => x.id === t.id);
      t.modulePolicies = seeded?.modulePolicies?.length
        ? seeded.modulePolicies.map((p) => ({ ...p }))
        : t.modules.map((m) => defaultModulePolicy(m));
    }
    t.modules = t.modules.filter((m) => m !== 'scheduler');
    t.modulePolicies = (t.modulePolicies ?? []).filter((p) => p.product !== 'scheduler');
    if (!t.scheduler && t.id === 't-xinghe') {
      const seeded = fresh.tenants.find((x) => x.id === t.id);
      if (seeded?.scheduler) t.scheduler = { ...seeded.scheduler };
    }
    if (!Array.isArray(t.engineBinds)) {
      const seeded = fresh.tenants.find((x) => x.id === t.id);
      t.engineBinds = seeded?.engineBinds ? seeded.engineBinds.map((e) => ({ ...e })) : [];
    }
  }
  for (const m of parsed.members) {
    if (!m.productRoles) m.productRoles = {};
    if (m.role === 'modeler' && !m.productRoles.warehouse) m.productRoles.warehouse = 'modeler';
    if (m.role === 'viewer' && !m.productRoles.warehouse) m.productRoles.warehouse = 'viewer';
    if (m.projectId === 'p-trade' && m.userId === 'u-li') {
      if (!m.productRoles.metadata) m.productRoles.metadata = 'analyst';
      if (!m.productRoles.quality) m.productRoles.quality = 'inspector';
    }
  }
  for (const p of parsed.projects) {
    if (!p.status) p.status = 'active';
    if (!Array.isArray(p.engines)) {
      p.engines = p.id === 'p-trade' ? [...ALL_ENGINES] : p.id === 'p-empty' ? ['hive'] : [];
    }
  }
  if (!Array.isArray(parsed.projectBinds) || !parsed.projectBinds.length) {
    parsed.projectBinds = fresh.projectBinds ?? [];
  }
  for (const g of parsed.grants) {
    if (!Array.isArray(g.aiCaps)) g.aiCaps = [];
    if (!Array.isArray(g.modules)) g.modules = [];
    if (!Array.isArray(g.projectIds)) g.projectIds = [];
    if (!Array.isArray(g.projectScopes)) g.projectScopes = [];
    if (!g.defaultRole) g.defaultRole = 'viewer';
  }
  const wang = fresh.accounts.find((a) => a.id === 'u-wang');
  if (wang && !parsed.accounts.some((a) => a.id === 'u-wang' || a.username === '王五')) {
    parsed.accounts.push(wang);
  }
  if (
    parsed.accounts.some((a) => a.id === 'u-wang') &&
    !parsed.userTenants.some((x) => x.userId === 'u-wang' && x.tenantId === 't-xinghe')
  ) {
    parsed.userTenants.push({ userId: 'u-wang', tenantId: 't-xinghe', role: 'member' });
  }
  if (!Array.isArray(parsed.knowledgeArticles)) parsed.knowledgeArticles = [];
  const liOnTrade = parsed.members.find((m) => m.projectId === 'p-trade' && m.userId === 'u-li');
  if (liOnTrade && liOnTrade.role === 'admin') liOnTrade.role = 'modeler';
  if (parsed.currentUserId && parsed.currentTenantId) {
    const acc = parsed.accounts.find((a) => a.id === parsed.currentUserId);
    if (
      acc?.platformAdmin &&
      !accessValidOn(parsed.grants, parsed.platformAccess, acc.id, parsed.currentTenantId)
    ) {
      parsed.currentTenantId = null;
      parsed.currentProjectId = null;
    }
  }
  return parsed;
}

function load(): AppState {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as AppState;
      if (parsed?.tenants?.length) {
        if (!Array.isArray(parsed.grades)) parsed.grades = [];
        if (!Array.isArray(parsed.tableVersions)) parsed.tableVersions = [];
        for (const t of parsed.tables ?? []) {
          if (!parsed.tableVersions.some((v) => v.tableId === t.id)) {
            parsed.tableVersions.push({
              id: `tv-${t.id}-1`,
              tableId: t.id,
              projectId: t.projectId,
              version: 1,
              createdAt: '2026-08-01 10:00',
              createdBy: '系统',
              note: '初始版本',
              snapshot: snapshotTable(t),
            });
          }
        }
        if (!Array.isArray(parsed.serveFolders) || !parsed.serveFolders.length) {
          const extra = seedServe('p-trade');
          parsed.serveFolders = extra.serveFolders;
          parsed.serveMetrics = extra.serveMetrics;
        }
        if (!Array.isArray(parsed.knowledgeArticles)) parsed.knowledgeArticles = [];
        return hydrateIam(parsed);
      }
    }
  } catch {
    /* ignore */
  }
  return createSeed();
}

const state = reactive<AppState>(load());

function persist() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    /* ignore */
  }
}

export const app = readonly(state);

export const currentTenant = computed(() => state.tenants.find((t) => t.id === state.currentTenantId));

export const tenantProjects = computed(() => {
  const list = state.projects.filter((p) => p.tenantId === state.currentTenantId);
  if (isTenantAdmin.value) return list;
  return list.filter((p) =>
    state.members.some((m) => m.projectId === p.id && m.userId === state.currentUserId)
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
export const projectTableVersions = computed(() => inProject(state.tableVersions ?? []));
export const projectDrafts = computed(() => inProject(state.drafts));
export const projectJobs = computed(() => inProject(state.jobs));
export const projectMetrics = computed(() => inProject(state.metrics));
export const projectLogs = computed(() => inProject(state.queryLogs));
export const projectClusters = computed(() => inProject(state.clusters));
export const projectRecs = computed(() => inProject(state.recs));
export const projectApiCalls = computed(() => inProject(state.apiCalls));
export const currentUser = computed(() => state.currentUser);
export const sessionAccount = computed(() =>
  state.accounts.find((a) => a.id === state.currentUserId) ?? null
);
export const isLoggedIn = computed(() => Boolean(state.currentUserId));
export const isPlatformAdmin = computed(() => Boolean(sessionAccount.value?.platformAdmin));
export const isRealTenantAdmin = computed(() => {
  if (!state.currentUserId || !state.currentTenantId) return false;
  return state.userTenants.some(
    (x) => x.userId === state.currentUserId && x.tenantId === state.currentTenantId && x.role === 'admin'
  );
});
export function hasPlatformTenantAccess(tenantId: string, userId = state.currentUserId): boolean {
  if (!userId) return false;
  return accessValidOn(state.grants, state.platformAccess, userId, tenantId);
}
export const isTenantAdmin = computed(() => {
  if (isRealTenantAdmin.value) return true;
  if (isPlatformAdmin.value && state.currentTenantId) {
    return hasPlatformTenantAccess(state.currentTenantId);
  }
  return false;
});

export function myTenants(): Tenant[] {
  if (!state.currentUserId) return [];
  if (!isMultiTenant()) {
    const t = state.tenants.find((x) => x.id === DEFAULT_TENANT_ID);
    return t ? [t] : [];
  }
  if (isPlatformAdmin.value) {
    return state.tenants.filter((t) => hasPlatformTenantAccess(t.id));
  }
  const ids = new Set(
    state.userTenants.filter((x) => x.userId === state.currentUserId).map((x) => x.tenantId)
  );
  return state.tenants.filter((t) => ids.has(t.id));
}

export function selectableTenants(): Tenant[] {
  const list = myTenants();
  if (isPlatformAdmin.value) return list;
  return list.filter((t) => t.status === 'active');
}

export function tenantUsers(): Account[] {
  if (!state.currentTenantId) return [];
  if (isPlatformAdmin.value) {
    const ids = new Set(
      state.userTenants.filter((x) => x.tenantId === state.currentTenantId).map((x) => x.userId)
    );
    return state.accounts.filter((a) => ids.has(a.id));
  }
  const ids = new Set(
    state.userTenants.filter((x) => x.tenantId === state.currentTenantId).map((x) => x.userId)
  );
  return state.accounts.filter((a) => ids.has(a.id));
}

export function accountDisplay(userId: string): string {
  return state.accounts.find((a) => a.id === userId)?.displayName ?? userId;
}

export function tenantRoleOf(userId: string, tenantId = state.currentTenantId): TenantOrgRole | null {
  if (!tenantId) return null;
  return state.userTenants.find((x) => x.userId === userId && x.tenantId === tenantId)?.role ?? null;
}

export const projectMemberList = computed(() =>
  state.members
    .filter((m) => m.projectId === state.currentProjectId)
    .map((m) => ({
      ...m,
      displayName: accountDisplay(m.userId),
      username: state.accounts.find((a) => a.id === m.userId)?.username ?? m.userId,
    }))
);

function requireTenantAdmin(): boolean {
  if (isTenantAdmin.value) return true;
  message.error('需要租户管理员权限');
  return false;
}

function requirePlatformAdmin(): boolean {
  if (!isMultiTenant()) {
    message.error('普通模式没有平台后台');
    return false;
  }
  if (isPlatformAdmin.value) return true;
  message.error('需要平台超级管理员权限');
  return false;
}

export function login(username: string, password: string): { account: Account; tenants: Tenant[] } {
  const name = username.trim();
  const acc = state.accounts.find((a) => a.username === name);
  if (!acc || acc.password !== password) {
    throw new Error('用户名或密码错误');
  }
  if (acc.status === 'disabled') {
    throw new Error('账号已停用');
  }
  if (!isMultiTenant() && acc.platformAdmin) {
    throw new Error('普通模式不提供平台账号');
  }
  state.currentUserId = acc.id;
  state.currentUser = acc.displayName;
  state.currentTenantId = null;
  state.currentProjectId = null;
  if (!isMultiTenant()) {
    state.currentTenantId = DEFAULT_TENANT_ID;
    persist();
    return { account: acc, tenants: state.tenants.filter((t) => t.id === DEFAULT_TENANT_ID) };
  }
  const tenants = selectableTenants();
  if (!acc.platformAdmin && tenants.length === 1) {
    state.currentTenantId = tenants[0].id;
  }
  persist();
  return { account: acc, tenants };
}

export function logout() {
  state.currentUserId = null;
  state.currentUser = '';
  state.currentTenantId = null;
  state.currentProjectId = null;
  persist();
}

export function updateOwnProfile(input: { displayName?: string }): boolean {
  const acc = sessionAccount.value;
  if (!acc) return false;
  const name = input.displayName?.trim();
  if (!name) {
    message.warning('请填写显示名');
    return false;
  }
  acc.displayName = name;
  state.currentUser = name;
  persist();
  message.success('个人信息已保存');
  return true;
}

export function changeOwnPassword(current: string, next: string): boolean {
  const acc = sessionAccount.value;
  if (!acc) return false;
  if (!current || !next) {
    message.warning('请填写当前密码和新密码');
    return false;
  }
  if (acc.password !== current) {
    message.error('当前密码不正确');
    return false;
  }
  if (next.length < 4) {
    message.warning('新密码至少 4 位');
    return false;
  }
  acc.password = next;
  persist();
  message.success('密码已修改');
  return true;
}

export function leaveTenant() {
  state.currentTenantId = null;
  state.currentProjectId = null;
  persist();
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

export function switchTenant(tenantId: string) {
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) {
    message.error('租户不存在');
    return false;
  }
  if (t.status === 'disabled' && !isPlatformAdmin.value) {
    message.error('租户已停用');
    return false;
  }
  if (isPlatformAdmin.value) {
    if (!hasPlatformTenantAccess(tenantId)) {
      message.error('需要该租户的授权码才能进入');
      return false;
    }
  } else if (!myTenants().some((x) => x.id === tenantId)) {
    message.error('你不属于该租户');
    return false;
  }
  state.currentTenantId = tenantId;
  state.currentProjectId = null;
  persist();
  return true;
}

export function isProjectAdmin(projectId = state.currentProjectId): boolean {
  if (!projectId || !state.currentUserId) return false;
  return state.members.some(
    (m) => m.projectId === projectId && m.userId === state.currentUserId && m.role === 'admin'
  );
}

export function currentProjectRole(): ProjectRole | null {
  if (!state.currentProjectId || !state.currentUserId) return null;
  return (
    state.members.find((m) => m.projectId === state.currentProjectId && m.userId === state.currentUserId)
      ?.role ?? null
  );
}

export function licensedProducts(tenantId = state.currentTenantId): ProductModule[] {
  const t = state.tenants.find((x) => x.id === tenantId);
  return t?.modules?.length ? t.modules : [];
}

/** 平台已开通且租户已启用 */
export function openedProducts(tenantId = state.currentTenantId): ProductModule[] {
  return licensedProducts(tenantId).filter((code) => modulePolicyOf(tenantId, code).enabled);
}

export function tenantEndpoints(tenantId = state.currentTenantId): ProductEndpoint[] {
  return state.tenants.find((x) => x.id === tenantId)?.endpoints ?? [];
}

export function platformServices(): PlatformService[] {
  return state.services ?? [];
}

export function serviceOf(product: ProductModule): PlatformService | undefined {
  return (state.services ?? []).find((s) => s.product === product);
}

export function serviceOnline(product: ProductModule): boolean {
  return serviceOf(product)?.status === 'online';
}

export function modulePolicyOf(tenantId: string | null | undefined, product: ProductModule) {
  const t = state.tenants.find((x) => x.id === tenantId);
  return t?.modulePolicies?.find((p) => p.product === product) ?? defaultModulePolicy(product);
}

export function canSeeModule(
  product: ProductModule,
  userId = state.currentUserId,
  tenantId = state.currentTenantId,
  projectId = state.currentProjectId
): boolean {
  if (!userId || !tenantId) return false;
  if (!licensedProducts(tenantId).includes(product)) return false;
  const policy = modulePolicyOf(tenantId, product);
  if (!policy.enabled) return false;
  const tenantAdmin = tenantRoleOf(userId, tenantId) === 'admin';
  const projectAdmin = Boolean(
    projectId &&
      state.members.some((m) => m.projectId === projectId && m.userId === userId && m.role === 'admin')
  );
  const vis: ModuleVisibleTo = policy.visibleTo;
  if (vis === 'tenant_admin') return tenantAdmin;
  if (vis === 'project_admin') return tenantAdmin || projectAdmin;
  if (vis === 'role_holders') return tenantAdmin || projectAdmin || Boolean(productRoleOf(userId, product, projectId));
  return Boolean(projectId && state.members.some((m) => m.projectId === projectId && m.userId === userId));
}

export function visibleProducts(
  userId = state.currentUserId,
  tenantId = state.currentTenantId
): ProductModule[] {
  return openedProducts(tenantId).filter((code) => canSeeModule(code, userId, tenantId));
}

export function productConnected(code: ProductModule, tenantId = state.currentTenantId): boolean {
  if (!openedProducts(tenantId).includes(code)) return false;
  return serviceOnline(code);
}

export function tenantScheduler(tenantId = state.currentTenantId): TenantScheduler | undefined {
  return state.tenants.find((x) => x.id === tenantId)?.scheduler;
}

export function schedulerReady(tenantId = state.currentTenantId): boolean {
  const s = tenantScheduler(tenantId);
  return Boolean(s?.enabled && s.baseUrl && s.status === 'ok');
}

export function tenantEngineBinds(tenantId = state.currentTenantId): TenantEngineBind[] {
  return state.tenants.find((x) => x.id === tenantId)?.engineBinds ?? [];
}

const SYNC_TARGETS: ProjectBindTarget[] = ['warehouse', 'metadata', 'quality', 'serve', 'scheduler'];

function tenantWantsTarget(tenant: Tenant | undefined, target: ProjectBindTarget): boolean {
  if (!tenant) return false;
  if (target === 'scheduler') return Boolean(tenant.scheduler?.enabled);
  const policy = tenant.modulePolicies?.find((p) => p.product === target);
  const enabled = policy ? policy.enabled : tenant.modules.includes(target);
  return tenant.modules.includes(target) && enabled;
}

function decideBind(project: Project, target: ProjectBindTarget): ProjectModuleBind {
  const tenant = state.tenants.find((x) => x.id === project.tenantId);
  const label = SYNC_TARGET_LABEL[target] ?? target;
  const at = stampNow();
  if (target === 'scheduler') {
    const ready = schedulerReady(project.tenantId);
    return {
      projectId: project.id,
      target,
      remoteId: project.id,
      status: ready ? 'ok' : 'pending',
      lastSyncAt: at,
      note: ready ? `本组织 DS 项目 ID = ${project.id}` : '调度集群未测通，项目位已占同一 ID',
    };
  }
  if (target === 'warehouse') {
    return {
      projectId: project.id,
      target,
      remoteId: project.id,
      status: 'ok',
      lastSyncAt: at,
      note: '组织项目即仓建设项目，ID 相同',
    };
  }
  const online = serviceOnline(target);
  return {
    projectId: project.id,
    target,
    remoteId: project.id,
    status: online ? 'ok' : 'pending',
    lastSyncAt: at,
    note: online ? `${label}侧项目 ID = ${project.id}` : '已占同一项目 ID，产品服务接通后直接用',
  };
}

function writeProjectBinds(project: Project) {
  const tenant = state.tenants.find((x) => x.id === project.tenantId);
  if (!state.projectBinds) state.projectBinds = [];
  const keep = state.projectBinds.filter((b) => b.projectId !== project.id);
  const next = SYNC_TARGETS.filter((t) => tenantWantsTarget(tenant, t)).map((t) => decideBind(project, t));
  state.projectBinds = [...keep, ...next];
}

export function projectBindsOf(projectId: string): ProjectModuleBind[] {
  return (state.projectBinds ?? []).filter((b) => b.projectId === projectId);
}

export function resyncProjectBinds(projectId: string) {
  const p = state.projects.find((x) => x.id === projectId);
  if (!p) return;
  writeProjectBinds(p);
  persist();
  message.success(`已按同一项目 ID ${p.id} 同步到各模块`);
}

export function productRoleOf(
  userId: string,
  product: ProductModule,
  projectId = state.currentProjectId
): string | undefined {
  if (!projectId) return undefined;
  const m = state.members.find((x) => x.projectId === projectId && x.userId === userId);
  if (!m) return undefined;
  if (m.role === 'admin') return productOf(product).adminRole;
  if (m.productRoles?.[product]) return m.productRoles[product];
  if (product === 'warehouse' && (m.role === 'modeler' || m.role === 'viewer')) return m.role;
  return undefined;
}

export function currentProductRole(product: ProductModule): string | undefined {
  if (!state.currentUserId) return undefined;
  return productRoleOf(state.currentUserId, product);
}

function licensedAiCaps(tenant: Tenant | undefined): AiCap[] {
  if (!tenant?.modules.includes('warehouse')) return [];
  if (!isMultiTenant()) return [...ALL_AI_CAPS];
  if (!tenant.aiCaps?.length) return [...ALL_AI_CAPS];
  return tenant.aiCaps.filter((c) => ALL_AI_CAPS.includes(c));
}

function currentBoundGrant(): TenantGrant | null {
  if (!isPlatformAdmin.value || !state.currentUserId || !state.currentTenantId) return null;
  const row = state.platformAccess.find(
    (a) => a.userId === state.currentUserId && a.tenantId === state.currentTenantId
  );
  if (!row) return null;
  const g = state.grants.find((x) => x.id === row.grantId);
  return g && grantIsValid(g) ? g : null;
}

export function effectiveAiCaps(): AiCap[] {
  const licensed = licensedAiCaps(currentTenant.value);
  const grant = currentBoundGrant();
  if (!grant?.aiCaps?.length) return licensed;
  return licensed.filter((c) => grant.aiCaps!.includes(c));
}

export function hasAiCap(cap: AiCap): boolean {
  return effectiveAiCaps().includes(cap);
}

/** 规范中心写操作：项目管理员，或仓建设「规范管理员」 */
export const canWriteSpec = computed(() => {
  if (isProjectAdmin()) return true;
  const r = currentProductRole('warehouse');
  return r === 'spec_admin';
});
/** 建模中心写操作：项目管理员，或仓建设规范管理员 / 建模工程师 */
export const canWriteModel = computed(() => {
  if (isProjectAdmin()) return true;
  const r = currentProductRole('warehouse');
  return r === 'spec_admin' || r === 'modeler';
});
export const canWriteMetadata = computed(() => {
  if (!productConnected('metadata')) return false;
  if (isProjectAdmin()) return true;
  const r = currentProductRole('metadata');
  return r === 'catalog_admin' || r === 'analyst';
});
export const canAdminMetadata = computed(() => {
  if (!productConnected('metadata')) return false;
  if (isProjectAdmin()) return true;
  return currentProductRole('metadata') === 'catalog_admin';
});

function requireSpecWrite(): boolean {
  if (canWriteSpec.value) return true;
  message.error('只有项目管理员能新增、修改或删除规范');
  return false;
}

function requireModelWrite(): boolean {
  if (canWriteModel.value) return true;
  message.error('需要项目管理员或建模工程师权限');
  return false;
}

function stampNow() {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

function recordVersion(table: WarehouseTable, note: string) {
  if (!Array.isArray(state.tableVersions)) state.tableVersions = [];
  const snap = snapshotTable(table);
  const prev = state.tableVersions
    .filter((v) => v.tableId === table.id)
    .sort((a, b) => b.version - a.version)[0];
  if (prev && sameSnapshot(prev.snapshot, snap)) return;
  const version = (prev?.version ?? 0) + 1;
  state.tableVersions.push({
    id: `tv-${table.id}-${version}-${Date.now()}`,
    tableId: table.id,
    projectId: table.projectId,
    version,
    createdAt: stampNow(),
    createdBy: state.currentUser || '系统',
    note,
    snapshot: snap,
  });
}

export function tableVersionsOf(tableId: string) {
  return projectTableVersions.value
    .filter((v) => v.tableId === tableId)
    .slice()
    .sort((a, b) => b.version - a.version);
}

export function currentTableVersion(tableId: string) {
  return tableVersionsOf(tableId)[0]?.version ?? 0;
}

export function applyBootSession(boot: {
  userId?: string | null;
  tenantId?: string | null;
  projectId?: string | null;
}): boolean {
  if (!boot.userId) return false;
  const acc = state.accounts.find((a) => a.id === boot.userId);
  if (!acc || acc.status === 'disabled') return false;
  state.currentUserId = acc.id;
  state.currentUser = acc.displayName;
  if (boot.tenantId && state.tenants.some((t) => t.id === boot.tenantId)) {
    state.currentTenantId = boot.tenantId;
  }
  persist();
  if (boot.projectId) return enterProject(boot.projectId);
  return true;
}

export function enterProject(projectId: string): boolean {
  const p = state.projects.find((x) => x.id === projectId);
  if (!p) return false;
  if ((p.status ?? 'active') === 'disabled') {
    message.error('项目已停用，不能进入');
    return false;
  }
  const member = state.members.some((m) => m.projectId === projectId && m.userId === state.currentUserId);
  if (!isTenantAdmin.value && !member) {
    message.error('你不是该项目成员');
    return false;
  }
  if (isPlatformAdmin.value) {
    const grant = currentBoundGrant();
    const allowed = grant?.projectIds ?? [];
    if (allowed.length && !allowed.includes(projectId)) {
      message.error('当前授权码不能进入该项目');
      return false;
    }
  }
  state.currentTenantId = p.tenantId;
  state.currentProjectId = p.id;
  persist();
  return true;
}

export function leaveProject() {
  state.currentProjectId = null;
  persist();
}

/** 选完租户后的落点：只有租户管理员进系统管理工作台，其他人进已加入的项目。 */
export function resolveTenantHome(): string {
  if (isRealTenantAdmin.value) return '/workbench/projects';
  const list = tenantProjects.value.filter((p) => (p.status ?? 'active') !== 'disabled');
  if (!list.length) return '/no-project';
  const keep = list.find((p) => p.id === state.currentProjectId);
  const id = keep?.id ?? list[0].id;
  if (state.currentProjectId !== id) enterProject(id);
  return '/app';
}

export function createProject(input: {
  code: string;
  name: string;
  description: string;
  adminUserId: string;
  bootstrapSpec?: boolean;
  engines?: EngineKind[];
}): Project | null {
  if (!requireTenantAdmin() || !state.currentTenantId) return null;
  const admin = state.accounts.find((a) => a.id === input.adminUserId);
  const inTenant = admin
    ? state.userTenants.some((x) => x.userId === admin.id && x.tenantId === state.currentTenantId)
    : false;
  if (!admin || !inTenant) {
    message.warning('请从本租户用户中指定项目管理员');
    return null;
  }
  const project: Project = {
    id: `p-${Date.now()}`,
    tenantId: state.currentTenantId,
    code: input.code,
    name: input.name,
    description: input.description,
    owner: admin.displayName,
    createdAt: new Date().toISOString().slice(0, 10),
    status: 'active',
    engines: sanitizeEngines(input.engines),
  };
  state.projects.push(project);
  state.members.push({ projectId: project.id, userId: admin.id, role: 'admin' });
  const techTime: WordRoot[] = state.roots
    .filter((r) => r.projectId === 'p-trade' && r.kind !== 'biz')
    .map((r) => ({ ...r, id: `${r.id}-${project.id}`, projectId: project.id }));
  state.roots.push(...techTime);
  if (input.bootstrapSpec) bootstrapProjectSpec(project.id);
  writeProjectBinds(project);
  persist();
  message.success(
    input.bootstrapSpec
      ? `项目「${project.name}」已创建（各模块项目 ID ${project.id}），并导入通用规范`
      : `项目「${project.name}」已创建，各模块项目 ID ${project.id}`
  );
  return project;
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
  if (!requireSpecWrite() || !state.currentProjectId) return null;
  if (projectDomains.value.some((d) => d.code === input.code.toUpperCase())) {
    message.error('主题域编码必须全局唯一');
    return null;
  }
  const d: Domain = {
    ...input,
    id: `d-${Date.now()}`,
    projectId: state.currentProjectId,
    code: input.code.toUpperCase(),
  };
  state.domains.push(d);
  persist();
  message.success(`主题域 ${d.code} 已登记`);
  return d;
}

export function addRoot(input: Omit<WordRoot, 'id' | 'projectId'>): WordRoot | null {
  if (!requireSpecWrite() || !state.currentProjectId) return null;
  if (projectRoots.value.some((r) => r.code === input.code)) {
    message.error('词根编码已存在');
    return null;
  }
  const r: WordRoot = { ...input, id: `r-${Date.now()}`, projectId: state.currentProjectId };
  state.roots.push(r);
  persist();
  message.success(`词根 ${r.code} 已入库`);
  return r;
}

export function updateDomain(
  id: string,
  input: Omit<Domain, 'id' | 'projectId'>
): Domain | null {
  if (!requireSpecWrite()) return null;
  const d = state.domains.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!d) return null;
  const code = input.code.toUpperCase();
  if (projectDomains.value.some((x) => x.code === code && x.id !== id)) {
    message.error('主题域编码必须全局唯一');
    return null;
  }
  Object.assign(d, { ...input, code });
  persist();
  message.success(`主题域 ${d.code} 已更新`);
  return d;
}

export function removeDomain(id: string) {
  if (!requireSpecWrite()) return;
  const d = state.domains.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!d) return;
  const pid = state.currentProjectId;
  state.domains = state.domains.filter((x) => x.id !== id);
  for (const other of state.domains) {
    if (other.projectId === pid) {
      other.related = other.related.filter((c) => c !== d.code);
    }
  }
  for (const r of state.roots) {
    if (r.projectId === pid && r.domain === d.code) r.domain = undefined;
  }
  for (const t of state.tables) {
    if (t.projectId === pid && t.domain === d.code) t.domain = undefined;
  }
  persist();
  message.success(`主题域 ${d.code} 已删除`);
}

export function updateRoot(id: string, input: Omit<WordRoot, 'id' | 'projectId'>): WordRoot | null {
  if (!requireSpecWrite()) return null;
  const r = state.roots.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!r) return null;
  if (projectRoots.value.some((x) => x.code === input.code && x.id !== id)) {
    message.error('词根编码已存在');
    return null;
  }
  Object.assign(r, input);
  persist();
  message.success(`词根 ${r.code} 已更新`);
  return r;
}

export function removeRoot(id: string) {
  if (!requireSpecWrite()) return;
  const r = state.roots.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!r) return;
  state.roots = state.roots.filter((x) => x.id !== id);
  persist();
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
  if (!requireSpecWrite() || !state.currentProjectId) return null;
  ensureProjectLayers();
  const layer = input.layer.toUpperCase();
  if (projectLayerRules.value.some((r) => r.layer === layer)) {
    message.error(`分层 ${layer} 已存在`);
    return null;
  }
  const row: LayerRule = { ...input, layer, projectId: state.currentProjectId };
  state.layerRules.push(row);
  persist();
  message.success(`分层 ${layer} 已新增`);
  return row;
}

export function updateLayer(prevLayer: string, input: Omit<LayerRule, 'projectId'>): LayerRule | null {
  if (!requireSpecWrite() || !state.currentProjectId) return null;
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
  persist();
  message.success(`分层 ${layer} 已更新`);
  return row;
}

export function removeLayer(layer: string) {
  if (!requireSpecWrite() || !state.currentProjectId) return;
  ensureProjectLayers();
  const before = state.layerRules.length;
  state.layerRules = state.layerRules.filter(
    (r) => !(r.projectId === state.currentProjectId && r.layer === layer)
  );
  if (state.layerRules.length === before) return;
  persist();
  message.success(`分层 ${layer} 已删除`);
}

export function addGrade(input: GradeDraft): DataGrade | null {
  if (!requireSpecWrite() || !state.currentProjectId) return null;
  const code = input.code.toUpperCase();
  if (projectGrades.value.some((g) => g.code === code)) {
    message.error('等级编码必须项目内唯一');
    return null;
  }
  const g: DataGrade = { ...input, code, id: `g-${Date.now()}`, projectId: state.currentProjectId };
  state.grades.push(g);
  persist();
  message.success(`等级 ${g.code} 已新增`);
  return g;
}

export function updateGrade(id: string, input: GradeDraft): DataGrade | null {
  if (!requireSpecWrite()) return null;
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
  persist();
  message.success(`等级 ${g.code} 已更新`);
  return g;
}

export function removeGrade(id: string) {
  if (!requireSpecWrite()) return;
  const g = state.grades.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!g) return;
  state.grades = state.grades.filter((x) => x.id !== id);
  for (const t of state.tables) {
    if (t.projectId !== state.currentProjectId) continue;
    if (t.grade === g.code) t.grade = undefined;
    for (const c of t.columns) if (c.grade === g.code) c.grade = undefined;
  }
  persist();
  message.success(`等级 ${g.code} 已删除`);
}

export function applyGradeTemplate(templateId: string, replace: boolean) {
  if (!requireSpecWrite() || !state.currentProjectId) return null;
  const tpl = GRADE_TEMPLATES.find((t) => t.id === templateId);
  if (!tpl) {
    message.error('模板不存在');
    return null;
  }
  const pid = state.currentProjectId;
  if (replace) {
    state.grades = state.grades.filter((g) => g.projectId !== pid);
  }
  let added = 0;
  let skipped = 0;
  tpl.grades.forEach((draft, i) => {
    const code = draft.code.toUpperCase();
    if (state.grades.some((g) => g.projectId === pid && g.code === code)) {
      skipped++;
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
  persist();
  message.success(
    replace
      ? `已替换为「${tpl.name}」，写入 ${added} 个等级`
      : `已导入「${tpl.name}」：新增 ${added}${skipped ? `，跳过 ${skipped}` : ''}`
  );
  return { added, skipped };
}

export function addTable(input: Omit<WarehouseTable, 'id' | 'projectId'>): WarehouseTable | null {
  if (!state.currentProjectId) return null;
  if (projectTables.value.some((t) => t.name === input.name)) {
    message.error('表名已存在');
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
  persist();
  message.success(`${t.name} 已保存为草稿，发布后才会生成版本`);
  return t;
}

export function updateTable(
  id: string,
  input: Omit<WarehouseTable, 'id' | 'projectId'>
): WarehouseTable | null {
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return null;
  if (projectTables.value.some((x) => x.name === input.name && x.id !== id)) {
    message.error('表名已存在');
    return null;
  }
  Object.assign(t, { ...input, status: 'draft', columns: input.columns.map((c) => ({ ...c })) });
  persist();
  message.success(`${t.name} 已保存为草稿，发布后才会生成新版本`);
  return t;
}

export function tableDependents(id: string) {
  return tableDependentsOf(id, projectTables.value).map((t) => ({
    kind: 'table' as const,
    id: t.id,
    name: t.name,
  }));
}

export function impactForUpdate(id: string, next: Omit<WarehouseTable, 'id' | 'projectId'>) {
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return null;
  return assessImpact(t, { ...t, ...next }, projectTables.value);
}

/** 当前工作副本相对上一发布版，对下游表字段的影响。 */
export function impactOfWorkingCopy(id: string) {
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return null;
  const last = tableVersionsOf(id)[0];
  if (!last) return assessImpact(t, t, projectTables.value);
  const before = { ...t, ...last.snapshot };
  return assessImpact(before, t, projectTables.value);
}

export function publishTable(id: string, note: string): boolean {
  if (!requireModelWrite()) return false;
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return false;
  const last = tableVersionsOf(id)[0];
  if (t.status === 'published' && last && sameSnapshot(last.snapshot, snapshotTable(t))) {
    message.info('没有未发布的变更');
    return false;
  }
  t.status = 'published';
  recordVersion(t, note.trim() || '发布');
  persist();
  message.success(`${t.name} 已发布为 v${currentTableVersion(id)}`);
  return true;
}

export function removeTable(id: string) {
  const t = state.tables.find((x) => x.id === id && x.projectId === state.currentProjectId);
  if (!t) return;
  const deps = tableDependents(id);
  if (deps.length) {
    message.warning(`已删除本表。下游仍在：${deps.map((d) => d.name).join('、')}`);
  }
  state.tables = state.tables.filter((x) => x.id !== id);
  state.tableVersions = (state.tableVersions ?? []).filter((v) => v.tableId !== id);
  state.qualityRules = state.qualityRules.filter((q) => q.table !== t.name);
  persist();
  message.success(`${t.name} 已删除`);
}

export function applyModelProposal(drafts: ModelTableDraft[]): number {
  if (!requireModelWrite() || !state.currentProjectId) return 0;
  let n = 0;
  for (const d of drafts) {
    const payload: Omit<WarehouseTable, 'id' | 'projectId'> = {
      layer: d.layer,
      name: d.name,
      comment: d.comment,
      domain: d.domain,
      grain: d.grain,
      period: d.period ?? 'di',
      partition: d.partition ?? 'dt',
      grade: d.grade,
      status: 'draft',
      columns: d.columns.map((c) => ({ ...c })),
    };
    if (d.mode === 'update' && d.tableId) {
      const t = state.tables.find((x) => x.id === d.tableId && x.projectId === state.currentProjectId);
      if (!t) continue;
      payload.status = 'draft';
      payload.createdFrom = t.createdFrom;
      payload.sourceSystem = t.sourceSystem;
      payload.sources = d.sources ?? t.sources;
      payload.joins = d.joins ?? t.joins;
      Object.assign(t, { ...payload, columns: payload.columns.map((c) => ({ ...c })) });
      n++;
      continue;
    }
    if (projectTables.value.some((x) => x.name === d.name && x.id !== d.tableId)) {
      message.error(`${d.name} 表名已存在`);
      continue;
    }
    const created: WarehouseTable = {
      ...payload,
      status: 'draft',
      id: `tbl-${Date.now()}-${n}`,
      projectId: state.currentProjectId,
      columns: payload.columns.map((c) => ({ ...c })),
    };
    state.tables.push(created);
    n++;
  }
  persist();
  if (n) message.success(`已写入 ${n} 张表为草稿，发布后才会生成版本`);
  return n;
}

export function restoreTableVersion(tableId: string, versionId: string): boolean {
  if (!requireModelWrite()) return false;
  const ver = (state.tableVersions ?? []).find((v) => v.id === versionId && v.tableId === tableId);
  const t = state.tables.find((x) => x.id === tableId && x.projectId === state.currentProjectId);
  if (!ver || !t) {
    message.error('版本不存在');
    return false;
  }
  const s = ver.snapshot;
  Object.assign(t, {
    name: s.name,
    comment: s.comment,
    domain: s.domain,
    grain: s.grain,
    period: s.period,
    partition: s.partition,
    status: 'draft',
    grade: s.grade,
    createdFrom: s.createdFrom,
    sources: s.sources?.map((x) => ({ ...x })),
    joins: s.joins?.map((x) => ({ ...x })),
    filter: s.filter,
    columns: s.columns.map((c) => ({ ...c })),
  });
  persist();
  message.success(`${t.name} 已切回 v${ver.version} 的结构（草稿），发布后才会生成新版本`);
  return true;
}

export function applySpecProposal(input: {
  domains: SpecDomainDraft[];
  layers: LayerRule[];
  roots: SpecRootDraft[];
  grades?: GradeDraft[];
  overwrite: boolean;
}) {
  if (!requireSpecWrite() || !state.currentProjectId) return null;
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

  if (input.layers.length) {
    state.layerRules = state.layerRules.filter((r) => r.projectId !== pid);
    for (const l of input.layers) {
      state.layerRules.push({ ...l, projectId: pid });
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

  persist();
  const parts = [
    `主题域 +${addedDomains}`,
    updatedDomains ? `更新${updatedDomains}` : '',
    skippedDomains ? `跳过${skippedDomains}` : '',
    input.layers.length ? `分层 ${input.layers.length} 条已覆盖` : '',
    `词根 +${addedRoots}`,
    updatedRoots ? `更新${updatedRoots}` : '',
    skippedRoots ? `跳过${skippedRoots}` : '',
    input.grades?.length ? `等级 +${addedGrades}` : '',
    updatedGrades ? `更新${updatedGrades}` : '',
    skippedGrades ? `跳过${skippedGrades}` : '',
  ].filter(Boolean);
  message.success(`已同步进当前项目：${parts.join('，')}`);
  return { addedDomains, updatedDomains, skippedDomains, addedRoots, updatedRoots, skippedRoots, addedGrades };
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
  if (!requireSpecWrite() || !state.currentProjectId) return null;
  const pid = state.currentProjectId;
  const stamp = Date.now();
  if (opts.replace) {
    state.domains = state.domains.filter((d) => d.projectId !== pid);
    state.roots = state.roots.filter((r) => r.projectId !== pid);
    state.layerRules = state.layerRules.filter((r) => r.projectId !== pid);
    state.grades = state.grades.filter((g) => g.projectId !== pid);
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
      } else skippedDomains++;
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
      } else skippedLayers++;
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
      } else skippedRoots++;
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
      } else skippedGrades++;
      return;
    }
    state.grades.push({ ...g, code, id: `g-${stamp}-${i}`, projectId: pid });
    addedGrades++;
  });

  persist();
  const parts = [
    `主题域 +${addedDomains}`,
    updatedDomains ? `更新${updatedDomains}` : '',
    skippedDomains ? `跳过${skippedDomains}` : '',
    `分层 +${addedLayers}`,
    updatedLayers ? `更新${updatedLayers}` : '',
    skippedLayers ? `跳过${skippedLayers}` : '',
    `词根 +${addedRoots}`,
    updatedRoots ? `更新${updatedRoots}` : '',
    skippedRoots ? `跳过${skippedRoots}` : '',
    `等级 +${addedGrades}`,
    updatedGrades ? `更新${updatedGrades}` : '',
    skippedGrades ? `跳过${skippedGrades}` : '',
  ].filter(Boolean);
  message.success(`已导入当前项目：${parts.join('，')}`);
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
  };
}

export function runOdsToDwd(sourceId: string) {
  if (!state.currentProjectId) return null;
  const source = state.tables.find((t) => t.id === sourceId);
  if (!source) {
    message.error('找不到源表');
    return null;
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
  persist();
  message.success('AI 已生成 DWD 草案，请审核');
  return draft;
}

export function runDwdToDws(sourceIds: string | string[], dims?: string[], measures?: DwsMeasureInput[]) {
  if (!state.currentProjectId) return null;
  const ids = Array.isArray(sourceIds) ? sourceIds : [sourceIds];
  const sources = ids.map((id) => state.tables.find((t) => t.id === id)).filter((t): t is WarehouseTable => Boolean(t));
  if (!sources.length) return null;
  const dwsRule = projectLayerRules.value.find((r) => r.layer === 'DWS');
  const draft = buildDwsDraft({
    projectId: state.currentProjectId,
    sources,
    domains: projectDomains.value,
    preferredDims: dims,
    measures,
    layerRule: hydrateLayerRule(dwsRule ?? { layer: 'DWS', naming: '', retention: '', serve: 'approval', note: '' }),
    grades: projectGrades.value,
    roots: projectRoots.value,
  });
  state.drafts.unshift(draft);
  persist();
  message.success('AI 已生成 DWS 草案，请审核');
  return draft;
}

export function approveDraft(draftId: string) {
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
  persist();
  message.success(`${table.name} 已发布，ETL 任务已挂入调度`);
}

export function rejectDraft(draftId: string) {
  const draft = state.drafts.find((d) => d.id === draftId);
  if (!draft) return;
  draft.status = 'rejected';
  persist();
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

function ensureUserInTenant(userId: string, tenantId: string, role: TenantOrgRole) {
  const row = state.userTenants.find((x) => x.userId === userId && x.tenantId === tenantId);
  if (row) row.role = role;
  else state.userTenants.push({ userId, tenantId, role });
}

export function createTenant(input: {
  code: string;
  name: string;
  modules: ProductModule[];
  aiCaps?: AiCap[];
  admin:
    | { mode: 'existing'; userId: string }
    | { mode: 'new'; username: string; displayName: string; password: string };
}): Tenant | null {
  if (!requirePlatformAdmin()) return null;
  const code = input.code.trim().toLowerCase();
  const name = input.name.trim();
  if (!code || !name) {
    message.warning('请填写编码和名称');
    return null;
  }
  if (state.tenants.some((t) => t.code === code)) {
    message.error('租户编码已存在');
    return null;
  }
  let admin: Account | undefined;
  if (input.admin.mode === 'existing') {
    const pick = input.admin;
    admin = state.accounts.find((a) => a.id === pick.userId);
    if (!admin) {
      message.error('请指定租户管理员');
      return null;
    }
  } else {
    const username = input.admin.username.trim();
    if (!username || !input.admin.password) {
      message.warning('请填写新管理员的用户名和密码');
      return null;
    }
    if (state.accounts.some((a) => a.username === username)) {
      message.error('用户名已被占用');
      return null;
    }
    admin = {
      id: `u-${Date.now()}`,
      username,
      displayName: input.admin.displayName.trim() || username,
      password: input.admin.password,
      platformAdmin: false,
      status: 'active',
    };
    state.accounts.push(admin);
  }
  const tenant: Tenant = {
    id: `t-${Date.now()}`,
    code,
    name,
    owner: admin.displayName,
    status: 'active',
    modules: input.modules.length ? input.modules : ['warehouse'],
    modulePolicies: (input.modules.length ? input.modules : (['warehouse'] as ProductModule[])).map((m) =>
      defaultModulePolicy(m)
    ),
    aiCaps: input.modules.includes('warehouse') ? [...ALL_AI_CAPS] : [],
    endpoints: [],
  };
  state.tenants.push(tenant);
  ensureUserInTenant(admin.id, tenant.id, 'admin');
  const project: Project = {
    id: `p-${Date.now()}`,
    tenantId: tenant.id,
    code: 'default',
    name: '默认项目',
    description: '新建租户的默认空项目，可在此开始建模',
    owner: admin.displayName,
    createdAt: new Date().toISOString().slice(0, 10),
    status: 'active',
    engines: ['hive'],
  };
  state.projects.push(project);
  state.members.push({ projectId: project.id, userId: admin.id, role: 'admin' });
  writeProjectBinds(project);
  persist();
  message.success(`租户「${tenant.name}」已创建`);
  return tenant;
}

export function setTenantStatus(tenantId: string, status: Tenant['status']) {
  if (!requirePlatformAdmin()) return;
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) return;
  t.status = status;
  if (status === 'disabled' && state.currentTenantId === tenantId) {
    state.currentTenantId = null;
    state.currentProjectId = null;
  }
  persist();
  message.success(status === 'disabled' ? `已停用「${t.name}」` : `已启用「${t.name}」`);
}

export function setTenantName(tenantId: string, name: string): boolean {
  if (!requirePlatformAdmin()) return false;
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) return false;
  const next = name.trim();
  if (!next) {
    message.warning('请填写名称');
    return false;
  }
  t.name = next;
  persist();
  return true;
}

export function setTenantModules(tenantId: string, modules: ProductModule[]) {
  if (!requirePlatformAdmin()) return;
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) return;
  t.modules = modules.length ? modules : ['warehouse'];
  t.aiCaps = t.modules.includes('warehouse') ? [...ALL_AI_CAPS] : [];
  const keep = (t.modulePolicies ?? []).filter((p) => t.modules.includes(p.product));
  for (const m of t.modules) {
    if (!keep.some((p) => p.product === m)) keep.push(defaultModulePolicy(m));
  }
  t.modulePolicies = keep;
  persist();
  message.success(`「${t.name}」开通上限已更新，租户内仍可再裁`);
}

export function setTenantAdmin(
  tenantId: string,
  admin:
    | { mode: 'existing'; userId: string }
    | { mode: 'new'; username: string; displayName: string; password: string }
) {
  if (!requirePlatformAdmin()) return;
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) return;
  let acc: Account | undefined;
  if (admin.mode === 'existing') {
    acc = state.accounts.find((a) => a.id === admin.userId);
    if (!acc) {
      message.error('请选择账号');
      return;
    }
  } else {
    const username = admin.username.trim();
    if (!username || !admin.password) {
      message.warning('请填写用户名和密码');
      return;
    }
    if (state.accounts.some((a) => a.username === username)) {
      message.error('用户名已被占用');
      return;
    }
    acc = {
      id: `u-${Date.now()}`,
      username,
      displayName: admin.displayName.trim() || username,
      password: admin.password,
      platformAdmin: false,
      status: 'active',
    };
    state.accounts.push(acc);
  }
  ensureUserInTenant(acc.id, tenantId, 'admin');
  t.owner = acc.displayName;
  persist();
  message.success(`「${t.name}」管理员已设为 ${acc.displayName}`);
}

export function createOrgUser(input: {
  username: string;
  displayName: string;
  password: string;
  role: TenantOrgRole;
  existingUserId?: string;
}): Account | null {
  if (!requireTenantAdmin() || !state.currentTenantId) return null;
  let acc: Account | undefined;
  if (input.existingUserId) {
    acc = state.accounts.find((a) => a.id === input.existingUserId);
    if (!acc) {
      message.error('账号不存在');
      return null;
    }
    const existing = acc;
    if (state.userTenants.some((x) => x.userId === existing.id && x.tenantId === state.currentTenantId)) {
      message.warning('该用户已在本租户');
      return null;
    }
  } else {
    const username = input.username.trim();
    if (!username || !input.password) {
      message.warning('请填写用户名和密码');
      return null;
    }
    if (state.accounts.some((a) => a.username === username)) {
      message.error('用户名已被占用，可改为「拉入已有账号」');
      return null;
    }
    acc = {
      id: `u-${Date.now()}`,
      username,
      displayName: input.displayName.trim() || username,
      password: input.password,
      platformAdmin: false,
      status: 'active',
    };
    state.accounts.push(acc);
  }
  ensureUserInTenant(acc.id, state.currentTenantId, input.role);
  persist();
  message.success(`已加入 ${acc.displayName}`);
  return acc;
}

export function setAccountStatus(userId: string, status: AccountStatus) {
  if (!requireTenantAdmin()) return;
  const acc = state.accounts.find((a) => a.id === userId);
  if (!acc) return;
  if (acc.platformAdmin) {
    message.error('不能停用平台超级管理员');
    return;
  }
  acc.status = status;
  persist();
  message.success(status === 'disabled' ? `已停用 ${acc.displayName}` : `已启用 ${acc.displayName}`);
}

export function resetAccountPassword(userId: string, password: string) {
  if (!requireTenantAdmin()) return;
  if (!password) {
    message.warning('请填写新密码');
    return;
  }
  const acc = state.accounts.find((a) => a.id === userId);
  if (!acc) return;
  acc.password = password;
  persist();
  message.success(`已重置 ${acc.displayName} 的密码`);
}

export function setUserTenantRole(userId: string, role: TenantOrgRole) {
  if (!requireTenantAdmin() || !state.currentTenantId) return;
  const row = state.userTenants.find((x) => x.userId === userId && x.tenantId === state.currentTenantId);
  if (!row) return;
  row.role = role;
  if (role === 'admin') {
    const acc = state.accounts.find((a) => a.id === userId);
    const t = state.tenants.find((x) => x.id === state.currentTenantId);
    if (acc && t) t.owner = acc.displayName;
  }
  persist();
  message.success('租户角色已更新');
}

export function patchOrgUser(input: {
  userId: string;
  displayName: string;
  username: string;
  tenantRole: TenantOrgRole;
  status: AccountStatus;
  memberships: { projectId: string; role: ProjectRole }[];
}): boolean {
  if (!requireTenantAdmin() || !state.currentTenantId) return false;
  const acc = state.accounts.find((a) => a.id === input.userId);
  if (!acc) return false;
  const name = input.displayName.trim();
  const username = input.username.trim();
  if (!name) {
    message.warning('请填写显示名');
    return false;
  }
  if (!username) {
    message.warning('请填写用户名');
    return false;
  }
  if (state.accounts.some((a) => a.id !== acc.id && a.username === username)) {
    message.error('用户名已被占用');
    return false;
  }
  if (input.status === 'disabled' && acc.id === state.currentUserId) {
    message.error('不能停用自己');
    return false;
  }
  if (input.status === 'disabled' && acc.platformAdmin) {
    message.error('不能停用平台超级管理员');
    return false;
  }
  acc.displayName = name;
  acc.username = username;
  acc.status = input.status;
  if (acc.id === state.currentUserId) state.currentUser = name;
  const roleRow = state.userTenants.find(
    (x) => x.userId === acc.id && x.tenantId === state.currentTenantId
  );
  if (roleRow) {
    roleRow.role = input.tenantRole;
    if (input.tenantRole === 'admin') {
      const t = state.tenants.find((x) => x.id === state.currentTenantId);
      if (t) t.owner = acc.displayName;
    }
  }
  const tenantPids = new Set(
    state.projects.filter((p) => p.tenantId === state.currentTenantId).map((p) => p.id)
  );
  state.members = state.members.filter((m) => {
    if (m.userId !== acc.id) return true;
    if (!tenantPids.has(m.projectId)) return true;
    return input.memberships.some((x) => x.projectId === m.projectId);
  });
  for (const mem of input.memberships) {
    if (!tenantPids.has(mem.projectId)) continue;
    const row = state.members.find((m) => m.projectId === mem.projectId && m.userId === acc.id);
    if (row) row.role = mem.role;
    else state.members.push({ projectId: mem.projectId, userId: acc.id, role: mem.role });
    if (mem.role === 'admin') {
      const p = state.projects.find((x) => x.id === mem.projectId);
      if (p) p.owner = acc.displayName;
    }
  }
  persist();
  message.success('已保存');
  return true;
}

export function removeUserFromOrg(userId: string): boolean {
  if (!requireTenantAdmin() || !state.currentTenantId) return false;
  if (userId === state.currentUserId) {
    message.error('不能删除自己');
    return false;
  }
  const acc = state.accounts.find((a) => a.id === userId);
  if (!acc) return false;
  const tenantId = state.currentTenantId;
  state.userTenants = state.userTenants.filter((x) => !(x.userId === userId && x.tenantId === tenantId));
  const pids = new Set(state.projects.filter((p) => p.tenantId === tenantId).map((p) => p.id));
  state.members = state.members.filter((m) => !(m.userId === userId && pids.has(m.projectId)));
  persist();
  message.success(`已从本组织移除 ${acc.displayName}`);
  return true;
}

function sanitizeEngines(engines?: EngineKind[]) {
  const allowed = new Set(ALL_ENGINES);
  return (engines ?? []).filter((e) => allowed.has(e));
}

export function patchProject(
  projectId: string,
  input: {
    name: string;
    code: string;
    description: string;
    adminUserId: string;
    status: 'active' | 'disabled';
    engines?: EngineKind[];
  }
): Project | null {
  if (!requireTenantAdmin() || !state.currentTenantId) return null;
  const p = state.projects.find((x) => x.id === projectId && x.tenantId === state.currentTenantId);
  if (!p) return null;
  const name = input.name.trim();
  const code = input.code.trim();
  if (!name) {
    message.warning('请填写名称');
    return null;
  }
  if (!code) {
    message.warning('请填写编码');
    return null;
  }
  if (state.projects.some((x) => x.tenantId === state.currentTenantId && x.code === code && x.id !== projectId)) {
    message.error('项目编码已存在');
    return null;
  }
  const admin = state.accounts.find((a) => a.id === input.adminUserId);
  const inTenant = admin
    ? state.userTenants.some((x) => x.userId === admin.id && x.tenantId === state.currentTenantId)
    : false;
  if (!admin || !inTenant) {
    message.warning('请从本租户用户中指定项目管理员');
    return null;
  }
  p.name = name;
  p.code = code;
  p.description = input.description;
  p.status = input.status;
  if (input.engines) p.engines = sanitizeEngines(input.engines);
  p.owner = admin.displayName;
  const mem = state.members.find((m) => m.projectId === p.id && m.userId === admin.id);
  if (mem) mem.role = 'admin';
  else state.members.push({ projectId: p.id, userId: admin.id, role: 'admin' });
  if (input.status === 'disabled' && state.currentProjectId === p.id) {
    state.currentProjectId = null;
  }
  persist();
  message.success('已保存');
  return p;
}

export function setProjectEngines(projectId: string, engines: EngineKind[]) {
  if (!requireTenantAdmin() || !state.currentTenantId) return;
  const p = state.projects.find((x) => x.id === projectId && x.tenantId === state.currentTenantId);
  if (!p) return;
  p.engines = sanitizeEngines(engines);
  persist();
}

export const tenantKnowledgeArticles = computed(() =>
  (state.knowledgeArticles ?? []).filter((a) => a.tenantId === state.currentTenantId)
);

export function importKnowledgeArticles(
  rows: TenantKnowledgeArticle[],
  mode: 'merge' | 'replace-engine' = 'merge'
) {
  if (!requireTenantAdmin() || !state.currentTenantId) return 0;
  if (!Array.isArray(state.knowledgeArticles)) state.knowledgeArticles = [];
  const tenantId = state.currentTenantId;
  const incoming = rows.filter((a) => a.tenantId === tenantId);
  if (!incoming.length) {
    message.warning('没有可导入的篇');
    return 0;
  }
  if (mode === 'replace-engine') {
    const engines = new Set(incoming.map((a) => a.engine));
    state.knowledgeArticles = state.knowledgeArticles.filter(
      (a) => a.tenantId !== tenantId || !engines.has(a.engine)
    );
  }
  const kept = state.knowledgeArticles.filter((a) => {
    if (a.tenantId !== tenantId) return true;
    return !incoming.some((n) => n.engine === a.engine && n.id === a.id);
  });
  state.knowledgeArticles = [...kept, ...incoming];
  persist();
  message.success(`已导入 ${incoming.length} 篇`);
  return incoming.length;
}

export function removeImportedArticle(articleId: string, engine: EngineKind) {
  if (!requireTenantAdmin() || !state.currentTenantId) return;
  const before = (state.knowledgeArticles ?? []).length;
  state.knowledgeArticles = (state.knowledgeArticles ?? []).filter(
    (a) => !(a.tenantId === state.currentTenantId && a.engine === engine && a.id === articleId)
  );
  if (state.knowledgeArticles.length === before) return;
  persist();
  message.success('已删除导入篇');
}

export function removeProject(projectId: string): boolean {
  if (!requireTenantAdmin() || !state.currentTenantId) return false;
  const p = state.projects.find((x) => x.id === projectId && x.tenantId === state.currentTenantId);
  if (!p) return false;
  state.projects = state.projects.filter((x) => x.id !== projectId);
  state.members = state.members.filter((m) => m.projectId !== projectId);
  state.domains = state.domains.filter((d) => d.projectId !== projectId);
  state.layerRules = state.layerRules.filter((r) => r.projectId !== projectId);
  state.grades = state.grades.filter((g) => g.projectId !== projectId);
  state.roots = state.roots.filter((r) => r.projectId !== projectId);
  state.tables = state.tables.filter((t) => t.projectId !== projectId);
  state.tableVersions = (state.tableVersions ?? []).filter((v) => v.projectId !== projectId);
  state.drafts = state.drafts.filter((d) => d.projectId !== projectId);
  state.jobs = state.jobs.filter((j) => j.projectId !== projectId);
  state.projectBinds = (state.projectBinds ?? []).filter((b) => b.projectId !== projectId);
  if (state.currentProjectId === projectId) state.currentProjectId = null;
  persist();
  message.success(`项目「${p.name}」已删除`);
  return true;
}

export function appointProjectAdmin(userId: string, projectId: string) {
  if (!requireTenantAdmin()) return;
  const project = state.projects.find((p) => p.id === projectId && p.tenantId === state.currentTenantId);
  if (!project) {
    message.error('项目不属于当前租户');
    return;
  }
  if (!state.userTenants.some((x) => x.userId === userId && x.tenantId === state.currentTenantId)) {
    message.error('只能指定本租户用户');
    return;
  }
  const row = state.members.find((m) => m.projectId === projectId && m.userId === userId);
  if (row) row.role = 'admin';
  else state.members.push({ projectId, userId, role: 'admin' });
  const acc = state.accounts.find((a) => a.id === userId);
  if (acc) project.owner = acc.displayName;
  persist();
  message.success(`已指定 ${acc?.displayName ?? ''} 为「${project.name}」项目管理员`);
}

export function setMemberRole(userId: string, role: ProjectRole) {
  if (!state.currentProjectId) return;
  if (!isProjectAdmin()) {
    message.error('只有项目管理员能拉人和改角色');
    return;
  }
  const row = state.members.find((m) => m.projectId === state.currentProjectId && m.userId === userId);
  if (row) row.role = role;
  else state.members.push({ projectId: state.currentProjectId, userId, role, productRoles: {} });
  persist();
}

export function setOrgProjectKind(userId: string, kind: 'admin' | 'member') {
  if (kind === 'admin') {
    setMemberRole(userId, 'admin');
    return;
  }
  const row = state.members.find((m) => m.projectId === state.currentProjectId && m.userId === userId);
  const wh = row?.productRoles?.warehouse;
  setMemberRole(userId, wh === 'viewer' ? 'viewer' : 'modeler');
}

export function setProductRole(userId: string, product: ProductModule, roleCode: string | undefined) {
  if (!state.currentProjectId) return;
  if (!isProjectAdmin()) {
    message.error('只有项目管理员能拉人和改角色');
    return;
  }
  let row = state.members.find((m) => m.projectId === state.currentProjectId && m.userId === userId);
  if (!row) {
    row = { projectId: state.currentProjectId, userId, role: 'modeler', productRoles: {} };
    state.members.push(row);
  }
  if (!row.productRoles) row.productRoles = {};
  if (!roleCode) delete row.productRoles[product];
  else row.productRoles[product] = roleCode;
  if (row.role !== 'admin' && product === 'warehouse') {
    row.role = roleCode === 'viewer' ? 'viewer' : 'modeler';
  }
  persist();
}

export function upsertProductEndpoint(tenantId: string, next: ProductEndpoint) {
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) return;
  if (!t.endpoints) t.endpoints = [];
  const i = t.endpoints.findIndex((e) => e.product === next.product);
  if (i >= 0) t.endpoints[i] = { ...t.endpoints[i], ...next };
  else t.endpoints.push(next);
  persist();
}

export function setModulePolicy(
  tenantId: string,
  product: ProductModule,
  patch: { enabled?: boolean; visibleTo?: ModuleVisibleTo }
) {
  if (!requireTenantAdmin()) return;
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) return;
  if (!t.modules.includes(product)) {
    message.error('平台未给本组织开通此产品');
    return;
  }
  if (!t.modulePolicies) t.modulePolicies = t.modules.map((m) => defaultModulePolicy(m));
  const i = t.modulePolicies.findIndex((p) => p.product === product);
  const cur = i >= 0 ? t.modulePolicies[i] : defaultModulePolicy(product);
  const next = { ...cur, ...patch, product };
  if (i >= 0) t.modulePolicies[i] = next;
  else t.modulePolicies.push(next);
  for (const p of state.projects.filter((x) => x.tenantId === tenantId)) writeProjectBinds(p);
  persist();
  const label = productOf(product).label;
  if (patch.enabled !== undefined) {
    message.success(`${label}：${next.enabled ? '已启用' : '已关闭'}`);
  } else {
    message.success(`${label} 可见范围已更新`);
  }
}

function maskToken(raw: string) {
  const s = raw.trim();
  if (!s) return '';
  return s.length <= 4 ? '••••' : `••••${s.slice(-4)}`;
}

export function setTenantScheduler(tenantId: string, patch: Partial<TenantScheduler>) {
  if (!requireTenantAdmin()) return;
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) return;
  const cur: TenantScheduler = t.scheduler ?? {
    provider: 'dolphinscheduler',
    enabled: false,
    baseUrl: '',
    status: 'unconfigured',
  };
  const next: TenantScheduler = { ...cur, ...patch, provider: 'dolphinscheduler' };
  if (patch.token !== undefined) {
    next.token = patch.token;
    next.tokenMasked = maskToken(patch.token);
    if (next.status === 'ok') next.status = 'unconfigured';
  }
  t.scheduler = next;
  persist();
}

export function testTenantScheduler(tenantId: string): boolean {
  if (!requireTenantAdmin()) return false;
  const t = state.tenants.find((x) => x.id === tenantId);
  const s = t?.scheduler;
  if (!s) {
    message.error('先填写调度地址和 Token');
    return false;
  }
  const urlOk = /^https?:\/\//i.test(s.baseUrl.trim());
  const tokenOk = Boolean(s.token?.trim());
  s.lastTestAt = stampNow();
  if (!urlOk) {
    s.status = 'error';
    s.note = '地址须以 http:// 或 https:// 开头';
    persist();
    message.error(s.note);
    return false;
  }
  if (!tokenOk) {
    s.status = 'error';
    s.note = 'Token 在 DolphinScheduler 安全中心生成后粘贴到这里';
    persist();
    message.error(s.note);
    return false;
  }
  s.status = 'ok';
  s.note = '原型：格式通过（不发真实请求）。发布模型后的调度操作走本组织这条 DS。';
  for (const p of state.projects.filter((x) => x.tenantId === tenantId)) writeProjectBinds(p);
  persist();
  message.success('已测通（演示）');
  return true;
}

export function setTenantEngineBind(tenantId: string, next: TenantEngineBind) {
  if (!requireTenantAdmin()) return;
  const t = state.tenants.find((x) => x.id === tenantId);
  if (!t) return;
  if (!t.engineBinds) t.engineBinds = [];
  const i = t.engineBinds.findIndex((e) => e.kind === next.kind);
  if (i >= 0) t.engineBinds[i] = next;
  else t.engineBinds.push(next);
  persist();
  message.success(`${next.name} 已保存（连接后期再填）`);
}

export function registerService(input: {
  product: ProductModule;
  name: string;
  baseUrl: string;
  source?: PlatformService['source'];
  note?: string;
}): PlatformService | null {
  if (!requirePlatformAdmin()) return null;
  const name = input.name.trim();
  const baseUrl = input.baseUrl.trim();
  if (!name || !baseUrl) {
    message.warning('请填写服务名和地址');
    return null;
  }
  if (!/^https?:\/\//i.test(baseUrl)) {
    message.error('地址须以 http:// 或 https:// 开头');
    return null;
  }
  if (!state.services) state.services = [];
  const existing = state.services.find((s) => s.product === input.product);
  const row: PlatformService = {
    id: existing?.id ?? `svc-${input.product}-${Date.now()}`,
    product: input.product,
    name,
    baseUrl,
    source: input.source ?? 'manual',
    status: 'online',
    lastSeenAt: stampNow(),
    note: input.note?.trim() || existing?.note,
  };
  if (existing) {
    Object.assign(existing, row);
  } else {
    state.services.push(row);
  }
  persist();
  message.success(`${productOf(input.product).label} 已登记 ${baseUrl}`);
  return row;
}

export function reportHeartbeat(id: string) {
  if (!requirePlatformAdmin()) return;
  const s = (state.services ?? []).find((x) => x.id === id);
  if (!s) return;
  s.status = 'online';
  s.source = 'heartbeat';
  s.lastSeenAt = stampNow();
  persist();
  message.success(`${s.name} 已上报心跳（演示）`);
}

export function setServiceStatus(id: string, status: PlatformService['status']) {
  if (!requirePlatformAdmin()) return;
  const s = (state.services ?? []).find((x) => x.id === id);
  if (!s) return;
  s.status = status;
  s.lastSeenAt = stampNow();
  persist();
  message.success(status === 'online' ? `${s.name} 已上线` : `${s.name} 已下线`);
}

export function removeService(id: string) {
  if (!requirePlatformAdmin()) return;
  const i = (state.services ?? []).findIndex((x) => x.id === id);
  if (i < 0) return;
  const name = state.services![i].name;
  state.services!.splice(i, 1);
  persist();
  message.success(`已从注册表移除「${name}」`);
}

export function testProductEndpoint(tenantId: string, product: ProductModule): boolean {
  const t = state.tenants.find((x) => x.id === tenantId);
  const ep = t?.endpoints?.find((e) => e.product === product);
  if (!ep?.baseUrl?.trim()) {
    message.error('先填写基址');
    return false;
  }
  const ok = /^https?:\/\//i.test(ep.baseUrl.trim());
  ep.status = ok ? 'ok' : 'error';
  ep.lastTestAt = stampNow();
  ep.note = ok ? '原型：地址格式通过（不发真实请求）' : '基址须以 http:// 或 https:// 开头';
  persist();
  if (ok) message.success('已测通（演示）');
  else message.error(ep.note);
  return ok;
}

export function accountsNotInTenant(): Account[] {
  if (!state.currentTenantId) return [];
  const ids = new Set(
    state.userTenants.filter((x) => x.tenantId === state.currentTenantId).map((x) => x.userId)
  );
  return state.accounts.filter((a) => !ids.has(a.id) && !a.platformAdmin);
}

export function platformUsers(): Account[] {
  return state.accounts.filter((a) => a.platformAdmin);
}

export function createPlatformUser(input: {
  username: string;
  displayName: string;
  password: string;
}): Account | null {
  if (!requirePlatformAdmin()) return null;
  const username = input.username.trim();
  if (!username || !input.password) {
    message.warning('请填写用户名和密码');
    return null;
  }
  if (state.accounts.some((a) => a.username === username)) {
    message.error('用户名已被占用');
    return null;
  }
  const acc: Account = {
    id: `u-${Date.now()}`,
    username,
    displayName: input.displayName.trim() || username,
    password: input.password,
    platformAdmin: true,
    status: 'active',
  };
  state.accounts.push(acc);
  persist();
  message.success(`平台用户「${acc.displayName}」已创建`);
  return acc;
}

export function setPlatformPassword(userId: string, password: string) {
  if (!requirePlatformAdmin()) return;
  if (!password) {
    message.warning('请填写新密码');
    return;
  }
  const acc = state.accounts.find((a) => a.id === userId && a.platformAdmin);
  if (!acc) return;
  acc.password = password;
  persist();
  message.success(`已修改 ${acc.displayName} 的密码`);
}

export function setPlatformStatus(userId: string, status: AccountStatus) {
  if (!requirePlatformAdmin()) return;
  const acc = state.accounts.find((a) => a.id === userId && a.platformAdmin);
  if (!acc) return;
  if (userId === state.currentUserId) {
    message.error('不能停用当前登录的平台用户');
    return;
  }
  const others = state.accounts.filter((a) => a.platformAdmin && a.status === 'active' && a.id !== userId);
  if (status === 'disabled' && others.length === 0) {
    message.error('不能停用最后一个可用的平台用户');
    return;
  }
  acc.status = status;
  persist();
  message.success(status === 'disabled' ? `已停用 ${acc.displayName}` : `已启用 ${acc.displayName}`);
}

export function tenantGrants(tenantId = state.currentTenantId): TenantGrant[] {
  if (!tenantId) return [];
  return state.grants.filter((g) => g.tenantId === tenantId);
}

export type GrantWrite = {
  permanent: boolean;
  expiresAt?: string | null;
  modules?: ProductModule[];
  projectIds?: string[];
  defaultRole?: ProjectRole;
  projectScopes?: { projectId: string; role: ProjectRole }[];
  aiCaps?: AiCap[];
};

function grantPayload(input: GrantWrite): Omit<TenantGrant, 'id' | 'tenantId' | 'code' | 'createdBy' | 'createdAt' | 'revoked'> {
  const kind: GrantKind = input.permanent ? 'permanent' : 'timed';
  return {
    kind,
    expiresAt: input.permanent ? null : input.expiresAt ?? null,
    modules: [...(input.modules ?? [])],
    projectIds: [...(input.projectIds ?? [])],
    defaultRole: input.defaultRole ?? 'viewer',
    projectScopes: [...(input.projectScopes ?? [])],
    aiCaps: [...(input.aiCaps ?? [])],
  };
}

export function createTenantGrant(input: GrantWrite): TenantGrant | null {
  if (!isMultiTenant()) {
    message.error('普通模式没有租户授权');
    return null;
  }
  if (!isRealTenantAdmin.value || !state.currentTenantId || !state.currentUserId) {
    message.error('只有本租户管理员能生成授权码');
    return null;
  }
  const tenant = state.tenants.find((t) => t.id === state.currentTenantId);
  if (!tenant) return null;
  const suffix = Math.random().toString(36).slice(2, 6).toUpperCase();
  const grant: TenantGrant = {
    id: `g-${Date.now()}`,
    tenantId: tenant.id,
    code: `${tenant.code.toUpperCase()}-${suffix}`,
    createdBy: state.currentUserId,
    createdAt: new Date().toISOString().slice(0, 10),
    revoked: false,
    ...grantPayload(input),
  };
  state.grants.push(grant);
  persist();
  message.success(`授权码 ${grant.code} 已生成`);
  return grant;
}

export function patchTenantGrant(grantId: string, input: GrantWrite): TenantGrant | null {
  if (!isRealTenantAdmin.value) {
    message.error('只有本租户管理员能编辑授权码');
    return null;
  }
  const g = state.grants.find((x) => x.id === grantId && x.tenantId === state.currentTenantId);
  if (!g) return null;
  Object.assign(g, grantPayload(input));
  persist();
  message.success(`已保存 ${g.code}`);
  return g;
}

export function deleteTenantGrant(grantId: string): boolean {
  if (!isRealTenantAdmin.value) {
    message.error('只有本租户管理员能删除授权码');
    return false;
  }
  const g = state.grants.find((x) => x.id === grantId && x.tenantId === state.currentTenantId);
  if (!g) return false;
  state.grants = state.grants.filter((x) => x.id !== grantId);
  state.platformAccess = state.platformAccess.filter((a) => a.grantId !== grantId);
  persist();
  message.success(`已删除授权码 ${g.code}`);
  return true;
}

function applyGrantMemberships(userId: string, grant: TenantGrant, tenantId: string) {
  const tenantPs = state.projects.filter(
    (p) => p.tenantId === tenantId && (p.status ?? 'active') !== 'disabled'
  );
  const pids = grant.projectIds?.length ? grant.projectIds : tenantPs.map((p) => p.id);
  const scopes = new Map((grant.projectScopes ?? []).map((s) => [s.projectId, s.role]));
  const fallback = grant.defaultRole ?? 'viewer';
  for (const pid of pids) {
    const role = scopes.get(pid) ?? fallback;
    const row = state.members.find((m) => m.projectId === pid && m.userId === userId);
    if (row) row.role = role;
    else state.members.push({ projectId: pid, userId, role });
  }
}

export function revokeTenantGrant(grantId: string) {
  if (!isRealTenantAdmin.value) {
    message.error('只有本租户管理员能作废授权码');
    return;
  }
  const g = state.grants.find((x) => x.id === grantId && x.tenantId === state.currentTenantId);
  if (!g) return;
  g.revoked = true;
  persist();
  message.success(`已作废 ${g.code}`);
}

export function redeemTenantGrant(tenantId: string, code: string): boolean {
  if (!requirePlatformAdmin()) return false;
  const normalized = code.trim().toUpperCase();
  const g = state.grants.find((x) => x.tenantId === tenantId && x.code.toUpperCase() === normalized);
  if (!g || !grantIsValid(g)) {
    message.error('授权码无效或已过期');
    return false;
  }
  if (!state.currentUserId) return false;
  const row = state.platformAccess.find((a) => a.userId === state.currentUserId && a.tenantId === tenantId);
  if (row) {
    row.grantId = g.id;
    row.boundAt = new Date().toISOString();
  } else {
    state.platformAccess.push({
      userId: state.currentUserId,
      tenantId,
      grantId: g.id,
      boundAt: new Date().toISOString(),
    });
  }
  applyGrantMemberships(state.currentUserId, g, tenantId);
  persist();
  message.success('授权成功，可以进入该租户');
  return true;
}

export function transferTenantAdmin(toUserId: string): boolean {
  if (!isMultiTenant()) {
    message.error('普通模式没有租户管理员转让');
    return false;
  }
  if (!isRealTenantAdmin.value || !state.currentTenantId || !state.currentUserId) {
    message.error('只有现任租户管理员能转让');
    return false;
  }
  if (toUserId === state.currentUserId) {
    message.warning('不能转让给自己');
    return false;
  }
  const target = state.accounts.find((a) => a.id === toUserId);
  const row = state.userTenants.find((x) => x.userId === toUserId && x.tenantId === state.currentTenantId);
  if (!target || target.status === 'disabled' || target.platformAdmin || !row) {
    message.error('只能转让给本租户未停用的用户');
    return false;
  }
  const mine = state.userTenants.find(
    (x) => x.userId === state.currentUserId && x.tenantId === state.currentTenantId
  );
  if (mine) mine.role = 'member';
  row.role = 'admin';
  const t = state.tenants.find((x) => x.id === state.currentTenantId);
  if (t) t.owner = target.displayName;
  persist();
  message.success(`已将租户管理员转让给 ${target.displayName}`);
  return true;
}

export function resetDemo() {
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
