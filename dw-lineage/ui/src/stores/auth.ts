import { reactive, readonly } from 'vue';
import { apiUrl } from '../config/api';

/**
 * standard（普通模式）的本地会话：登录、令牌、续期、登出。
 *
 * <h2>它管什么、不管什么</h2>
 *
 * <p>管「我是谁」：访问令牌、刷新令牌、当前登录者（`me`），以及一条给自己用的登出。
 * <b>不管</b>「我能看哪个租户」—— 租户由请求头决定，那是 `stores/tenant` 的事。
 * 两者刻意分开：租户在 standard 下是本地配置（一套部署一套库），与账号无关。
 *
 * <h2>三种模式下的分工</h2>
 *
 * <pre>
 *   standalone → 没有登录。本模块全部函数都不会被调用（hasSession 恒 false）
 *   standard   → 登录页拿到的令牌存在这里，/api/** 带着它过后端认证
 *   multi      → 令牌由壳通过 #boot= 带进来，存在 stores/tenant 的 sql-tools.bootToken
 * </pre>
 *
 * <p>所以本模块的令牌键（`sql-tools.accessToken` / `refreshToken` / `tokenExp`）与
 * 壳那条链路的键（`sql-tools.bootToken*`）<b>刻意不复用</b>。看起来可以合并成一个，
 * 但两者的来源、生命周期和续期方式都不同：壳给的那条前端<b>没法自己续</b>（只能向壳要），
 * 自己登录的这条可以拿 refresh 换。混在一个键里，401 时就分不清「该去求壳」还是
 * 「该去调 refresh」。分开存，判断依据就是键本身。
 *
 * <h2>为什么登录/续期用裸 fetch 而不是 axios 实例</h2>
 *
 * <p>`utils/request` 的响应拦截器上挂着「401 就用 refresh 重试一次」的逻辑，
 * 而 refresh 自己正是那个 401 时要调用的东西 —— 走实例会递归，还会带上一枚已失效的
 * Authorization 头。登录同理：那个时刻根本还没有令牌。所以这几个端点用裸 fetch，
 * 只共享 `config/api` 里的基址。
 */

/** 自己登录拿到的访问令牌。 */
const ACCESS_KEY = 'sql-tools.accessToken';
/** 刷新令牌。后端库上存的是它的 SHA-256，明文只在本模块出现。 */
const REFRESH_KEY = 'sql-tools.refreshToken';
/** 访问令牌的<b>绝对</b>到期时刻（毫秒时间戳）。 */
const EXP_KEY = 'sql-tools.tokenExp';

/** 提前量：离到期不足这么久就先换，别等请求真的 401 了再补。 */
const REFRESH_AHEAD_MS = 60_000;

export interface Me {
  userId: number;
  username: string;
  displayName: string;
  /** 是否管理员。仅用于决定要不要渲染「账号管理」入口 —— 判权在后端。 */
  admin: boolean;
  runMode: string;
}

/** 登录 / 刷新的响应，字段与后端 `LocalAuthModels.LoginRes` 一一对应。 */
export interface LoginResult {
  token: string;
  refreshToken?: string;
  /** 访问令牌还能活多少<b>秒</b>。 */
  expiresIn?: number;
  userId: number;
  username: string;
  displayName: string;
}

interface AuthState {
  /** 当前登录者。null 表示未登录，或还没拉过（见 loadMe）。 */
  me: Me | null;
}

const state = reactive<AuthState>({ me: null });

/** 只读视图。组件读它渲染，改只能通过下面的函数。 */
export const authState = readonly(state);

// ---------------------------------------------------------------------------
// 存储
//
// 全部包一层 try/catch：隐私模式下 sessionStorage 的读写会直接抛异常，
// 那不该让整个应用起不来 —— 存不下最多是「刷新页面要重新登录」。
// ---------------------------------------------------------------------------

function read(key: string): string {
  try {
    return sessionStorage.getItem(key) ?? '';
  } catch {
    return '';
  }
}

function write(key: string, value: string): void {
  try {
    sessionStorage.setItem(key, value);
  } catch {
    /* 见上 */
  }
}

