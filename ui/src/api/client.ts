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

export function setAuthToken(token: string | null) {
  if (token) sessionStorage.setItem('dw-ai.token', token);
  else sessionStorage.removeItem('dw-ai.token');
}

async function req<T>(path: string, init?: RequestInit): Promise<T> {
  const token = authToken();
  const tenant = sessionStorage.getItem('dw-ai.tenantId') ?? '';
  const project = sessionStorage.getItem('dw-ai.projectId') ?? '';
  const res = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(tenant ? { 'X-Tenant-Id': tenant } : {}),
      ...(project ? { 'X-Project-Id': project } : {}),
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
    throw new Error(text || `${res.status} ${path}`);
  }
  if (res.status === 204) return undefined as T;
  const raw = await res.text();
  if (!raw) return undefined as T;
  return JSON.parse(raw) as T;
}

export type AuthConfig = {
  deployMode?: 'multi' | 'standard';
  mode: 'dev' | 'oidc';
  allowLogin?: boolean;
  allowDevLogin: boolean;
  casdoorConfigured: boolean;
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
};

export type Appearance = { theme: string; menuPos: string };

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
  authConfig: () => req<AuthConfig>('/api/auth/config'),
  login: (username: string, password = '') =>
    req<LoginRes>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  listAuthTenants: () => req<Tenant[]>('/api/auth/tenants'),
  selectTenant: (tenantId: string) =>
    req<Me>('/api/auth/select-tenant', { method: 'POST', body: JSON.stringify({ tenantId }) }),
  enterTenant: (tenantId: string, code: string) =>
    req<Me>('/api/auth/enter-tenant', { method: 'POST', body: JSON.stringify({ tenantId, code }) }),
  me: () => req<Me>('/api/auth/me'),
  updateMe: (displayName: string) =>
    req<OrgUser>('/api/me', { method: 'PUT', body: JSON.stringify({ displayName }) }),
  changePassword: (currentPassword: string, newPassword: string) =>
    req<void>('/api/me/password', { method: 'PUT', body: JSON.stringify({ currentPassword, newPassword }) }),
  platform: {
    tenants: () => req<Tenant[]>('/api/platform/tenants'),
    createTenant: (body: Record<string, unknown>) =>
      req<Tenant>('/api/platform/tenants', { method: 'POST', body: JSON.stringify(body) }),
    patchTenant: (id: string, body: Record<string, unknown>) =>
      req<Tenant>(`/api/platform/tenants/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    users: () => req<OrgUser[]>('/api/platform/users'),
    accounts: () => req<OrgUser[]>('/api/platform/accounts'),
    createUser: (body: { username: string; displayName?: string; password: string }) =>
      req<OrgUser>('/api/platform/users', { method: 'POST', body: JSON.stringify(body) }),
    patchUser: (id: string, body: { status?: string; password?: string }) =>
      req<OrgUser>(`/api/platform/users/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    appearance: () => req<Appearance>('/api/platform/appearance'),
    putAppearance: (body: Appearance) =>
      req<Appearance>('/api/platform/appearance', { method: 'PUT', body: JSON.stringify(body) }),
  },
  org: {
    users: (tenantId: string) => req<OrgUser[]>(`/api/tenants/${tenantId}/users`),
    createUser: (tenantId: string, body: Record<string, unknown>) =>
      req<OrgUser>(`/api/tenants/${tenantId}/users`, { method: 'POST', body: JSON.stringify(body) }),
    patchUser: (tenantId: string, userId: string, body: Record<string, unknown>) =>
      req<OrgUser>(`/api/tenants/${tenantId}/users/${encodeURIComponent(userId)}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    deleteUser: (tenantId: string, userId: string) =>
      req<void>(`/api/tenants/${tenantId}/users/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
    projects: (tenantId: string) => req<Project[]>(`/api/tenants/${tenantId}/projects`),
    createProject: (tenantId: string, body: Record<string, unknown>) =>
      req<Project>(`/api/tenants/${tenantId}/projects`, { method: 'POST', body: JSON.stringify(body) }),
    patchProject: (tenantId: string, projectId: string, body: Record<string, unknown>) =>
      req<Project>(`/api/tenants/${tenantId}/projects/${projectId}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    deleteProject: (tenantId: string, projectId: string) =>
      req<void>(`/api/tenants/${tenantId}/projects/${projectId}`, { method: 'DELETE' }),
    appearance: (tenantId: string) => req<Appearance>(`/api/tenants/${tenantId}/appearance`),
    putAppearance: (tenantId: string, body: Appearance) =>
      req<Appearance>(`/api/tenants/${tenantId}/appearance`, { method: 'PUT', body: JSON.stringify(body) }),
    llm: (tenantId: string) => req<LlmConfigDto>(`/api/tenants/${tenantId}/llm`),
    putLlm: (tenantId: string, body: Record<string, unknown>) =>
      req<LlmConfigDto>(`/api/tenants/${tenantId}/llm`, { method: 'PUT', body: JSON.stringify(body) }),
    grants: (tenantId: string) => req<Grant[]>(`/api/tenants/${tenantId}/grants`),
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
      }
    ) => req<Grant>(`/api/tenants/${tenantId}/grants`, { method: 'POST', body: JSON.stringify(body) }),
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
      }
    ) =>
      req<Grant>(`/api/tenants/${tenantId}/grants/${grantId}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    revokeGrant: (tenantId: string, grantId: string) =>
      req<void>(`/api/tenants/${tenantId}/grants/${grantId}/revoke`, { method: 'POST' }),
    deleteGrant: (tenantId: string, grantId: string) =>
      req<void>(`/api/tenants/${tenantId}/grants/${grantId}`, { method: 'DELETE' }),
    transferAdmin: (tenantId: string, userId: string) =>
      req<void>(`/api/tenants/${tenantId}/transfer-admin`, { method: 'POST', body: JSON.stringify({ userId }) }),
  },
  versions: {
    list: (projectId: string, tableId: string) =>
      req<TableVersion[]>(`/api/projects/${projectId}/tables/${tableId}/versions`),
    get: (projectId: string, tableId: string, versionId: string) =>
      req<TableVersion>(`/api/projects/${projectId}/tables/${tableId}/versions/${versionId}`),
    diff: (projectId: string, tableId: string, from: string, to: string) =>
      req<{
        meta: { field: string; from: string; to: string }[];
        added: Record<string, unknown>[];
        removed: Record<string, unknown>[];
        changed: { name: string; from: Record<string, unknown>; to: Record<string, unknown> }[];
      }>(`/api/projects/${projectId}/tables/${tableId}/versions/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
    restore: (projectId: string, tableId: string, versionId: string) =>
      req<WarehouseTable>(`/api/projects/${projectId}/tables/${tableId}/versions/${versionId}/restore`, {
        method: 'POST',
      }),
  },
  ai: {
    chat: (message: string) =>
      req<{ source: string; text?: string | null }>('/api/ai/chat', {
        method: 'POST',
        body: JSON.stringify({ message }),
      }),
    layerChat: (projectId: string, layer: string, message: string, tableId?: string) =>
      req<{ source: string; text?: string | null }>(`/api/projects/${projectId}/layers/${layer}/ai/chat`, {
        method: 'POST',
        body: JSON.stringify({ message, tableId }),
      }),
    apply: (projectId: string, layer: string, tables: unknown[]) =>
      req<WarehouseTable[]>(`/api/projects/${projectId}/layers/${layer}/ai/apply`, {
        method: 'POST',
        body: JSON.stringify({ tables }),
      }),
  },
  session: () => req<Session>('/api/session'),
  tenants: () => req<Tenant[]>('/api/tenants'),
  projects: () => req<Project[]>('/api/projects'),
  createProject: (body: {
    code: string;
    name: string;
    description: string;
    owner: string;
    adminUserId?: string;
    bootstrapSpec?: boolean;
  }) => req<Project>('/api/projects', { method: 'POST', body: JSON.stringify(body) }),
  patchProject: (projectId: string, body: Record<string, unknown>) =>
    req<Project>(`/api/projects/${projectId}`, { method: 'PATCH', body: JSON.stringify(body) }),
  deleteProject: (projectId: string) => req<void>(`/api/projects/${projectId}`, { method: 'DELETE' }),
  snapshot: (projectId: string) => req<Snapshot>(`/api/projects/${projectId}/snapshot`),
  putMember: (projectId: string, userId: string, role: string) =>
    req<ProjectMember>(`/api/projects/${projectId}/members/${encodeURIComponent(userId)}`, {
      method: 'PUT',
      body: JSON.stringify({ role }),
    }),
  spec: {
    domains: (projectId: string) => req<Domain[]>(`/api/projects/${projectId}/domains`),
    layers: (projectId: string) => req<LayerRule[]>(`/api/projects/${projectId}/layers`),
    grades: (projectId: string) => req<DataGrade[]>(`/api/projects/${projectId}/grades`),
    roots: (projectId: string) => req<WordRoot[]>(`/api/projects/${projectId}/roots`),
    saveDomain: (projectId: string, body: Domain) =>
      req<Domain>(
        body.id ? `/api/projects/${projectId}/domains/${body.id}` : `/api/projects/${projectId}/domains`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    deleteDomain: (projectId: string, id: string) =>
      req<void>(`/api/projects/${projectId}/domains/${id}`, { method: 'DELETE' }),
    saveLayer: (projectId: string, body: LayerRule, prevLayer?: string) =>
      req<LayerRule>(
        prevLayer
          ? `/api/projects/${projectId}/layers/${encodeURIComponent(prevLayer)}`
          : `/api/projects/${projectId}/layers`,
        { method: prevLayer ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    deleteLayer: (projectId: string, layer: string) =>
      req<void>(`/api/projects/${projectId}/layers/${encodeURIComponent(layer)}`, { method: 'DELETE' }),
    saveGrade: (projectId: string, body: DataGrade) =>
      req<DataGrade>(
        body.id ? `/api/projects/${projectId}/grades/${body.id}` : `/api/projects/${projectId}/grades`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    deleteGrade: (projectId: string, id: string) =>
      req<void>(`/api/projects/${projectId}/grades/${id}`, { method: 'DELETE' }),
    saveRoot: (projectId: string, body: WordRoot) =>
      req<WordRoot>(
        body.id ? `/api/projects/${projectId}/roots/${body.id}` : `/api/projects/${projectId}/roots`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    deleteRoot: (projectId: string, id: string) =>
      req<void>(`/api/projects/${projectId}/roots/${id}`, { method: 'DELETE' }),
    sync: (projectId: string, body: { domains: Domain[]; layers: LayerRule[]; grades: DataGrade[]; roots: WordRoot[] }) =>
      req<void>(`/api/projects/${projectId}/spec`, { method: 'PUT', body: JSON.stringify(body) }),
  },
  tables: {
    list: (projectId: string) => req<WarehouseTable[]>(`/api/projects/${projectId}/tables`),
    save: (projectId: string, body: WarehouseTable) =>
      req<WarehouseTable>(
        body.id ? `/api/projects/${projectId}/tables/${body.id}` : `/api/projects/${projectId}/tables`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    remove: (projectId: string, id: string) =>
      req<void>(`/api/projects/${projectId}/tables/${id}`, { method: 'DELETE' }),
    sync: (projectId: string, body: WarehouseTable[]) =>
      req<void>(`/api/projects/${projectId}/tables-bundle`, { method: 'PUT', body: JSON.stringify(body) }),
  },
  drafts: {
    list: (projectId: string) => req<ModelingDraft[]>(`/api/projects/${projectId}/drafts`),
    save: (projectId: string, body: ModelingDraft) =>
      req<ModelingDraft>(
        body.id ? `/api/projects/${projectId}/drafts/${body.id}` : `/api/projects/${projectId}/drafts`,
        { method: body.id ? 'PUT' : 'POST', body: JSON.stringify(body) }
      ),
    sync: (projectId: string, body: ModelingDraft[]) =>
      req<void>(`/api/projects/${projectId}/drafts-bundle`, { method: 'PUT', body: JSON.stringify(body) }),
  },
  previewQuery: (body: unknown) =>
    req<{ sql: string; decision: string; rows: Record<string, unknown>[] }>('/api/query/preview', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  publishJob: (body: unknown) =>
    req<{ processCode?: string; skipped?: boolean }>('/api/jobs/publish', {
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
