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
import { isEmbed } from '../config/runtime';

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
  // 被框起来的那一页（入口页的 Tab 里嵌的 org 页面）不主导航、不清令牌。
  //
  // <p>同源 iframe 与父窗口**共享 sessionStorage**，下面这一句清掉的是**父页也在用**的
  // `dw-ai.token` / `refreshToken` —— 父页下一次请求必 401、连 refresh 都没得刷，
  // 表现为「外壳莫名其妙被登出」。而 `location.assign` 会把**当前这个 iframe** 导航成
  // 登录页：标签里嵌着一张登录表单，外壳却还显示着登录态。收场留给顶层窗口。
  if (isEmbed()) return;
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

/**
 * 工作台「模块管理」的一行。
 *
 * `explicit = false` 表示**从未配过**这一项，`enabled` / `visibleTo` 只是页面上的初值；
 * 生效侧对没配过的模块不判（见 `NavNodeService` 第三层注释），保存之后才真正开始判。
 * 页面据此给一句提示，否则「显示全开、实际不判」会让人以为保存过。
 */
export type ModulePolicyRow = {
  product: string;
  enabled: boolean;
  visibleTo: string;
  explicit: boolean;
};

export type ComputeEngineRow = { kind: string; enabled: boolean };

/**
 * 计算资源。
 *
 * **没有 token 字段** —— 服务端只回 `hasToken` 布尔，明文（含掩码）都不出服务端；
 * 类型层面就没有它，杜绝某天顺手把响应接上请求又把密文写回去。
 */
