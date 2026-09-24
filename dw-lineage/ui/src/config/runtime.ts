import { setBootRoles } from './iam';

export type RunMode = 'standalone' | 'standard' | 'multi';

const STORAGE_KEY = 'sql-tools.runMode';

function parseMode(v: string | null | undefined): RunMode | null {
  if (v === 'standalone' || v === 'standard' || v === 'multi') return v;
  return null;
}

export function getRunMode(): RunMode {
  // ?mode= 只在开发态生效（规格 06-runtime-modes：不要靠前端 ?mode= 当生产开关）。
  // 生产里它能把 multi 部署的前端骗成 standalone，表现为页面在、后端全 401。
  if (import.meta.env.DEV && typeof window !== 'undefined') {
    const q = new URLSearchParams(window.location.search).get('mode');
    const fromQuery = parseMode(q);
    if (fromQuery) {
      setRunMode(fromQuery);
      return fromQuery;
    }
  }
  // 顺序要紧：**运行时探测到的优先，构建时写死的兜底**。
  //
  // sessionStorage 里那个值是 `loadRuntime()` 从 `/api/runtime` 问回来的 —— 后端才
  // 知道自己跑在哪个模式。反过来（构建时的 `VITE_RUN_MODE` 压在上面）会这样翻车：
  // 用 `dev:standalone` 起前端、后端却是 standard 时，前端一直按「没有登录这回事」
  // 渲染 —— 摆出工作台、请求也不带令牌 —— 而后端按 standard 把 `/api/**` 全判 401，
  // 界面上只剩一片「请求错误 401」。构建时那个值只在后端没起来时用得上。
  //
  // 注意这个值只在**本次加载里后端确实答过**时才存在：答不上来时 `loadRuntime()`
  // 会把它清掉（见 `clearRunMode`），否则就会读到上一次部署的残留。
  if (typeof window !== 'undefined') {
    const fromApi = parseMode(window.sessionStorage.getItem(STORAGE_KEY));
    if (fromApi) return fromApi;
  }
  const env = parseMode(import.meta.env.VITE_RUN_MODE);
  if (env) return env;
  return 'standard';
}

export function setRunMode(mode: RunMode) {
  window.sessionStorage.setItem(STORAGE_KEY, mode);
}

export function isStandalone(): boolean {
  return getRunMode() === 'standalone';
}

export function isStandardMode(): boolean {
  return getRunMode() === 'standard';
}

export function isMulti(): boolean {
  return getRunMode() === 'multi';
}

export function orgOrigin(): string {
  return (import.meta.env.VITE_ORG_ORIGIN as string | undefined)?.replace(/\/$/, '')
    || 'http://127.0.0.1:5171';
}

export function warehouseOrigin(): string {
  return (import.meta.env.VITE_WAREHOUSE_ORIGIN as string | undefined)?.replace(/\/$/, '')
    || 'http://127.0.0.1:5172';
}

/**
 * 作废「上次问后端问到的模式」。
 *
 * <p>{@link STORAGE_KEY} 的语义是「后端刚告诉我它跑在哪个模式」，只在**本次页面加载里
 * 后端确实答过**时才成立。后端没答上来（没起来 / 没就绪 / 答了个认不出的值）时必须
 * 清掉 —— 留着它，{@link getRunMode} 会继续按**上一次部署**的模式渲染。
 *
 * <p>典型翻车：上次后端跑 standard，那次探测把 {@code standard} 写进了 sessionStorage；
 * 这次只起了 {@code npm run dev:standalone -w sql-tools} 而后端没起，探测连接失败却不作废，前端就
 * 照着残留的 standard 出登录页 —— 而这次根本没有后端可以登录。清掉之后落到构建时的
 * {@code VITE_RUN_MODE}，那才是「后端不在时」的答案。
 *
 * <p>不清用户显式给的值：{@code ?mode=} 每次读 URL 都会重新写回来（见 `getRunMode`）。
 */
function clearRunMode() {
  window.sessionStorage.removeItem(STORAGE_KEY);
}

export async function loadRuntime(): Promise<RunMode> {
  try {
    const res = await fetch('/api/runtime');
    if (res.ok) {
      const body = (await res.json()) as { runMode?: string };
      const mode = parseMode(body.runMode);
      if (mode) {
        setRunMode(mode);
        return mode;
      }
    }
  } catch {
    /* 后端未起来 —— 下面统一作废旧值，回落到构建时模式 */
  }
  clearRunMode();
  return getRunMode();
}

const EMBED_KEY = 'sql-tools.embed';

export function isEmbed(): boolean {
  if (typeof window === 'undefined') return false;
  return (
    sessionStorage.getItem(EMBED_KEY) === '1' ||
    new URLSearchParams(window.location.search).get('embed') === '1'
  );
}

export function consumeBootHash(): void {
  const params = new URLSearchParams(window.location.search);
  if (params.get('embed') === '1') sessionStorage.setItem(EMBED_KEY, '1');
  const raw = window.location.hash.startsWith('#boot=') ? window.location.hash.slice(6) : '';
  if (!raw) return;
  try {
    const boot = JSON.parse(decodeURIComponent(raw)) as {
      token?: string;
      refreshToken?: string;
      tokenExp?: string;
      userId?: string;
      tenantCode?: string;
      projectCode?: string;
      tenant?: string;
      project?: string;
      tenantName?: string;
      projectName?: string;
      roles?: unknown;
      embed?: boolean;
    };
    // 壳给的「我在本项目各产品下的角色」。只用于把菜单画对 —— 写操作后端还会
    // 各自调组织的 authz/check 兜底，所以这份被改也点不进去（见 config/iam.ts）。
    setBootRoles(boot.roles);
    const tenant = boot.tenantCode || boot.tenant;
    const project = boot.projectCode || boot.project;
    if (tenant) sessionStorage.setItem('sql-tools.bootTenant', tenant);
    if (project) sessionStorage.setItem('sql-tools.bootProject', project);
    if (boot.userId) sessionStorage.setItem('sql-tools.bootUser', boot.userId);
    if (boot.tenantName) sessionStorage.setItem('sql-tools.bootTenantName', boot.tenantName);
    if (boot.projectName) sessionStorage.setItem('sql-tools.bootProjectName', boot.projectName);
    if (boot.token) sessionStorage.setItem('sql-tools.bootToken', boot.token);
    if (boot.refreshToken) sessionStorage.setItem('sql-tools.bootRefresh', boot.refreshToken);
    if (boot.tokenExp) sessionStorage.setItem('sql-tools.bootTokenExp', boot.tokenExp);
    if (boot.embed) sessionStorage.setItem(EMBED_KEY, '1');
  } catch {
    /* 忽略损坏的启动参数 */
  }
  history.replaceState(null, '', window.location.pathname + window.location.search);
}