function drop(key: string): void {
  try {
    sessionStorage.removeItem(key);
  } catch {
    /* 见上 */
  }
}

export function authAccessToken(): string {
  return read(ACCESS_KEY);
}

export function authRefreshToken(): string {
  return read(REFRESH_KEY);
}

export function authTokenExp(): number {
  const n = Number(read(EXP_KEY));
  return Number.isFinite(n) ? n : 0;
}

/**
 * 手上有没有会话凭据。
 *
 * <p>判断「要不要把人送去登录页」用它，而<b>不要</b>只看 access token：
 * access 只有 15 分钟，页面开着不动就会过期，但那时 refresh 还在 ——
 * 凭 access 判会把人无故踢出去重登，而他其实完全不需要。
 */
export function hasSession(): boolean {
  return Boolean(authAccessToken() || authRefreshToken());
}

function storeTokens(res: LoginResult): void {
  write(ACCESS_KEY, res.token);
  if (res.refreshToken) write(REFRESH_KEY, res.refreshToken);
  // 存绝对到期时刻，不存 expiresIn 这个相对值：页面开着不动时，
  // 相对值永远是「还有 900 秒」，看不出它其实早就过期了。
  if (res.expiresIn && res.expiresIn > 0) {
    write(EXP_KEY, String(Date.now() + res.expiresIn * 1000));
  } else {
    drop(EXP_KEY);
  }
}

/**
 * 清掉本地会话。
 *
 * <p>只清自己的三个键，<b>不碰</b> `sql-tools.bootToken*` —— 那几个属于壳那条链路，
 * 在 multi 模式下由壳管；本模块在 standard 下运行时它们本就不存在，顺手清掉反而会让
 * 「同一浏览器先开 multi 再开 standard」这种调试场景丢状态。
 */
export function clearSession(): void {
  drop(ACCESS_KEY);
  drop(REFRESH_KEY);
  drop(EXP_KEY);
  state.me = null;
}

// ---------------------------------------------------------------------------
// 请求
// ---------------------------------------------------------------------------

interface ErrorBody {
  message?: string;
  error?: string;
}

function statusOf(e: unknown): number {
  if (typeof e === 'object' && e !== null && 'status' in e) {
    return Number((e as { status?: number }).status) || 0;
  }
  return 0;
}

function toError(e: unknown, fallback: string): Error {
  return e instanceof Error ? e : new Error(fallback);
}

/**
 * 认证端点的裸请求。
 *
 * <p>错误文案取响应体的 `message` —— 后端的 `GlobalExceptionHandler` 会把
 * `ResponseStatusException.getReason()` 放进这个字段，所以「用户名或密码错误」
 * 「新密码至少 4 位」这类中文提示能直接到达用户。
 *
 * <p>连不上后端时 `fetch` 抛的是 `TypeError: Failed to fetch`，那句话对用户毫无意义，
 * 所以在这里翻成人话。没有 `status` 字段，因此和 HTTP 错误能区分开。
 *
 * <h2>Authorization 默认不发，要显式声明</h2>
 *
 * <p>{@code /api/auth/login|refresh|logout} 在后端 {@code SecurityConfig} 里是
 * {@code permitAll}，看起来带不带令牌都行 —— <b>不是</b>。Spring Security 的
 * {@code BearerTokenAuthenticationFilter} 在<b>授权判定之前</b>就解析请求上的
 * Bearer 令牌，令牌一旦无效（过期、后端重启换了世代、密钥轮换）它就当场
 * commence 401，{@code permitAll} 根本没机会生效。
 *
 * <p>于是「拿 refresh 换新 access」这件事会被它<b>正要去替换的那枚旧 access</b>
 * 挡在门外：刷新请求 401 → {@code doRefresh} 判定 refresh 也不能用了 → 清会话 →
 * 回登录页。表现为访问令牌一过期（15 分钟）就被踢出去一次，而自动续期从来没生效过。
 * 所以刷新令牌的请求上绝不能带访问令牌，登出同理（它靠 body 里的 refresh 认证，
 * 后端 {@code /api/auth/logout} 也不读 access）。
 *
 * <p>反过来 {@code /me|profile|password} <b>必须</b>带 —— 它们不在 {@code PUBLIC_PATHS} 里，
 * 少了就是 401。所以这里默认不发、由调用点按端点显式声明，而不是无条件发。
 */
