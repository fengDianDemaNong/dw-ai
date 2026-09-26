/** 生产控制台只调 API。未配置 VITE_API_BASE_URL 时：开发态走 Vite `/api` 代理，已登录则同源。 */
import { LOGIN_PATH } from '../config/paths';
import { currentLocation, isEmbed, openOrgLogin } from '../config/product';
import { isMultiTenant, isStandalone } from '../config/runtime';
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

/** `.` 表示与页面同源（Docker Nginx 反代 / 安装包由 API 托管静态页 / Vite 代理） */
function normalize(v: string | undefined): string {
  const t = (v ?? '').trim();
  return !t || t === '.' ? '' : t.replace(/\/$/, '');
}

/** 是否显式配置过后端地址（含 `.`）。useRemoteApi 用它，语义与改动前一致。 */
let rawApi = ((import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '').trim();

/**
 * 后端基址。
 *
 * <p>初值取构建期 `VITE_API_BASE_URL` —— 这样即使没人调 configureApiBase，
 * 行为也与改动前完全一致，不会静默退回同源。挂载前由 main.ts 用运行时配置覆盖
 * （见 config/appConfig.ts）。消费点都在函数体内，所以 `export let` 的重新赋值
 * 对它们是可见的（ESM live binding）；模块顶层就把它赋给别的 const 会快照旧值。
 */
export let API_BASE = normalize(rawApi);

const rawRules = (import.meta.env.VITE_RULES_BASE as string | undefined)?.trim();
export const RULES_BASE = normalize(rawRules);

/**
 * 注入运行时配置的后端地址。**必须在 createApp() 之前调用**，见 main.ts。
 * 空串 = 同源相对路径（沿用改动前的默认行为）。
 */
export function configureApiBase(value: string | undefined): void {
  rawApi = (value ?? '').trim();
  API_BASE = normalize(rawApi);
}

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

/** 当前 access token 的到期毫秒时间戳（无则空串）。供 ProductEmbed 转发给嵌入的子应用。 */
export function storedTokenExp() {
  return sessionStorage.getItem('dw-ai.tokenExp') ?? '';
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

/**
 * 身份彻底失效后的去向 —— 「令牌失效」的几条路都收在这里，免得各自漂移。
 *
 * <p>multi 的身份在组织平台：回那儿重新登录，并把**当前这一页**带上，登录后落回原处。
 * 不带的话他落在门户首页，还得自己重新找刚才在看的东西 —— 而这次失效往往只是令牌到期，
 * 页面本身没毛病。以前这里径直跳本进程的 `LOGIN_PATH`，那一页在 multi 下也不过是再转
 * 一次组织平台：多一跳，原地址还在这条绕路上丢了。
 *
 * <p>嵌入态**绝不整页跳**：那会把组织平台门户的壳变成一整页登录页。那里的续期是向宿主
 * 求令牌（见 {@link renewFromHost}），求不到时由调用方给出可行动的提示。
 */
function backToLogin() {
  if (typeof window === 'undefined' || isEmbed()) return;
  if (isMultiTenant()) {
    openOrgLogin(currentLocation());
    return;
  }
  if (!window.location.pathname.includes('/login')) window.location.assign(LOGIN_PATH);
}

function expireIdle() {
  if (isStandalone()) return;
  setAuthToken(null);
  backToLogin();
}

/**
 * 令牌变更事件。
 *
 * <p>嵌进来的子应用（数据地图）用自己的后端校验同一个 JWT，需要在续期后拿到新令牌。
 * 这里广播一个事件，由 {@code ProductEmbed} 转发给 iframe —— 比让 iframe 重建
 * （sessionKey 里含 token）保住页面状态，也不必让客户端去轮询 sessionStorage。
 */
export const AUTH_TOKEN_EVENT = 'dw-ai-token-changed';

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
  if (typeof window !== 'undefined') {
    window.dispatchEvent(
      new CustomEvent(AUTH_TOKEN_EVENT, {
        detail: {
          token: sessionStorage.getItem('dw-ai.token') ?? '',
          tokenExp: sessionStorage.getItem('dw-ai.tokenExp') ?? '',
        },
      })
    );
  }
}

/**
 * 宿主推来的新 access token。
 *
 * <p>与本地登录拿到的那条走**同一处存储**（`dw-ai.token`）：请求头、`ProductEmbed`
 * 往数据地图转发，都只看这一处，多一处就得记住「哪个来源优先」。
 *
 * <p>`touch: false`：这是壳续期推过来的，不代表用户此刻有动作，不该靠它推迟空闲登出。
 */
export function applyAccessToken(token: string, tokenExp = ''): void {
  if (!token) return;
  setAuthToken(token, { touch: false });
  // 不传 expiresIn：宿主给的是绝对到期时间戳（毫秒），与 setAuthToken 的「还剩多少秒」
  // 是两种量纲，换算一次就多一个算错的机会。
  if (tokenExp) sessionStorage.setItem('dw-ai.tokenExp', tokenExp);
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
    const res = await fetch(`${API_BASE}/api/auth/refresh`, {
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

/** 轮询等令牌被换掉（宿主回话是异步的，没法 await 那条 postMessage）。 */
function waitForTokenChange(prev: string, timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    const startedAt = Date.now();
    const timer = window.setInterval(() => {
      const current = authToken();
      if (current && current !== prev) {
        window.clearInterval(timer);
        resolve(true);
      } else if (Date.now() - startedAt >= timeoutMs) {
        window.clearInterval(timer);
        resolve(false);
      }
    }, 50);
  });
}

/**
 * 嵌在壳里时，请宿主推一枚新 access token 过来。
 *
 * <p>为什么 embed 态不走本地的 `refreshAccess()`：这里的令牌是**组织平台**签发的，
 * 而 `/api/auth/refresh` 换的是本模块自己签发的（standard 那套）。拿组织签发的
 * refreshToken 去问本模块的后端只会失败，而 `doRefresh()` 失败时会顺手
 * `setAuthToken(null)` —— 把壳刚给的那条也一起清掉，代价比不试还大。
 *
 * <p>动态 import 是刻意的：`config/embed.ts` 要用本模块的 `applyAccessToken`，
 * 静态 import 会形成环。这条路径只在 401 之后走，不是首屏依赖。
 */
async function renewFromHost(): Promise<boolean> {
  if (!isEmbed()) return false;
  const before = authToken();
  const { requestEmbedToken } = await import('../config/embed');
  requestEmbedToken();
  return waitForTokenChange(before, 2000);
}

function isAuthFailure(err: unknown) {
  const status = typeof err === 'object' && err && 'status' in err ? Number((err as { status?: number }).status) : 0;
  if (status === 401) return true;
  const msg = err instanceof Error ? err.message : String(err);
  return /401|unauthorized|session expired|登录已过期|未登录|没有登录/i.test(msg);
}

function asciiHeader(v: string) {
  if (!v) return '';
  if (/^[\x20-\x7E]+$/.test(v)) return v;
  // 非 ASCII 的值塞进 HTTP 头会被浏览器拒绝整条请求，只能丢弃；但要留下线索，别静默
  console.warn('[dw-ai] 请求头含非 ASCII 字符，已丢弃:', v.slice(0, 32));
  return '';
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
    if (!anon && isAuthFailure(e)) {
      // 嵌入态先问宿主（见 renewFromHost 里为什么不能反着来）
      if (await renewFromHost()) return doFetch<T>(path, init);
      if (await refreshAccess()) return doFetch<T>(path, init);
      // 三条路都走不通了。multi 下当场回组织平台重新登录（原页带上）：这次 401 往往
      // 只是令牌到期，页面本身没毛病，让他落回原处比让各处分别显示一句「请求错误 401」有用。
      //
      // 只对 multi 生效 —— standard 的 401 恢复耗尽原本就是把错误抛给调用方，
      // 那是另一个模式的既有口径，这一轮不顺手改它（嵌入态由 backToLogin 自己挡掉）。
      if (isMultiTenant()) backToLogin();
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
  mode: 'dev' | 'oidc';
  allowLogin?: boolean;
  allowDevLogin: boolean;
  casdoorConfigured: boolean;
  sessionEpoch?: string;
  accessTtlSeconds?: number;
  refreshTtlSeconds?: number;
  idleTtlSeconds?: number;
  /**
   * 组织平台的**前端**地址（门户 UI 站点根）；为空 = 后端没配。
   *
   * <p>注意不是后端的 API 基址：这里拼的是页面路径（`/org/login`）。
   * 为空时走 config/product.ts 里 orgOrigin 的下一级回落。
   */
  orgUiUrl?: string;
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
  authConfig: () => req<AuthConfig>('/api/auth/config'),
  login: (username: string, password = '') =>
    req<LoginRes>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  logout: (refreshToken: string) =>
    fetch(`${API_BASE}/api/auth/logout`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refreshToken }),
    }).then(() => undefined),
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
        aiCaps?: string[];
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
        aiCaps?: string[];
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
    aiPrompts: (tenantId: string) => req<AiPromptsDto>(`/api/tenants/${tenantId}/ai-prompts`),
    putAiPrompts: (tenantId: string, overrides: Record<string, string>) =>
      req<AiPromptsDto>(`/api/tenants/${tenantId}/ai-prompts`, {
        method: 'PUT',
        body: JSON.stringify({ overrides }),
      }),
    knowledge: (tenantId: string) => req<KnowledgeArticleDto[]>(`/api/tenants/${tenantId}/knowledge`),
    importKnowledge: (tenantId: string, body: { text: string; filename?: string; mode?: string }) =>
      req<{ articles: KnowledgeArticleDto[]; warnings: string[]; format: string }>(
        `/api/tenants/${tenantId}/knowledge/import`,
        { method: 'POST', body: JSON.stringify(body) }
      ),
    deleteKnowledge: (tenantId: string, engine: string, articleId: string) =>
      req<void>(`/api/tenants/${tenantId}/knowledge/${encodeURIComponent(engine)}/${encodeURIComponent(articleId)}`, {
        method: 'DELETE',
      }),
  },
  knowledge: {
    manuals: (engine?: string) =>
      req<Record<string, unknown>[]>(`/api/knowledge/manuals${engine ? `?engine=${encodeURIComponent(engine)}` : ''}`),
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
    chat: (body: { message: string; slot?: string; projectId?: string; tableId?: string; history?: unknown[] }) =>
      req<{ source: string; text?: string | null; fallback?: boolean; error?: string }>('/api/ai/chat', {
        method: 'POST',
        body: JSON.stringify(body),
      }),
    layerChat: (
      projectId: string,
      layer: string,
      body: { message: string; slot?: string; tableId?: string; history?: unknown[] }
    ) =>
      req<{ source: string; text?: string | null; fallback?: boolean; error?: string }>(
        `/api/projects/${projectId}/layers/${layer}/ai/chat`,
        {
          method: 'POST',
          body: JSON.stringify({ slot: 'model.system', ...body }),
        }
      ),
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
    engines?: string[];
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
    publish: (projectId: string, id: string, body?: { note?: string }) =>
      req<WarehouseTable>(`/api/projects/${projectId}/tables/${id}/publish`, {
        method: 'POST',
        body: JSON.stringify(body ?? {}),
      }),
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
