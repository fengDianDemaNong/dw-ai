/** 生产控制台只调 API。未配置 VITE_API_BASE 时：开发态走 Vite `/api` 代理，已登录则同源。 */
import type {
  DataGrade,
  Domain,
  LayerRule,
  ModelingDraft,
  Project,
  ProjectMember,
  Tenant,
  TenantLicense,
  WarehouseTable,
  WordRoot,
} from '../types';

const rawApi = (import.meta.env.VITE_API_BASE as string | undefined)?.trim();
const rawRules = (import.meta.env.VITE_RULES_BASE as string | undefined)?.trim();
/** `.` 表示与页面同源（Docker Nginx 反代 / 安装包由 API 托管静态页 / Vite 代理） */
export const API_BASE = !rawApi || rawApi === '.' ? '' : rawApi.replace(/\/$/, '');
export const RULES_BASE = !rawRules || rawRules === '.' ? '' : rawRules.replace(/\/$/, '');

export function useRemoteApi() {
  if (rawApi) return true;
  if (import.meta.env.DEV) return true;
  return Boolean(authToken());
}

export function authToken() {
  return sessionStorage.getItem('dw-ai.token') ?? '';
}

export function storedRefreshToken() {
  return sessionStorage.getItem('dw-ai.refreshToken') ?? '';
}

export type AuthTokens = { refreshToken?: string; expiresIn?: number; touch?: boolean };

const LAST_ACTIVITY_KEY = 'dw-ai.lastActivity';
const IDLE_TTL_KEY = 'dw-ai.idleTtl';

export function setIdleTtlSeconds(seconds: number) {
  const n = Number(seconds);
  sessionStorage.setItem(IDLE_TTL_KEY, String(n > 0 ? n : 900));
}

function idleTtlMs() {
  const n = Number(sessionStorage.getItem(IDLE_TTL_KEY) || 900);
  return (n > 0 ? n : 900) * 1000;
}

export function markActivity() {
  sessionStorage.setItem(LAST_ACTIVITY_KEY, String(Date.now()));
}

function isIdle() {
  if (!authToken() && !storedRefreshToken()) return false;
  const last = Number(sessionStorage.getItem(LAST_ACTIVITY_KEY) || 0);
  if (!last) return false;
  return Date.now() - last > idleTtlMs();
}

function expireIdle() {
  setAuthToken(null);
  if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
    window.location.assign('/org/login');
  }
}

export function setAuthToken(token: string | null, extras?: AuthTokens) {
  if (token) {
    sessionStorage.setItem('dw-ai.token', token);
    if (extras?.refreshToken) sessionStorage.setItem('dw-ai.refreshToken', extras.refreshToken);
    if (extras?.expiresIn && extras.expiresIn > 0) {
      sessionStorage.setItem('dw-ai.tokenExp', String(Date.now() + extras.expiresIn * 1000));
    }
    if (extras?.touch !== false) markActivity();
  } else {
    sessionStorage.removeItem('dw-ai.token');
    sessionStorage.removeItem('dw-ai.refreshToken');
    sessionStorage.removeItem('dw-ai.tokenExp');
    sessionStorage.removeItem(LAST_ACTIVITY_KEY);
  }
}

const ANON_AUTH = /\/auth\/(login|refresh|logout|config)(?:\?|$)/;

function tokenExpiringSoon() {
  const exp = Number(sessionStorage.getItem('dw-ai.tokenExp') || 0);
  if (!exp) return false;
  return Date.now() > exp - 60_000;
}

let refreshInflight: Promise<boolean> | null = null;

export function refreshAccess(): Promise<boolean> {
  if (isIdle()) {
    expireIdle();
    return Promise.resolve(false);
  }
  if (!refreshInflight) {
    refreshInflight = doRefresh().finally(() => {
      refreshInflight = null;
    });
  }
  return refreshInflight;
}