export type ComputeConfig = {
  schedulerEnabled: boolean;
  schedulerBaseUrl: string;
  hasToken: boolean;
  schedulerStatus: string;
  schedulerTestedAt: string;
  schedulerNote: string;
  engines: ComputeEngineRow[];
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

/** 一条菜单项挂哪个壳：进项目**之前**（工作台）还是**之后**（项目）。 */
export type NavScope = 'workbench' | 'project';

/**
 * 菜单树里的一个节点 —— **V23 起侧栏与菜单管理共用的形状**。
 *
 * <p>「分组」这个概念没有了：分组原本表达的就是「一层目录」，现在它就是一个 `path`
 * 为空、带 `children` 的节点（用户 2026-09-27：「统一都是菜单，菜单下面还有菜单…
 * 不限制菜单层级」）。所以这里没有 `groupTitle`。
 *
 * <p>五类节点只看几列（见服务端 `NavNodeService` 的类注释）：
 * <ul>
 *   <li>org 自有：`product` 为空 → `path` 是 org 的**完整路由**（可能含 `{code}` 占位符）；</li>
 *   <li>手工复制的产品页面：`product` 非空、`mounted` 为假 → `path` 是**子应用内**路径；</li>
 *   <li>挂载节点：`mounted` 为真 → 内容按 `(product, ref)` 在产品清单里**实时展开**，
 *       此时 `path`/权限词都取自产品那一项，而不是这一行的配置；</li>
 *   <li>入口页（V29）：`entryPage` 为真 → 自己是一排 Tab，一条被挂的菜单一个 Tab。
 *       被挂的菜单**在原位置也还在**（引用，不是父子）；</li>
 *   <li>外链菜单（V29）：`externalUrl` 非空 → 指向平台外的地址。`openMode` 是 `embed`
 *       时 `path` 指向壳内那一页（含 `{node}` 占位符），是 `jump` 时改用 `href`。</li>
 * </ul>
 *
 * <p>`frontendUrl` / `disabled` / `disabledReason` 只有消费面（`api.nav()`）会给：
 * 那是渲染要用的判权结果（服务端替前端判完了，见 `config/sysNav.ts` 的 `toNavItems`）。
 */
export type NavNodeRow = {
  id: string;
  scope: NavScope;
  /** 父节点 id；空串 = 壳下的顶层节点（用户说的「主菜单」），**不是** `null`。 */
  parentId: string;
  label: string;
  /**
   * 空串 = 目录节点（不可点，只用来挂子节点）。
   *
   * <p>入口页与外链（内嵌）这一列是**服务端写的模板**（`/org/workbench/entry/{node}`），
   * `{node}` 由前端用节点 id 替换 —— 与 `{code}` 同一套机制。管理员填不了它，因为 id
   * 是服务端生成的。
   */
  path: string;
  icon: string;
  perm: string;
  sortOrder: number;
  enabled: boolean;
  /** 仅租户管理员可见（只对 org 自有节点有意义）。 */
  adminOnly: boolean;
  /** 产品码；空串 = org 自己的页面。 */
  product: string;
  /** `mounted` 为真时：产品清单里那个节点的 id。 */
  ref: string;
  mounted: boolean;
  emptyPolicy: NavGroupEmptyPolicy;
  /** 入口页（V29）：这一行自己是一排 Tab。 */
  entryPage?: boolean;
  /**
   * 入口页挂进来的 Tab（管理面回显；**只有管理面给**）。**数组顺序就是 Tab 顺序**。
   *
   * <p>V31 之前是两个字段（`linkTargets` + `productLinks`）—— 拆开是因为当时两张表的
   * 顺序来源不同；现在 Tab 顺序由管理员在表单里排，两类混排，只能是一份列表。
   */
  links?: NavEntryLink[];
  /** 外链目标地址；非空 = 这一行是一条外链菜单。 */
  externalUrl?: string;
  /** `embed` = 内嵌进壳；`jump` = 新标签页打开。 */
  openMode?: NavExternalOpenMode;
  /** `none` / `token` / `basic`。**`basic` 不会自动登录**（浏览器限制），只存不填。 */
  authMode?: NavExternalAuthMode;
  /** 配过 token 没有。**明文永远不回传**，只有平台管理员的「复制」接口给。 */
  hasToken?: boolean;
  basicUser?: string;
  hasPassword?: boolean;
  /** 外链独立的可见性开关（两档，原因见 `NavNodeService` 的类注释）。 */
  visibility?: NavExternalVisibility;
  createdAt?: string;
  /** 该节点的产品站点根；空串 = 「服务注册」里还没配。 */
  frontendUrl?: string;
  /** 消费面：外链（`jump`）的目标地址，直接落在 `<a href>` 上。 */
  href?: string;
  external?: boolean;
  disabled?: boolean;
  disabledReason?: string;
  /** 子菜单，层级不限。消费面里已经被服务端按许可与角色裁过一遍。 */
  children?: NavNodeRow[];
  /**
   * **前端合成的只读行**，不是 `nav_nodes` 里的一行。
   *
   * <p>只出现在管理面：挂载行的子菜单来自产品清单（后端 `renderMounted` 根本不读 org 侧
   * 的子行），所以直接读表时那一行下面永远是空的 —— 而侧栏里明明有一整棵。这个标记表示
   * 「这一行是照着产品清单画的」，编辑/停用/删除/新增子菜单一概不给，也不该被当成
   * 父节点候选。
   */
  virtual?: boolean;
};

/**
 * 入口页上的一条 Tab（V31，服务端 `LinkSpec` 的回显形状）。
 *
 * <p>两类引用**合成一份带类型的列表**（原先拆成 `linkTargets` + `productLinks` 两段）：
 * Tab 顺序由数组顺序决定，而两类是混排的 —— 两个数组拼不回一个顺序，也带不了
 * 每条自己的名字。
 *
 * <p>`kind` 决定读哪几个字段，**不要按 id 的形状去猜**：把清单 id 当成 nav id 提交回去，
 * 服务端报的是「要挂的菜单不存在」，与真实原因（分错类了）差得很远。
 */
export type NavEntryLink = {
  /** `node` = 本站菜单（读 `target`）；`product` = 产品清单节点（读 `product` + `ref`）。 */
  kind: 'node' | 'product';
  /** `kind='node'` 时：本站菜单在 `nav_nodes` 里的 id。 */
  target?: string;
  /** `kind='product'` 时：产品码，见 `config/products.ts`。 */
  product?: string;
  /** `kind='product'` 时：产品清单（`{frontendUrl}/menu.json`）里那个节点的 id。 */
  ref?: string;
  /**
   * 这一条 Tab 自己显示的名字。**空 = 没改过名**（用被挂菜单自己的标题），不是「名字为空」。
   *
   * <p>同名菜单挂两条时（比如两个「概况」）只能靠它区分。
   */
  label?: string;
  /** Tab 顺序（服务端按它排，只有回显给）。提交时用不到 —— 数组顺序就是它。 */
  sortOrder?: number;
};

/**
 * 提交用的同一条 Tab：与 {@link NavEntryLink} 只差一个 `sortOrder`。
 *
 * <p>顺序由**数组下标**表达，所以这个字段不提交（服务端也不读它）。
 */
export type NavEntryLinkReq = Omit<NavEntryLink, 'sortOrder'>;

/** 外链菜单的打开方式。 */
export type NavExternalOpenMode = 'embed' | 'jump';

/**
 * 外链菜单的认证方式。
 *
 * <p>`basic` 这一档**不会带来任何自动化**：现代浏览器禁止 `https://user:pass@host` 作为
 * iframe 地址，也无法代填第三方的登录表单。它的实际用途只剩「平台管理员保存备查 + 复制」，
 * 表单里必须写明。
 */
export type NavExternalAuthMode = 'none' | 'token' | 'basic';

/**
 * 外链菜单的可见范围。**只有两档**：后三档（指定产品角色那些）都要 `product` 才能算，
 * 而外链没有产品 —— 这是能力缺失，不是漏做。
 */
export type NavExternalVisibility = 'all' | 'tenant_admin';

/** 入口页的内容（`GET /api/v1/nav/entry/{id}`）。 */
export type NavEntryPage = {
  id: string;
  label: string;
  scope: NavScope;
  /** 表里要列出的菜单。**每一项都走侧栏那一套判权**，看不见的不会出现在这里。 */
  items: NavNodeRow[];
};

/** 一条外链的最终地址（`GET /api/v1/nav/external/{id}`）：token 已由服务端拼好。 */
export type NavExternalTarget = {
  id: string;
  label: string;
  url: string;
  openMode: NavExternalOpenMode;
};

/** 凭据明文（平台管理员专属，`GET /api/v1/platform/nav-nodes/{id}/credential`）。 */
export type NavNodeCredential = {
  id: string;
  label: string;
  authMode: NavExternalAuthMode;
  token: string;
  basicUser: string;
  password: string;
};

/**
 * 新建 / 修改菜单节点的请求体（服务端 `NavNodeService.NavNodeReq` 的镜像）。
 *
 * <p>字段缺席 = 不改（`PATCH` 的语义）；新建时 `scope` 必填，其余由服务端落默认值。
 * 五类节点的填法（见 {@link NavNodeRow}）：
 * <ul>
 *   <li>org 自有页面 / 目录：`product` 留空，`path` 写 org 路由（空串 = 目录）；</li>
 *   <li>手工复制产品页面：给 `product` + 子应用内的 `path`，`mounted` 留假（默认）；</li>
 *   <li>挂载产品节点：给 `product` + `ref`（产品清单里那个节点的 id）并置 `mounted: true`
 *       —— 此时 `path` / 权限词由服务端在渲染时现取，**不要**自己填；</li>
 *   <li>入口页：`entryPage: true` + `linkTargets`（挂本站菜单）+ `productLinks`（挂产品清单里的节点）；</li>
 *   <li>外链菜单：`externalUrl` + `openMode` + `authMode`（+ 凭据）。</li>
 * </ul>
 *
 * <p>入口页与外链的 `path` 都**不要**填：服务端会写成模板（`{node}` 留给前端替换）。
 */
export type NavNodeInput = {
  scope?: NavScope;
  /** 父节点 id；空串 = 壳下的顶层节点（用户说的「主菜单」）。 */
  parentId?: string;
  title?: string;
  /** 空串 = 目录节点。入口页 / 外链不要填（服务端写模板）。 */
  path?: string;
  icon?: string;
  perm?: string;
  /**
   * 同层排序值，**不传**（= 落在同层末尾，服务端取「同层最大 + 10」）。
   *
   * <p>管理页已经完全不发它了：不让管理员手填数字，改用列表里的上移 / 下移
   * （{@link PlatformApi.moveNavNode}）与展示层合成的分层位次。
   *
   * <p>显式传仍然生效，这是给「按既定顺序导入」这类脚本用的 —— 服务端只在字段出现时
   * 才采信（见 `NavNodeService#apply`）。
   */
  sortOrder?: number;
  enabled?: boolean;
  /** 仅租户管理员可见；只能用在 org 自有节点上（`product` 为空），**外链用 `visibility`**。 */
  adminOnly?: boolean;
  /** 产品码；空串 = org 自己的页面。入口页与外链都必须留空。 */
  product?: string;
  /** `mounted` 为真时必填：产品清单里那个节点的 id。 */
  ref?: string;
  mounted?: boolean;
  emptyPolicy?: NavGroupEmptyPolicy;

  // ---- V29：入口页与外链菜单 ----

  /** 这一行是不是一个入口页（自己是一排 Tab）。 */
  entryPage?: boolean;
  /**
   * 入口页挂进来的 Tab（V31）。**数组顺序 = Tab 顺序**，每条可带自己的 `label`（Tab 名）。
   *
   * <p>**全量替换**（两类一起）：不传 = 不改，空数组 = 都清空 —— 在这套语义里它们本来
   * 就是同一张列表。管理页发的就是它。
   *
   * <p>与下面两个老字段**互斥**，同时传服务端会拒（以哪一份为准没有唯一说法）。
   */
  links?: NavEntryLinkReq[];
  /**
   * 入口页挂了哪些菜单的 id。**老写法（V29）**，管理页已不再发它 ——
   * 新写法 {@link links} 才能表达混合顺序与 Tab 名。老客户端与既有测试仍在用。
   *
   * <p>**全量替换语义**：不传 = 不改，空数组 = 清空。两者必须分得开：改了标题但没碰
   * 这一栏时是前者，否则一保存就把表清空了。
   */
  linkTargets?: string[];
  /**
   * 入口页挂的**产品清单节点**。**老写法（V30）**，同上，管理页已不再发。
   *
   * <p>与 {@link linkTargets} 各自独立的全量替换语义：不传 = 那一类不改，空数组 = 清空那一类。
   */
  productLinks?: { product: string; ref: string }[];
  /** 外链目标地址；只允许 http / https。 */
  externalUrl?: string;
  openMode?: NavExternalOpenMode;
  authMode?: NavExternalAuthMode;
  /**
   * token 明文，**只进不出**（响应只给 `hasToken`）。
   *
   * <p>空串 = **保持原值**，不是清空 —— 编辑态这一格本来就是空的（服务端不回显明文），
   * 把空当清空的话改一次标题就会把 token 抹掉。
   */
  token?: string;
  basicUser?: string;
  /** 账号密码认证的密码明文，处置同 `token`。 */
  password?: string;
  visibility?: NavExternalVisibility;
};

/**
 * 空目录策略：目录下的子菜单一个都不可用时（判权全不过、产品拉不到），
 * 这个目录是保留（入口置灰并说明）还是整条隐藏。
 *
 * <p>V23 之前它是「分组」的字段，现在挂在**目录节点**自己身上（`NavNodeRow.emptyPolicy`）
 * —— 分组没了，原来那种「组有策略、组内的菜单项没策略」的两层关系也就跟着塌成一层。
 */
export type NavGroupEmptyPolicy = 'always' | 'hide';

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

/**
 * 角色下拉的一个选项 —— `GET /projects/{id}/member-roles` 的投影。
 *
 * <p>刻意不含 `perms`：那是管理面「产品角色」页要看的权限词清单，成员页只拿它填下拉。
 * 后端也只回这四项（见 `ProjectService.memberRoles`）。
 *
 * <p>`code` 是自定义角色码的来源 —— 别在前端写死 `admin/modeler/viewer` 三值：
 * 写侧 V20 起角色码问产品角色表，后台新建的角色是能派下去的，前端写死会让它
 * **存得进、选不出**。
 */
export type ProjectRoleOption = {
  code: string;
  label: string;
  hint?: string | null;
  isAdmin?: boolean;
};

/**
 * 某个服务报上来的一个菜单候选（`{frontendUrl}/menu.json` 里的一项）。
 *
 * <p><b>V23 起是一棵树</b>：产品可以报任意层级，目录节点有 `children` 且 `path` 为空。
 * 老格式（扁平两层、每一项带 `group`）在服务端就被折成了「目录节点 + 子项」
 * （见 `MenuCandidateService.foldGroups`），所以这里读到 `group` 非空是**不可能的** ——
 * 留着这个字段只为让老服务端的报文仍能解析。
 */
export type MenuCandidate = {
  /** 产品清单里这个节点的 id。挂载（`NavNodeRow.ref`）引用的就是它。 */
  id: string;
  /** 服务自己建议的归属壳；管理员可以改。 */
  scope: NavScope;
  /** @deprecated V23 起恒为空串；分组已经折成父节点了。 */
  group: string;
  /** 空串 = 目录节点（只有子菜单，自己没有页面）。 */
  path: string;
  label: string;
  icon: string;
  perm: string;
  sort: number;
  /** 子菜单，层级不限。 */
  children?: MenuCandidate[];
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

/** 服务目录里的一条（`GET /api/services`）：产品码 → 它的站点根。 */
export type ProductService = {
  product: string;
  /** 该产品的前端地址；为空 = 还没在「服务注册」里配。 */
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
   * 当前租户可见的**菜单树**（消费面）。
   *
   * <p>注意路径是 `/api/nav` 而不是 `/api/v1/platform/...`：后者是平台管理员专属，
   * 挂过去普通成员的侧栏会永远是空的（服务端 `NavController` 上有同样的注释）。
   *
   * <p>返回的是**这个人**的树，不是这个租户的树：挂了权限词的节点已经按他在当前项目下的
   * 角色判过（`X-Project-Id` 由壳带上），所以**切项目必须重拉一次**。
   *
   * <p>以前这里还要再拉一次 `navGroups()` 拿「组间顺序 + 空组策略」—— V23 起那些就是
   * 树里那一行自己的字段（`sortOrder` / `emptyPolicy`），没有第二份需要单独拉的东西，
   * 也就没有「菜单到了、分组没到」这种半成品状态。
   */
  nav: () => req<NavNodeRow[]>('/api/v1/nav'),

  /**
   * 一个入口页的内容（消费面）：表里要列出哪些菜单。
   *
   * <p>每一项都走服务端侧栏那一套判权，所以**看不见的不会出现在这里** ——
   * 前端不必也不该再过滤一次（两个过滤器意味着「表里少一项」有两种成因）。
   */
  navEntry: (id: string) => req<NavEntryPage>(`/api/v1/nav/entry/${encodeURIComponent(id)}`),

  /**
   * 一条外链的最终地址（消费面）：**token 已由服务端拼好**。
   *
   * <p>不在侧栏树里给明文：那样每个页面的侧栏请求都会把凭据下发一遍。这里一次只给
   * 一条外链，且只在真要打开它的时候调。
   */
  navExternal: (id: string) => req<NavExternalTarget>(`/api/v1/nav/external/${encodeURIComponent(id)}`),

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
    /**
     * 门户菜单树的**全树**（管理面，平台管理员）。
     *
     * <p>与消费面 `api.nav()` 的三点不同：这里**不判权、不裁许可、不展开挂载节点** ——
     * 管理页要看到自己配的全部行（含停用、含产品节点），所以每一行都是 `nav_nodes` 里的
     * 真实一行，`children` 也就是真实层级。消费面那棵是「这个人看到的树」，两者别混。
     *
     * <p>`mounted` 的行在管理面只有 `ref`，没有内容 —— 它的子菜单要去 `navCandidates()`
     * 里对着产品清单看（挂载是「窗口」，不是「副本」）。
     */
    navNodes: (query?: { scope?: NavScope }) => {
      const qs = new URLSearchParams();
      if (query?.scope) qs.set('scope', query.scope);
      const suffix = qs.toString() ? `?${qs}` : '';
      return req<NavNodeRow[]>(`/api/v1/platform/nav-nodes${suffix}`);
    },
    /**
     * 各服务报上来的菜单候选。
     *
     * <p>由后端去拉各服务登记的**页面地址**（`{frontendUrl}/menu.json`）——
     * 浏览器直接去拉会撞上跨域，而且这些地址在跨机部署时浏览器根本到不了。
     */
    navCandidates: () => req<{ products: MenuCandidatesOfProduct[] }>('/api/v1/platform/nav-candidates'),
    /**
     * 建一条菜单节点。**一次只建一条** —— V23 后端没有批量端点（旧的那条随
     * `/nav-items/batch` 一起删了），因为「从产品拉取菜单」现在要支持嵌套：
     * 一次导入是一棵子树、父子 id 要在导入过程中传递，前端递归逐条建反而更直白
     * （见 `pages/admin/nav-items.vue` 的 `walkImport`）。
     */
    createNavNode: (body: NavNodeInput) =>
      req<NavNodeRow>('/api/v1/platform/nav-nodes', { method: 'POST', body: JSON.stringify(body) }),
    /** 改标题/图标/顺序/权限词/启停/父子关系/空目录策略/挂载。字段缺席 = 不改。 */
    updateNavNode: (id: string, body: NavNodeInput) =>
      req<NavNodeRow>(`/api/v1/platform/nav-nodes/${encodeURIComponent(id)}`, {
        method: 'PATCH',
        body: JSON.stringify(body),
      }),
    /** 删一个节点**连同整棵子树**；`subtree` 是连带删掉的子孙条数（确认框要用）。 */
    deleteNavNode: (id: string) =>
      req<{ subtree: number }>(`/api/v1/platform/nav-nodes/${encodeURIComponent(id)}`, { method: 'DELETE' }),
    /**
     * 把一个菜单在**同层**里上移 / 下移一格（`delta` = -1 / 1）。
     *
     * <p>请求体里只有 `delta`，`scope` 与 `parentId` 由服务端从被移动的那个节点自身推导。
     * 让调用方传那两个字段的话，「传错层」这类请求在协议上就成立了 —— 它的后果是静默改错
     * 一层：没有报错，只有某处的顺序变了。
     *
     * <p>已经在首 / 末位时返回 `changed: false`（不是错误），调用方据此把按钮置灰。
     * 成功时服务端会把**整层**重编号成 10/20/30，所以调用方拿到结果后应重新拉一次列表。
     */
    moveNavNode: (id: string, delta: -1 | 1) =>
      req<{ changed: boolean; sortOrder?: number }>(
        `/api/v1/platform/nav-nodes/${encodeURIComponent(id)}/move`,
        { method: 'POST', body: JSON.stringify({ delta }) },
      ),
    /**
     * 把一条外链菜单的凭据**明文**读出来，供「复制」用（平台管理员专属）。
     *
     * <p>这是全仓唯一的凭据明文出口，有意开的：账号密码那一档浏览器不允许代填，
     * 不给人复制就完全是个死字段。**消费面没有对应接口** —— 使用者拿不到明文。
     */
    navNodeCredential: (id: string) =>
      req<NavNodeCredential>(`/api/v1/platform/nav-nodes/${encodeURIComponent(id)}/credential`),
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
    /**
     * 外观。`shell` 选哪一套：`workbench`（工作台壳）/ `project`（项目壳）。
     * **不带 = 老口径**（服务端 scope='tenant'）—— dw-model 前端就是这个用法。
     */
    appearance: (tenantId: string, shell?: 'workbench' | 'project') =>
      req<Appearance>(`/api/v1/tenants/${tenantId}/appearance${shell ? `?shell=${shell}` : ''}`),
    putAppearance: (tenantId: string, body: Appearance, shell?: 'workbench' | 'project') =>
      req<Appearance>(`/api/v1/tenants/${tenantId}/appearance${shell ? `?shell=${shell}` : ''}`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    llm: (tenantId: string) => req<LlmConfigDto>(`/api/v1/tenants/${tenantId}/llm`),
    putLlm: (tenantId: string, body: Record<string, unknown>) =>
      req<LlmConfigDto>(`/api/v1/tenants/${tenantId}/llm`, { method: 'PUT', body: JSON.stringify(body) }),
    // 模块策略与计算资源：只在各自的页面里按需拉（两者都只有租户管理员进得来，
    // 塞进每次登录都拉的 session 只是白拉一遍，见 stores/app.ts 的 session 注释）。
    modules: (tenantId: string) => req<ModulePolicyRow[]>(`/api/v1/tenants/${tenantId}/modules`),
    putModules: (tenantId: string, body: { product: string; enabled: boolean; visibleTo: string }[]) =>
      req<ModulePolicyRow[]>(`/api/v1/tenants/${tenantId}/modules`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    compute: (tenantId: string) => req<ComputeConfig>(`/api/v1/tenants/${tenantId}/compute`),
    putCompute: (
      tenantId: string,
      body: {
        schedulerEnabled?: boolean;
        schedulerBaseUrl?: string;
        schedulerToken?: string;
        engines?: ComputeEngineRow[];
      }
    ) =>
      req<ComputeConfig>(`/api/v1/tenants/${tenantId}/compute`, {
        method: 'PUT',
        body: JSON.stringify(body),
      }),
    // 测试连接：失败也回 200，结论在 schedulerStatus / schedulerNote 里。
    testCompute: (tenantId: string) =>
      req<ComputeConfig>(`/api/v1/tenants/${tenantId}/compute/test`, { method: 'POST' }),
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
  /**
   * 项目成员列表。
   *
   * <p>行是 **(项目, 用户, 产品)** 粒度：同一个人在每个已开通产品下各占一行、各有各的角色。
   * 渲染时别按 `userId` 去重 —— 去重等于丢掉产品维，症状是「改了这个产品的角色，
   * 另一个产品的下拉跟着变」，而库里两行其实不一致。
   */
  listMembers: (projectId: string) => req<ProjectMember[]>(`/api/v1/projects/${projectId}/members`),
  /**
   * 派/改一个人在本项目某产品下的角色。
   *
   * <p>`product` 可选：不传时由后端按仓建设兜底（老的「只传 role」调用点零改动）。
   */
  putMember: (projectId: string, userId: string, role: string, product?: string) =>
    req<ProjectMember>(`/api/v1/projects/${projectId}/members/${encodeURIComponent(userId)}`, {
      method: 'PUT',
      body: JSON.stringify(product ? { product, role } : { role }),
    }),
  /**
   * 把一个人移出本项目 —— **所有产品**的角色都会被清掉，不是某一个。
   * 界面上的确认文案必须把这个范围说出来。
   */
  deleteMember: (projectId: string, userId: string) =>
    req<void>(`/api/v1/projects/${projectId}/members/${encodeURIComponent(userId)}`, { method: 'DELETE' }),
  /** 本项目可派的产品角色（成员页角色下拉的选项）。 */
  projectMemberRoles: (projectId: string) =>
    req<ProjectRoleOption[]>(`/api/v1/projects/${projectId}/member-roles`),
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