async function authFetch<T>(
  path: string,
  init: RequestInit & { auth?: boolean } = {}
): Promise<T> {
  // 只有显式声明 { auth: true } 的端点才带令牌 —— /me /profile /password 三个。
  // 其余（login / refresh / logout）**故意不带**，理由见上面那段，最要紧的一条：
  // 刷新令牌的请求上带着一枚已经失效的访问令牌，会让刷新自己被 401 挡掉。
  const { auth, ...rest } = init;
  const token = auth ? authAccessToken() : '';
  let res: Response;
  try {
    res = await fetch(apiUrl(path), {
      ...rest,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(rest.headers ?? {}),
      },
    });
  } catch {
    throw new Error('无法连接后端服务，请确认服务已启动');
  }
  if (!res.ok) {
    let detail = '';
    try {
      const body = (await res.json()) as ErrorBody;
      detail = body?.message || body?.error || '';
    } catch {
      /* 非 JSON（网关错误页之类），保持空 */
    }
    const err = new Error(detail || `请求失败（${res.status}）`) as Error & { status: number };
    err.status = res.status;
    throw err;
  }
  if (res.status === 204) return undefined as T;
  const raw = await res.text();
  if (!raw) return undefined as T;
  return JSON.parse(raw) as T;
}

// ---------------------------------------------------------------------------
// 登录 / 登出 / 续期
// ---------------------------------------------------------------------------

/**
 * 登录。
 *
 * <p>错误文案在这里再包一层，是为了让最常见的两种失败有稳定说法：
 * 401 一律说「用户名或密码错误」—— 后端也<b>刻意</b>把「密码错 / 账号不存在 /
 * 账号已停用」合并成同一个 401 与同一句话，区分开就等于白送一个账号枚举接口。
 * 前端不重新拆开它，只是把后端的文案固定下来，不随 Spring 默认行为漂移。
 *
 * @return 当前登录者（已顺带拉好，调用方不必再调 {@link loadMe}）
 */
export async function login(username: string, password: string): Promise<Me> {
  try {
    const res = await authFetch<LoginResult>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    });
    storeTokens(res);
    return await loadMe();
  } catch (e) {
    const status = statusOf(e);
    if (status === 401) throw new Error('用户名或密码错误');
    if (status === 403) throw new Error('当前运行模式不支持本地登录');
    throw toError(e, '登录失败，请稍后重试');
  }
}

/**
 * 登出。
 *
 * <p>顺序很重要：<b>先</b>把 refresh 交给后端撤销，<b>再</b>清本地。
 * 反过来本地已经没凭据了，那次撤销就只能盲发 —— 而它恰恰是
 * 「这枚 refresh 以后不能再用了」的唯一保障（无状态 JWT 做不到「登出即失效」，
 * 落库存 refresh 就是为了这一刻能撤）。
 *
 * <p>后端失败也照样清本地并正常返回：登出必须永远成功。撤销没成功最多让那枚
 * refresh 还能用到它自然过期，但把用户卡在「点了登出却还在登录态」不可接受。
 */
export async function logout(): Promise<void> {
  const refreshToken = authRefreshToken();
  try {
    if (refreshToken) {
      await authFetch<void>('/api/auth/logout', {
        method: 'POST',
        body: JSON.stringify({ refreshToken }),
      });
    }
  } catch {
    /* 见上：撤销失败不阻止登出 */
  } finally {
    clearSession();
  }
}

let refreshInflight: Promise<boolean> | null = null;

/**
 * 用 refresh 换一枚新的 access。返回是否成功。
 *
 * <p><b>并发去重</b>：一个页面同时发 5 个请求、又都撞上 401 时，只该换一次令牌。
 * 说清这里的去重是为了什么 —— 后端的 refresh 是<b>滑动续期</b>（同一个 refresh
 * 令牌可重复使用，每次顺延过期时间，不是一次性的），所以并发重复调用不会互相
 * 作废、不会出错，纯粹是白打几次网络请求。去重是为了省这点开销与延迟，
 * 不是为了避免错误 —— 别把它当成安全机制。
 */