async function doRefresh(): Promise<boolean> {
  const rt = storedRefreshToken();
  if (!rt) return false;
  try {
    const res = await fetch(`${API_BASE}/api/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken: rt }),
    });
    if (!res.ok) {
      setAuthToken(null);
      return false;
    }
    const out = (await res.json()) as LoginRes;
    if (!out?.token) {
      setAuthToken(null);
      return false;
    }
    setAuthToken(out.token, { refreshToken: out.refreshToken, expiresIn: out.expiresIn, touch: false });
    return true;
  } catch {
    return false;
  }
}

function isAuthFailure(err: unknown) {
  const status = typeof err === 'object' && err && 'status' in err ? Number((err as { status?: number }).status) : 0;
  if (status === 401) return true;
  const msg = err instanceof Error ? err.message : String(err);
  return /401|unauthorized|session expired|登录已过期|未登录|没有登录/i.test(msg);
}

function asciiHeader(v: string) {
  return /^[\x20-\x7E]+$/.test(v) ? v : '';
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const anon = ANON_AUTH.test(path);
  if (!anon && isIdle()) {
    expireIdle();
    throw new Error('登录已过期');
  }
  if (!anon && tokenExpiringSoon()) await refreshAccess();
  try {
    return await doFetch<T>(path, init);
  } catch (e) {
    if (!anon && isAuthFailure(e) && (await refreshAccess())) {
      return doFetch<T>(path, init);
    }
    throw e;
  }
}

async function doFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const token = authToken();
  const tenant = asciiHeader(sessionStorage.getItem('dw-ai.tenantId') ?? '');
  const project = asciiHeader(sessionStorage.getItem('dw-ai.projectId') ?? '');
  const tenantCode = asciiHeader(sessionStorage.getItem('dw-ai.tenantCode') ?? '');
  const projectCode = asciiHeader(sessionStorage.getItem('dw-ai.projectCode') ?? '');
  const userId = asciiHeader(sessionStorage.getItem('dw-ai.userId') ?? '');
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(tenant ? { 'X-Tenant-Id': tenant } : {}),
      ...(project ? { 'X-Project-Id': project } : {}),
      ...(tenantCode ? { 'X-Tenant-Code': tenantCode } : {}),
      ...(projectCode ? { 'X-Project-Code': projectCode } : {}),
      ...(userId ? { 'X-User-Id': userId } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    let text = await res.text();
    try {
      const j = JSON.parse(text) as { error?: string };
      if (j.error) text = j.error;
    } catch {
      /* keep */
    }
    const err = new Error(text || `${res.status} ${path}`) as Error & { status: number };
    err.status = res.status;
    throw err;
  }
  if (res.status === 204) return undefined as T;
  const raw = await res.text();
  if (!raw) return undefined as T;
  return JSON.parse(raw) as T;
}

if (typeof window !== 'undefined') {
  let lastSlide = Date.now();
  const onActivity = () => {
    if (!authToken() && !storedRefreshToken()) return;
    if (isIdle()) {
      expireIdle();
      return;
    }
    markActivity();
    const now = Date.now();
    if (now - lastSlide < 60_000 || !storedRefreshToken()) return;
    lastSlide = now;
    void refreshAccess();
  };
  window.addEventListener('pointerdown', onActivity, { passive: true });
  window.addEventListener('keydown', onActivity, { passive: true });
}

export type AuthConfig = {
  runMode?: 'standalone' | 'standard' | 'multi';
  deployMode?: 'standalone' | 'standard' | 'multi';
  product?: string;
  mode: 'dev' | 'oidc';
  allowLogin?: boolean;
  allowDevLogin: boolean;
  casdoorConfigured: boolean;
  sessionEpoch?: string;
  accessTtlSeconds?: number;
  refreshTtlSeconds?: number;
  idleTtlSeconds?: number;
  casdoor?: { issuer: string; audience: string };
};

export type Me = {
  userId: string;
  displayName: string;
  tenantId?: string | null;
  tenantCode?: string | null;
  tenantName?: string | null;
  username: string;
  authMode: string;
  platformAdmin?: boolean;
  tenantRole?: string | null;
  landing?: string;
  landingProjectId?: string | null;
  needSelectTenant?: boolean;
  deployMode?: string;
  aiCaps?: string[];
};

export type OrgUser = {
  id: string;
  username: string;
  displayName: string;
  status: string;
  tenantRole?: string | null;
  platformAdmin?: boolean;
};

export type Grant = {
  id: string;
  tenantId: string;
  code: string;
  kind: string;
  expiresAt?: string | null;
  valid: boolean;
  createdAt?: string;
  modules?: string[];
  projectIds?: string[];
  projectScopes?: { projectId: string; role: string }[];
  defaultRole?: string;
  aiCaps?: string[];
};

export type AiPromptsDto = {
  defaults: Record<string, string>;
  overrides: Record<string, string>;
  effective: Record<string, string>;
};

export type KnowledgeArticleDto = {
  id: string;
  tenantId: string;
  engine: string;
  title: string;
  summary: string;
  body: string;
  sourceUrl?: string;
  sourceLabel?: string;
  sections?: Record<string, unknown>[];
  notes?: string[];
  importedAt?: string;
  importedBy?: string;
};

export type Appearance = { theme: string; menuPos: string; menuColor?: string };

export type LlmConfigDto = {
  enabled: boolean;
  provider?: string;
  baseUrl?: string;
  model?: string;
  hasKey: boolean;
};

export type TableVersion = {
  id: string;
  tableId: string;
  projectId: string;
  version: number;
  note: string;
  createdBy: string;
  createdAt: string;
  snapshot: Record<string, unknown>;
};

export type LoginRes = {
  token: string;
  refreshToken?: string;
  expiresIn?: number;
  needSelectTenant?: boolean;
  platformAdmin?: boolean;
  displayName?: string;
  userId?: string;
  tenants?: Tenant[];
};

export type Session = {
  user: Me;
  tenants: Tenant[];
  projects: Project[];
  members: ProjectMember[];
  licenses: TenantLicense[];
};

export type Snapshot = {
  project: Project;
  members: ProjectMember[];
  domains: Domain[];
  layers: LayerRule[];
  grades: DataGrade[];
  roots: WordRoot[];
  tables: WarehouseTable[];
  drafts: ModelingDraft[];
};

export const api = {
  health: () => req<{ ok: boolean }>('/api/health'),
  runtime: () => req<{ product: string; version: string; runMode: string }>('/api/runtime'),
  authConfig: () => req<AuthConfig>('/api/v1/auth/config'),
  login: (username: string, password = '') =>
    req<LoginRes>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  logout: (refreshToken: string) =>
    fetch(`${API_BASE}/api/v1/auth/logout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    }).then(() => undefined),
  listAuthTenants: () => req<Tenant[]>('/api/v1/auth/tenants'),
  selectTenant: (tenantId: string) =>
    req<Me>('/api/v1/auth/select-tenant', { method: 'POST', body: JSON.stringify({ tenantId }) }),
  enterTenant: (tenantId: string, code: string) =>
    req<Me>('/api/v1/auth/enter-tenant', { method: 'POST', body: JSON.stringify({ tenantId, code }) }),
  me: () => req<Me>('/api/v1/auth/me'),
  updateMe: (displayName: string) =>
    req<OrgUser>('/api/v1/me', { method: 'PUT', body: JSON.stringify({ displayName }) }),
  changePassword: (currentPassword: string, newPassword: string) =>
    req<void>('/api/v1/me/password', { method: 'PUT', body: JSON.stringify({ currentPassword, newPassword }) }),
  platform: {
    tenants: () => req<Tenant[]>('/api/v1/platform/tenants'),
    createTenant: (body: Record<string, unknown>) =>
      req<Tenant>('/api/v1/platform/tenants', { method: 'POST', body: JSON.stringify(body) }),
    patchTenant: (id: string, body: Record<string, unknown>) =>
      req<Tenant>(`/api/v1/platform/tenants/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    users: () => req<OrgUser[]>('/api/v1/platform/users'),
    accounts: () => req<OrgUser[]>('/api/v1/platform/accounts'),
    createUser: (body: { username: string; displayName?: string; password: string }) =>
      req<OrgUser>('/api/v1/platform/users', { method: 'POST', body: JSON.stringify(body) }),
    patchUser: (id: string, body: { status?: string; password?: string }) =>
      req<OrgUser>(`/api/v1/platform/users/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    appearance: () => req<Appearance>('/api/v1/platform/appearance'),
    putAppearance: (body: Appearance) =>
      req<Appearance>('/api/v1/platform/appearance', { method: 'PUT', body: JSON.stringify(body) }),
    services: () =>
      req<{ product: string; version?: string; baseUrl: string; seenAt?: string; status?: string }[]>(
        '/api/v1/platform/services'
      ),
    registerService: (body: { product: string; baseUrl: string; version?: string }) =>
      req<unknown>('/api/v1/platform/services', { method: 'POST', body: JSON.stringify(body) }),
    removeService: (product: string) =>
      req<void>(`/api/v1/platform/services/${encodeURIComponent(product)}`, { method: 'DELETE' }),
  },
  org: {
    users: (tenantId: string) => req<OrgUser[]>(`/api/v1/tenants/${tenantId}/users`),
    createUser: (tenantId: string, body: Record<string, unknown>) =>
      req<OrgUser>(`/api/v1/tenants/${tenantId}/users`, { method: 'POST', body: JSON.stringify(body) }),
    patchUser: (tenantId: string, userId: string, body: Record<string, unknown>) =>
      req<OrgUser>(`/api/v1/tenants/${tenantId}/users/${encodeURIComponent(userId)}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    deleteUser: (tenantId: string, userId: string) =>
      req<void>(`/api/v1/tenants/${tenantId}/users/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
    projects: (tenantId: string) => req<Project[]>(`/api/v1/tenants/${tenantId}/projects`),
    createProject: (tenantId: string, body: Record<string, unknown>) =>
      req<Project>(`/api/v1/tenants/${tenantId}/projects`, { method: 'POST', body: JSON.stringify(body) }),
    patchProject: (tenantId: string, projectId: string, body: Record<string, unknown>) =>
      req<Project>(`/api/v1/tenants/${tenantId}/projects/${projectId}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    deleteProject: (tenantId: string, projectId: string) =>
      req<void>(`/api/v1/tenants/${tenantId}/projects/${projectId}`, { method: 'DELETE' }),
    appearance: (tenantId: string) => req<Appearance>(`/api/v1/tenants/${tenantId}/appearance`),
    putAppearance: (tenantId: string, body: Appearance) =>
      req<Appearance>(`/api/v1/tenants/${tenantId}/appearance`, { method: 'PUT', body: JSON.stringify(body) }),
    llm: (tenantId: string) => req<LlmConfigDto>(`/api/v1/tenants/${tenantId}/llm`),
    putLlm: (tenantId: string, body: Record<string, unknown>) =>
      req<LlmConfigDto>(`/api/v1/tenants/${tenantId}/llm`, { method: 'PUT', body: JSON.stringify(body) }),
    grants: (tenantId: string) => req<Grant[]>(`/api/v1/tenants/${tenantId}/grants`),
    createGrant: (
      tenantId: string,
      body: {
        permanent?: boolean;
        expiresAt?: string;
        modules?: string[];
        projectIds?: string[];
        projectScopes?: { projectId: string; role: string }[];
        defaultRole?: string;
        kind?: string;
        aiCaps?: string[];
      }
    ) => req<Grant>(`/api/v1/tenants/${tenantId}/grants`, { method: 'POST', body: JSON.stringify(body) }),
    patchGrant: (
      tenantId: string,
      grantId: string,
      body: {
        permanent?: boolean;
        expiresAt?: string;
        modules?: string[];
        projectIds?: string[];
        projectScopes?: { projectId: string; role: string }[];
        defaultRole?: string;
        kind?: string;
        aiCaps?: string[];
      }
    ) =>
      req<Grant>(`/api/v1/tenants/${tenantId}/grants/${grantId}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    revokeGrant: (tenantId: string, grantId: string) =>
      req<void>(`/api/v1/tenants/${tenantId}/grants/${grantId}/revoke`, { method: 'POST' }),
    deleteGrant: (tenantId: string, grantId: string) =>
      req<void>(`/api/v1/tenants/${tenantId}/grants/${grantId}`, { method: 'DELETE' }),
    transferAdmin: (tenantId: string, userId: string) =>
      req<void>(`/api/v1/tenants/${tenantId}/transfer-admin`, { method: 'POST', body: JSON.stringify({ userId }) }),
    aiPrompts: (tenantId: string) => req<AiPromptsDto>(`/api/v1/tenants/${tenantId}/ai-prompts`),
    putAiPrompts: (tenantId: string, overrides: Record<string, string>) =>
      req<AiPromptsDto>(`/api/v1/tenants/${tenantId}/ai-prompts`, {
        method: 'PUT',
        body: JSON.stringify({ overrides }),
      }),
    knowledge: (tenantId: string) => req<KnowledgeArticleDto[]>(`/api/v1/tenants/${tenantId}/knowledge`),
    importKnowledge: (tenantId: string, body: { text: string; filename?: string; mode?: string }) =>
      req<{ articles: KnowledgeArticleDto[]; warnings: string[]; format: string }>(
        `/api/v1/tenants/${tenantId}/knowledge/import`,
        { method: 'POST', body: JSON.stringify(body) }
      ),
    deleteKnowledge: (tenantId: string, engine: string, articleId: string) =>
      req<void>(`/api/v1/tenants/${tenantId}/knowledge/${encodeURIComponent(engine)}/${encodeURIComponent(articleId)}`, {
        method: 'DELETE',
      }),
  },
  knowledge: {
    manuals: (engine?: string) =>
      req<Record<string, unknown>[]>(`/api/v1/knowledge/manuals${engine ? `?engine=${encodeURIComponent(engine)}` : ''}`),
  },
  versions: {
    list: (projectId: string, tableId: string) =>
      req<TableVersion[]>(`/api/v1/projects/${projectId}/tables/${tableId}/versions`),
    get: (projectId: string, tableId: string, versionId: string) =>
      req<TableVersion>(`/api/v1/projects/${projectId}/tables/${tableId}/versions/${versionId}`),
    diff: (projectId: string, tableId: string, from: string, to: string) =>
      req<{
        meta: { field: string; from: string; to: string }[];
        added: Record<string, unknown>[];
        removed: Record<string, unknown>[];
        changed: { name: string; from: Record<string, unknown>; to: Record<string, unknown> }[];
      }>(`/api/v1/projects/${projectId}/tables/${tableId}/versions/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    restore: (projectId: string, tableId: string, versionId: string) =>
      req<WarehouseTable>(`/api/v1/projects/${projectId}/tables/${tableId}/versions/${versionId}/restore`, {
        method: 'POST',
      }),
  },
  ai: {
    chat: (body: { message: string; slot?: string; projectId?: string; tableId?: string; history?: unknown[] }) =>
      req<{ source: string; text?: string | null; fallback?: boolean; error?: string }>('/api/v1/ai/chat', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    layerChat: (
      projectId: string,
      layer: string,
      body: { message: string; slot?: string; tableId?: string; history?: unknown[] }
    ) =>
      req<{ source: string; text?: string | null; fallback?: boolean; error?: string }>(
        `/api/v1/projects/${projectId}/layers/${layer}/ai/chat`,
        {
          method: 'POST',
          body: JSON.stringify({ slot: 'model.system', ...body }),
        }
      ),
    apply: (projectId: string, layer: string, tables: unknown[]) =>
      req<WarehouseTable[]>(`/api/v1/projects/${projectId}/layers/${layer}/ai/apply`, {
        method: 'POST',
        body: JSON.stringify({ tables }),
      }),
  },
  session: () => req<Session>('/api/v1/session'),
  tenants: () => req<Tenant[]>('/api/v1/tenants'),
  projects: () => req<Project[]>('/api/v1/projects'),
  createProject: (body: {
    code: string;
    name: string;
    description: string;
    owner: string;
    adminUserId?: string;
    bootstrapSpec?: boolean;
    engines?: string[];
  }) => req<Project>('/api/v1/projects', { method: 'POST', body: JSON.stringify(body) }),
  patchProject: (projectId: string, body: Record<string, unknown>) =>
    req<Project>(`/api/v1/projects/${projectId}`, { method: 'PATCH', body: JSON.stringify(body) }),
  deleteProject: (projectId: string) => req<void>(`/api/v1/projects/${projectId}`, { method: 'DELETE' }),
  snapshot: (projectId: string) => req<Snapshot>(`/api/v1/projects/${projectId}/snapshot`),
  putMember: (projectId: string, userId: string, role: string) =>
    req<ProjectMember>(`/api/v1/projects/${projectId}/members/${encodeURIComponent(userId)}`, {
      method: 'PUT',
      body: JSON.stringify({ role }),
    }),
  spec: {
    domains: (projectId: string) => req<Domain[]>(`/api/v1/projects/${projectId}/domains`),
    layers: (projectId: string) => req<LayerRule[]>(`/api/v1/projects/${projectId}/layers`),
    grades: (projectId: string) => req<DataGrade[]>(`/api/v1/projects/${projectId}/grades`),
    roots: (projectId: string) => req<WordRoot[]>(`/api/v1/projects/${projectId}/roots`),
    saveDomain: (projectId: string, body: Domain) =>
      req<Domain>(
        body.id ? `/api/v1/projects/${projectId}/domains/${body.id}` : `/api/v1/projects/${projectId}/domains`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    deleteDomain: (projectId: string, id: string) =>
      req<void>(`/api/v1/projects/${projectId}/domains/${id}`, { method: 'DELETE' }),
    saveLayer: (projectId: string, body: LayerRule, prevLayer?: string) =>
      req<LayerRule>(
        prevLayer
          ? `/api/v1/projects/${projectId}/layers/${encodeURIComponent(prevLayer)}`
          : `/api/v1/projects/${projectId}/layers`,
        { method: prevLayer ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    deleteLayer: (projectId: string, layer: string) =>
      req<void>(`/api/v1/projects/${projectId}/layers/${encodeURIComponent(layer)}`, { method: 'DELETE' }),
    saveGrade: (projectId: string, body: DataGrade) =>
      req<DataGrade>(
        body.id ? `/api/v1/projects/${projectId}/grades/${body.id}` : `/api/v1/projects/${projectId}/grades`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    deleteGrade: (projectId: string, id: string) =>
      req<void>(`/api/v1/projects/${projectId}/grades/${id}`, { method: 'DELETE' }),
    saveRoot: (projectId: string, body: WordRoot) =>
      req<WordRoot>(
        body.id ? `/api/v1/projects/${projectId}/roots/${body.id}` : `/api/v1/projects/${projectId}/roots`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    deleteRoot: (projectId: string, id: string) =>
      req<void>(`/api/v1/projects/${projectId}/roots/${id}`, { method: 'DELETE' }),
    sync: (projectId: string, body: { domains: Domain[]; layers: LayerRule[]; grades: DataGrade[]; roots: WordRoot[] }) =>
      req<void>(`/api/v1/projects/${projectId}/spec`, { method: 'PUT', body: JSON.stringify(body) }),
  },
  tables: {
    list: (projectId: string) => req<WarehouseTable[]>(`/api/v1/projects/${projectId}/tables`),
    save: (projectId: string, body: WarehouseTable) =>
      req<WarehouseTable>(
        body.id ? `/api/v1/projects/${projectId}/tables/${body.id}` : `/api/v1/projects/${projectId}/tables`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    remove: (projectId: string, id: string) =>
      req<void>(`/api/v1/projects/${projectId}/tables/${id}`, { method: 'DELETE' }),
    publish: (projectId: string, id: string, body?: { note?: string }) =>
      req<WarehouseTable>(`/api/v1/projects/${projectId}/tables/${id}/publish`, {
        method: 'POST',
        body: JSON.stringify(body ?? {}),
      }),
    sync: (projectId: string, body: WarehouseTable[]) =>
      req<void>(`/api/v1/projects/${projectId}/tables-bundle`, { method: 'PUT', body: JSON.stringify(body) }),
  },
  drafts: {
    list: (projectId: string) => req<ModelingDraft[]>(`/api/v1/projects/${projectId}/drafts`),
    save: (projectId: string, body: ModelingDraft) =>
      req<ModelingDraft>(
        body.id ? `/api/v1/projects/${projectId}/drafts/${body.id}` : `/api/v1/projects/${projectId}/drafts`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    sync: (projectId: string, body: ModelingDraft[]) =>
      req<void>(`/api/v1/projects/${projectId}/drafts-bundle`, { method: 'PUT', body: JSON.stringify(body) }),
  },
  previewQuery: (body: unknown) =>
    req<{ sql: string; decision: string; rows: Record<string, unknown>[] }>('/api/v1/query/preview', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  publishJob: (body: unknown) =>
    req<{ processCode?: string; skipped?: boolean }>('/api/v1/jobs/publish', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
};

export async function rulesReq<T>(path: string, body: unknown): Promise<T> {
  const base = RULES_BASE || API_BASE;
  const res = await fetch(`${base}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(await res.text());
  return res.json() as Promise<T>;
}
