/** 生产控制台只调 API。未配置 VITE_API_BASE_URL 时：开发态走 Vite `/api` 代理，已登录则同源。 */
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

function expireIdle() {
  setAuthToken(null);
  if (typeof window !== 'undefined' && !window.location.pathname.includes('/login')) {
    window.location.assign('/org/login');
  }
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

/** 门户菜单项：管理面看全部字段，消费面（`api.nav()`）拿到的是它的子集。 */
/** 一条菜单项挂哪个壳：进项目**之前**（工作台）还是**之后**（项目）。 */
export type NavScope = 'workbench' | 'project';

export type NavItemRow = {
  id: string;
  product: string;
  scope: NavScope;
  /** 侧栏分组标题，如「数据地图」。为空 = 不分组。 */
  groupTitle: string;
  label: string;
  icon: string;
  /** 【子应用内】路径，如 /lineage/tables */
  path: string;
  /** 子应用内的权限词，如 `catalog:read`。为空 = 不判权。 */
  perm: string;
  sortOrder: number;
  enabled: boolean;
  createdAt?: string;
};

export type NavItemInput = {
  product?: string;
  scope?: NavScope;
  groupTitle?: string;
  label?: string;
  icon?: string;
  path?: string;
  perm?: string;
  sortOrder?: number;
  enabled?: boolean;
};

/** 空组策略：没有可用菜单时，这个分组是保留（入口置灰并说明）还是整组隐藏。 */
export type NavGroupEmptyPolicy = 'always' | 'hide';

/**
 * 一个侧栏分组的元数据（管理面 `platform.navGroups()` 与消费面 `api.navGroups()` 同一形状）。
 *
 * <p>名字带 `Row` 后缀，与 `NavItemRow` 同一套命名 —— `config/nav.ts` 里的 `NavGroup`
 * 是**渲染后**的分组（带 items），两者不是一回事，别弄混。
 *
 * <p>它与 `NavItemRow.groupTitle` 是**软约束**关系：分组表只是候选清单 + 组间顺序 +
 * 空组策略，菜单项里那个字符串仍是渲染真源。所以这里没有、也不该有 id 之外的关联字段。
 */
export type NavGroupRow = {
  id: string;
  scope: NavScope;
  product: string;
  title: string;
  /** 组【间】顺序；组内顺序仍由菜单项的 `sortOrder` 决定。 */
  sortOrder: number;
  emptyPolicy: NavGroupEmptyPolicy;
  createdAt?: string;
};

export type NavGroupInput = {
  scope?: NavScope;
  product?: string;
  title?: string;
  sortOrder?: number;
  emptyPolicy?: NavGroupEmptyPolicy;
};

/** 改分组名的回执：`renamedItems` 是同事务里跟着改名的菜单条数。 */
export type NavGroupUpdateResult = NavGroupRow & { renamedItems?: number };

/**
 * 删除分组的回执。
 *
 * `referenced` = 仍写着这个名字的菜单条数 —— 删除**不阻塞、不清空**菜单项的分组名
 * （那等于静默改菜单），前端拿它提示「已删除，但有 N 条菜单仍写着这个名字」。
 */
export type NavGroupDeleteResult = { referenced: number };

/** 权限词的一个可选值。`label` 是**产品自己的说法**（「查看目录」），org 侧原样展示。 */
export type PermOption = { value: string; label: string };

/**
 * 某产品自报的权限词全集（`GET /api/v1/platform/product-perms`）。
 *
 * <p>`perms` 为空数组 = 该产品**还没上报词表**（老版本服务，或「服务注册」里没登记它的
 * 页面地址）。这是个必须区分于「产品说它没有任何权限词」的状态：前者要回落到
 * 「可手填 + 提示」，后者才是真的没得选。
 *
 * <p>词表里有**不在任何菜单上**的词（如 `lineage:write` 只用在 SQL 解析页的保存按钮上），
 * 所以它不能由菜单候选聚合出来，必须由产品显式声明。
 */
export type ProductPerms = {
  product: string;
  perms: PermOption[];
};

/** 产品角色管理页的一行（`GET /api/v1/platform/product-roles`）。 */
export type ProductRoleRow = {
  id: string;
  product: string;
  /** 写进 `project_members.role` 的就是它；产品 + 角色码一起构成身份，都不能改。 */
  code: string;
  label: string;
  hint: string;
  /** 该产品的管理角色。每个产品恰有一个 true。 */
  isAdmin: boolean;
  /** 内置角色：不可删（它是 `Perms.java` 那份硬编码矩阵的投影）。 */
  builtin: boolean;
  sortOrder: number;
  createdAt: string;
  perms: PermOption[];
  permCount: number;
};

/** 新建/修改产品角色的请求体。字段缺席 = 不改（`perms` 例外：传了就是全量替换）。 */
export type ProductRoleInput = {
  product?: string;
  code?: string;
  label?: string;
  hint?: string;
  sortOrder?: number;
  isAdmin?: boolean;
  perms?: string[];
};

/** 删除角色的回执。`referenced` = 仍被派了这个角色的成员人次 —— 删除**不阻塞、不改人**。 */
export type ProductRoleDeleteResult = { referenced: number; code: string };

/** 某个服务报上来的一个菜单候选（`{frontendUrl}/menu.json` 里的一项）。 */
export type MenuCandidate = {
  id: string;
  /** 服务自己建议的归属壳；管理员可以改。 */
  scope: NavScope;
  group: string;
  path: string;
  label: string;
  icon: string;
  perm: string;
  sort: number;
};

/**
 * 某个产品的候选拉取结果。**逐产品报告**：一个产品拉失败不影响另一个。
 *
 * `url` 是实际请求的地址 —— 它是「页面地址填错了」时唯一能对的东西。
 */
export type MenuCandidatesOfProduct = {
  product: string;
  url?: string;
  ok: boolean;
  error: string;
  menus: MenuCandidate[];
};

/** 批量新建的逐条回执（`created` / `skipped` 已配置 / `failed` 带 1 起序号）。 */
export type NavBatchResult = {
  created: NavItemRow[];
  skipped: { product: string; path: string; scope: NavScope; reason: string }[];
  failed: { index: number; reason: string }[];
};

/** 服务目录里的一条（`GET /api/services`）：产品码 → 它的站点根。 */
export type ProductService = {
  product: string;
  /** 该产品的前端地址；为空 = 还没在「服务注册」里配。 */
  frontendUrl: string;
};

/** 侧栏要渲染的一条产品菜单（`GET /api/nav`），许可过滤已在服务端做过。 */
export type ProductMenu = {
  product: string;
  /** 挂哪个壳 —— 前端靠这一项把一份清单拆成工作台与项目两套侧栏。 */
  scope: NavScope;
  /** 侧栏分组标题，如「数据地图」。为空 = 不分组。 */
  groupTitle: string;
  label: string;
  icon: string;
  path: string;
  /** 子应用内的权限词；为空 = 不判权。 */
  perm: string;
  /** 该产品页面的前端地址；为空 = 还没在「服务注册」里配。 */
  frontendUrl: string;
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
  /**
   * 当前租户可见的门户菜单。
   *
   * <p>注意路径是 `/api/nav` 而不是 `/api/v1/platform/...`：后者是平台管理员专属，
   * 挂过去普通成员的侧栏会永远是空的（服务端 `NavController` 上有同样的注释）。
   */
  nav: () => req<ProductMenu[]>('/api/v1/nav'),

  /**
   * 当前租户侧栏要用的分组元数据（组间顺序 + 空组策略）。
   *
   * <p>**与 `nav()` 分开拉，不要合并**：两者失败后果不同。菜单拉不到 = 侧栏少入口；
   * 分组拉不到 = 侧栏回落成现在的样子（按首次出现序、无空组）。合成一个请求会让
   * 这两种症状互相掩盖 —— 与 `stores/app.ts` 里 `navItems` 与 `productServices`
   * 分开拉是同一条理由。
   *
   * <p>返回条数**不受租户许可过滤**（服务端 `NavGroupService.groupsFor` 的注释解释了
   * 为什么）：这正是 `emptyPolicy='always'` 能在「模块未开通」时仍出现的前提。
   */
  navGroups: () => req<NavGroupRow[]>('/api/v1/nav/groups'),

  /**
   * 产品服务目录（消费面）：当前租户开通的产品各自的前端地址在哪。
   *
   * <p>与 `nav()` 的分工：菜单回答「侧栏有哪些入口」（按管理员配的菜单项来），
   * 这里回答「每个产品的站点根在哪」（按租户许可来）。「进入项目」按钮要的是后者 ——
   * 开通了许可但还没配菜单项时，菜单里没有这个产品，但按钮仍应该能进去。
   *
   * <p>也别用上面管理面的 `services()`（`/api/v1/platform/services`，平台管理员专属）：
   * 普通成员调它是 403。
   */
  productServices: () => req<ProductService[]>('/api/v1/services'),
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
    resetAdminPassword: (id: string, password: string) =>
      req<void>(`/api/v1/platform/tenants/${id}/reset-admin-password`, {
        method: 'POST',
        body: JSON.stringify({ password }),
      }),
    users: () => req<OrgUser[]>('/api/v1/platform/users'),
    accounts: () => req<OrgUser[]>('/api/v1/platform/accounts'),
    createUser: (body: { username: string; displayName?: string; password: string }) =>
      req<OrgUser>('/api/v1/platform/users', { method: 'POST', body: JSON.stringify(body) }),
    patchUser: (id: string, body: { status?: string; password?: string }) =>
      req<OrgUser>(`/api/v1/platform/users/${id}`, { method: 'PATCH', body: JSON.stringify(body) }),
    appearance: () => req<Appearance>('/api/v1/platform/appearance'),
    putAppearance: (body: Appearance) =>
      req<Appearance>('/api/v1/platform/appearance', { method: 'PUT', body: JSON.stringify(body) }),
    // 存的是产品的「页面地址」，供门户 iframe 嵌入用；不再有后端地址与探活状态
    services: () =>
      req<{ product: string; version?: string; frontendUrl: string }[]>('/api/v1/platform/services'),
    registerService: (body: { product: string; frontendUrl: string; version?: string }) =>
      req<unknown>('/api/v1/platform/services', { method: 'POST', body: JSON.stringify(body) }),
    removeService: (product: string) =>
      req<void>(`/api/v1/platform/services/${encodeURIComponent(product)}`, { method: 'DELETE' }),
    // 门户菜单是平台管理员配的（管理面）；普通成员读的是下面 api.nav()
    navItems: () => req<NavItemRow[]>('/api/v1/platform/nav-items'),
    /**
     * 各服务报上来的菜单候选。
     *
     * <p>由后端去拉各服务登记的**页面地址**（`{frontendUrl}/menu.json`）——
     * 浏览器直接去拉会撞上跨域，而且这些地址在跨机部署时浏览器根本到不了。
     */
    navCandidates: () => req<{ products: MenuCandidatesOfProduct[] }>('/api/v1/platform/nav-candidates'),
    createNavItemsBatch: (items: NavItemInput[]) =>
      req<NavBatchResult>('/api/v1/platform/nav-items/batch', {
        method: 'POST',
        body: JSON.stringify({ items }),
      }),
    createNavItem: (body: NavItemInput) =>
      req<NavItemRow>('/api/v1/platform/nav-items', { method: 'POST', body: JSON.stringify(body) }),
    updateNavItem: (id: string, body: NavItemInput) =>
      req<NavItemRow>(`/api/v1/platform/nav-items/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    deleteNavItem: (id: string) =>
      req<void>(`/api/v1/platform/nav-items/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    // 分组：让管理员把「有哪些分组」提前建好（菜单管理页的「分组管理」抽屉）。
    // 与 navItems 一样是管理面；消费面的只读版本是上面的 api.navGroups()。
    navGroups: (query?: { scope?: NavScope; product?: string }) => {
      const qs = new URLSearchParams();
      if (query?.scope) qs.set('scope', query.scope);
      if (query?.product) qs.set('product', query.product);
      const suffix = qs.toString() ? `?${qs}` : '';
      return req<NavGroupRow[]>(`/api/v1/platform/nav-groups${suffix}`);
    },
    createNavGroup: (body: NavGroupInput) =>
      req<NavGroupRow>('/api/v1/platform/nav-groups', { method: 'POST', body: JSON.stringify(body) }),
    /** 传 `title` 就是改名，服务端会级联更新菜单项的分组名（见返回的 `renamedItems`）。 */
    updateNavGroup: (id: string, body: NavGroupInput) =>
      req<NavGroupUpdateResult>(`/api/v1/platform/nav-groups/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    deleteNavGroup: (id: string) =>
      req<NavGroupDeleteResult>(`/api/v1/platform/nav-groups/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    /**
     * 某产品认哪些权限词（产品自报，见各服务前端 `config/navData.ts` 的 `PERM_OPTIONS`）。
     *
     * <p>配菜单（挂哪个权限）与配产品角色（勾哪些权限）用的是同一份词表 ——
     * 两处若各拿一份，会出现「菜单页拒掉的词、角色页能用」这种对不上的现象。
     */
    productPerms: (product: string) =>
      req<ProductPerms>(`/api/v1/platform/product-perms?product=${encodeURIComponent(product)}`),
    // 产品角色：全局一套、跟产品版本走（规范 06-runtime-modes.md:99）。
    // 租户只在项目里「派」角色，角色定义本身只给平台管理员。
    productRoles: (product?: string) =>
      req<ProductRoleRow[]>(
        `/api/v1/platform/product-roles${product ? `?product=${encodeURIComponent(product)}` : ''}`,
      ),
    createProductRole: (body: ProductRoleInput) =>
      req<ProductRoleRow>('/api/v1/platform/product-roles', { method: 'POST', body: JSON.stringify(body) }),
    updateProductRole: (id: string, body: ProductRoleInput) =>
      req<ProductRoleRow>(`/api/v1/platform/product-roles/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    deleteProductRole: (id: string) =>
      req<ProductRoleDeleteResult>(`/api/v1/platform/product-roles/${encodeURIComponent(id)}`, {
        method: 'DELETE',
      }),
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