export function refreshAccess(): Promise<boolean> {
  if (!refreshInflight) {
    refreshInflight = doRefresh().finally(() => {
      refreshInflight = null;
    });
  }
  return refreshInflight;
}

async function doRefresh(): Promise<boolean> {
  const refreshToken = authRefreshToken();
  if (!refreshToken) return false;
  try {
    const res = await authFetch<LoginResult>('/api/auth/refresh', {
      method: 'POST',
      body: JSON.stringify({ refreshToken }),
    });
    if (!res?.token) {
      clearSession();
      return false;
    }
    storeTokens(res);
    return true;
  } catch {
    // 刷新失败意味着这枚 refresh 已经不能用了：过期、被登出删掉、
    // 管理员改过密码（改密码会踢掉全部 refresh）、被停用、或服务重启换了签名密钥。
    // 这些都只能靠重新登录解决，所以直接清会话，别留半个会话让上层反复重试。
    clearSession();
    return false;
  }
}

/**
 * 离到期不足 {@link REFRESH_AHEAD_MS} 了。
 *
 * <p>没有到期信息时返回 false（「不确定」按「先试着发」处理）——
 * 宁可让后端回一个 401 走兜底，也不要凭猜测去多换一次令牌。
 */
export function tokenExpiringSoon(): boolean {
  const exp = authTokenExp();
  if (!exp) return false;
  return Date.now() > exp - REFRESH_AHEAD_MS;
}

// ---------------------------------------------------------------------------
// 当前登录者
// ---------------------------------------------------------------------------

async function fetchMe(): Promise<Me> {
  const me = await authFetch<Me>('/api/auth/me', { auth: true });
  if (!me || typeof me.userId !== 'number') {
    throw new Error('身份响应格式异常');
  }
  return me;
}

/**
 * 拉当前登录者并写进 `authState`。
 *
 * <p>先直接试一次；拿到 401 说明 access 过期了（常见于页面开着不动超过 15 分钟），
 * 换一枚再试一次。两条路都失败就清会话并抛错，让调用方决定是回登录页还是先放行。
 *
 * <p>`me.admin` 决定「账号管理」入口显不显示。这只是渲染便利，不是安全措施 ——
 * 真正的判权在后端 `LocalUserController.requireAdmin()`，前端隐藏入口只是
 * 不让人去点一个必然 403 的页面。
 */
export async function loadMe(): Promise<Me> {
  if (!hasSession()) {
    state.me = null;
    throw new Error('未登录');
  }
  let me: Me | null = null;
  try {
    me = await fetchMe();
  } catch (e) {
    if (statusOf(e) === 401 && (await refreshAccess())) {
      try {
        me = await fetchMe();
      } catch {
        /* 交给下面的统一处理：换过令牌还是拿不到身份，会话就是结束了 */
      }
    }
  }
  if (!me) {
    clearSession();
    throw new Error('登录已失效，请重新登录');
  }
  state.me = me;
  return me;
}

// ---------------------------------------------------------------------------
// 自助：改显示名 / 改自己的密码（对应后端 /api/auth/profile 与 /api/auth/password）
// ---------------------------------------------------------------------------

export async function updateProfile(displayName: string): Promise<Me> {
  const me = await authFetch<Me>('/api/auth/profile', {
    auth: true,
    method: 'PUT',
    body: JSON.stringify({ displayName }),
  });
  state.me = me;
  return me;
}

/**
 * 改自己的密码。后端会验当前密码，并在成功后踢掉<b>全部</b>刷新令牌
 * （包括本次会话正在用的那枚）—— 这是「怀疑密码泄露」时的自救动作，
 * 所以调用方在成功后应当引导用户重新登录。
 */
export async function changePassword(currentPassword: string, newPassword: string): Promise<void> {
  await authFetch<void>('/api/auth/password', {
    auth: true,
    method: 'PUT',
    body: JSON.stringify({ currentPassword, newPassword }),
  });
}
