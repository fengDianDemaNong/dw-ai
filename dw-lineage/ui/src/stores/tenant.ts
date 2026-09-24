import { reactive, readonly } from 'vue';
import { message } from 'ant-design-vue';
import { authAccessToken } from './auth';

/**
 * 当前租户与项目。
 *
 * 这是全站唯一一份跨页面共享的状态，为它引入 pinia 不划算 —— 一个模块级 reactive
 * 就够了，且没有额外依赖。（`OPTIMIZATION_PLAN.md` 方案 H 里说的 pinia 收拢是另一件事：
 * 它要收的是 SQL 解析页与 LineageGraph 之间那十几个 v-model，与这里无关。）
 *
 * 后端 `TenantInterceptor` 在**独立 / 普通模式**下对缺头回落到 1/1，所以这里的默认值
 * 必须与它一致，否则「首次访问」和「清掉 localStorage 后访问」会落到两个不同的租户。
 * （multi 模式不适用：那里缺头是 400，必须由组织侧带 `#boot=` 的 code 进来。）
 */
const STORAGE_KEY = 'sql-tools.tenant';

export const DEFAULT_TENANT_ID = 1;
export const DEFAULT_PROJECT_ID = 1;

export interface TenantState {
  tenantId: number;
  projectId: number;
  tenantCode: string;
  projectCode: string;
  tenantName: string;
  projectName: string;
}

function load(): TenantState {
  const fallback: TenantState = {
    tenantId: DEFAULT_TENANT_ID,
    projectId: DEFAULT_PROJECT_ID,
    tenantCode: '',
    projectCode: '',
    tenantName: '',
    projectName: '',
  };
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return fallback;
    const parsed = JSON.parse(raw);
    // 存的值可能来自上一个版本，或被手工改过，逐项校验后再用
    const tenantId = Number(parsed?.tenantId);
    const projectId = Number(parsed?.projectId);
    if (!Number.isInteger(tenantId) || tenantId <= 0) return fallback;
    if (!Number.isInteger(projectId) || projectId <= 0) return fallback;
    return {
      tenantId,
      projectId,
      tenantCode: String(parsed?.tenantCode ?? ''),
      projectCode: String(parsed?.projectCode ?? ''),
      tenantName: String(parsed?.tenantName ?? ''),
      projectName: String(parsed?.projectName ?? ''),
    };
  } catch {
    // localStorage 被禁用（隐私模式）或内容损坏，都不该让整个应用起不来
    return fallback;
  }
}

const state = reactive<TenantState>(load());

/** 只读视图，避免组件直接改字段绕过持久化。 */
export const tenantState = readonly(state);

/** 供 request 拦截器读取，不走 readonly 包装省一层代理开销。 */
export function currentTenantHeaders(): Record<string, string> {
  const tenant = state.tenantCode || String(state.tenantId);
  const project = state.projectCode || String(state.projectId);
  const headers: Record<string, string> = {
    'X-Tenant-Id': tenant,
    'X-Project-Id': project,
  };
  if (state.tenantCode) headers['X-Tenant-Code'] = state.tenantCode;
  if (state.projectCode) headers['X-Project-Code'] = state.projectCode;
  return headers;
}

/**
 * 组织签发的 access token。
 *
 * <p>由 {@code #boot=} 带进来（壳的 `sessionBoot()` 一直有传），multi 模式下随每个请求
 * 作为 `Authorization` 发出，供后端校验身份。独立 / 普通模式取不到，也就不发 ——
 * 那两种模式后端不要求认证。
 */
const ACCESS_TOKEN_KEY = 'sql-tools.bootToken';
const ACCESS_TOKEN_EXP_KEY = 'sql-tools.bootTokenExp';

export function currentAccessToken(): string {
  return sessionStorage.getItem(ACCESS_TOKEN_KEY) ?? '';
}

/**
 * 更新 token。两个来源：{@code #boot=}（首次），以及嵌壳的 `dw-embed-token` 消息（续期）。
 *
 * <p>为什么要能更新：access token 只有 15 分钟。此前壳换 token 只能靠重建 iframe
 * （{@code ProductEmbed} 的 sessionKey 里含 token），那会把数据地图的页面状态整个丢掉。
 * 走消息通道可以原地换掉，不重载。
 */
export function applyAccessToken(token: string, tokenExp = ''): void {
  if (token) sessionStorage.setItem(ACCESS_TOKEN_KEY, token);
  if (tokenExp) sessionStorage.setItem(ACCESS_TOKEN_EXP_KEY, tokenExp);
}

/**
 * 随请求发出的身份令牌。
 *
 * <p>优先用 standard 下自己登录拿到的（`stores/auth`），没有再退回壳带进来的那条。
 * 两者不会同时存在：登录页只在 standard 出现，而 standard 下没有壳在嵌这一页。
 * 真出现两个都有（同一标签页先嵌壳再被改成 standard）时，自己登录的那条更新鲜，
 * 用它更接近用户此刻的意图。
 */
export function currentAuthHeaders(): Record<string, string> {
  const token = authAccessToken() || currentAccessToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** 组织平台带过来的字符串 id，拦截器按 code 解析。 */
export function applyOrgContext(tenantCode: string, projectCode: string): void {
  if (tenantCode) state.tenantCode = tenantCode;
  if (projectCode) state.projectCode = projectCode;
  persist();
}

/**
 * 租户与项目由组织平台在建项目时推过来（`PUT /internal/v1/projects/{code}`，
 * 见 dw-org 的 `ModuleSyncService`），这里不负责落户。
 *
 * <p>原先此处有个 `ensureOrgProject()`：组织侧还没推到时，前端自己打同一个内部接口自救。
 * 那条路是错的 —— 该接口的调用方是组织（带 module token 的服务间调用），浏览器直打必然
 * 被拒；更糟的是它挂在请求拦截器上、且失败即清缓存，于是每个 API 请求都要陪着多等一次
 * 注定失败的往返。真正兜住同步的一直是组织侧的 fan-out。
 */
function persist(): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
  } catch {
    // 存不下就算了，本次会话内仍然有效
  }
}

/** 仅更新展示用的名称（页面加载后从 /api/context 拿到真实名称时调用）。 */
export function setNames(tenantName: string, projectName: string): void {
  state.tenantName = tenantName || '';
  state.projectName = projectName || '';
  persist();
}

/**
 * 切换租户/项目。
 *
 * 切换后整页重载，而不是逐页失效数据。原因是前端目前没有任何共享状态层：
 * 6 个页面各自 onMounted 拉数据、各自存本地 ref，逐个接入失效逻辑要改 6 个文件且容易漏。
 * 把上一个租户的数据留在页面上，比多花一次重载严重得多。
 * 等日后真上了 pinia，再换成细粒度失效。
 *
 * @param redirectTo 切完要去哪。留空表示原地重载（顶栏的项目切换器就是这样用的）。
 *   「进入项目」要的是从工作台跳到项目层级，光切 id 不够 —— 同一个项目也允许
 *   「进入」，所以那时不能因为 id 没变就提前返回。这种情况数据本来就是对的，
 *   不必重载，直接导航。
 */
export function switchTo(
  tenantId: number,
  projectId: number,
  tenantName = '',
  projectName = '',
  redirectTo = ''
): void {
  if (state.tenantId === tenantId && state.projectId === projectId) {
    if (redirectTo) window.location.href = redirectTo;
    return;
  }
  state.tenantId = tenantId;
  state.projectId = projectId;
  state.tenantCode = '';
  state.projectCode = '';
  state.tenantName = tenantName;
  state.projectName = projectName;
  persist();
  if (redirectTo) window.location.href = redirectTo;
  else window.location.reload();
}

/**
 * 本地存的租户已不存在或被停用时，回落到默认租户并重载。
 *
 * @return 是否触发了重载。调用方据此决定提示是当场弹还是留到重载之后 ——
 *         页面一重载，刚弹出的 toast 就跟着没了
 */
export function resetToDefault(): boolean {
  const alreadyDefault =
    state.tenantId === DEFAULT_TENANT_ID &&
    state.projectId === DEFAULT_PROJECT_ID;
  state.tenantId = DEFAULT_TENANT_ID;
  state.projectId = DEFAULT_PROJECT_ID;
  state.tenantCode = '';
  state.projectCode = '';
  state.tenantName = '';
  state.projectName = '';
  persist();
  // 已经在默认租户上就不必重载，否则会陷入无限刷新
  if (alreadyDefault) return false;
  window.location.reload();
  return true;
}

/**
 * standard 固定单租户：把本地残留的租户选择钉回默认值。
 *
 * <p>为什么要清：库里可能存着旧版本切过的租户号（那时「设置 › 租户」还开着），
 * 界面会据此显示「当前租户」并把 {@code X-Tenant-Id} 发出去 —— 而后端已经忽略这个头，
 * 实际读写的是默认租户。不清掉就是「界面显示租户 c、数据其实是租户 1」这种对不上的状态，
 * 排查起来很难受。
 *
 * <p>为什么<b>只有租户不是默认值时才动</b>：项目是 standard 下保留的维度。
 * 用户在默认租户里选了项目 2，不该被这里顺手重置回项目 1。租户确实变了才一并回落 ——
 * 项目 id 跨租户不通用，换了租户之后原先那个项目号不再确定属于谁。
 *
 * <p>与 {@link resetToDefault} 的区别是<b>不重载</b>：本函数在 main.ts 里、页面渲染之前
 * 调用，当场改 state 就够了；重载只会让启动多绕一圈，而此刻还没有任何数据要失效。
 */
export function pinDefaultTenant(): void {
  if (state.tenantId === DEFAULT_TENANT_ID && !state.tenantCode) return;
  state.tenantId = DEFAULT_TENANT_ID;
  state.projectId = DEFAULT_PROJECT_ID;
  state.tenantCode = '';
  state.projectCode = '';
  state.tenantName = '';
  state.projectName = '';
  persist();
}

/**
 * 「为什么帮你切回了默认租户」这句话的暂存处。
 *
 * 直接 message.warning 再 reload 的话，提示会随着重载一起消失，用户只看到页面
 * 莫名刷新、租户悄悄变了。所以先存进 sessionStorage，重载后的那次启动再弹。
 * 用 session 而不是 local：这是一次性的说明，关掉标签页就不该再出现。
 */
const RESET_NOTICE_KEY = 'sql-tools.tenant.resetNotice';

function noticeAndReset(text: string): void {
  const reloading = (() => {
    try {
      sessionStorage.setItem(RESET_NOTICE_KEY, text);
    } catch {
      // sessionStorage 不可用就退回「弹了也可能看不到」，总比不切回默认强
    }
    return resetToDefault();
  })();

  if (!reloading) {
    // 没重载，提示不会被冲掉，当场弹并清掉暂存
    try {
      sessionStorage.removeItem(RESET_NOTICE_KEY);
    } catch {
      /* 同上 */
    }
    message.warning(text);
  }
}

/** 把上一次重载前存下的说明弹出来。每次启动调一次。 */
function flushResetNotice(): void {
  try {
    const text = sessionStorage.getItem(RESET_NOTICE_KEY);
    if (!text) return;
    sessionStorage.removeItem(RESET_NOTICE_KEY);
    message.warning(text);
  } catch {
    /* sessionStorage 不可用，没有待弹的提示 */
  }
}

/**
 * 启动自检：本地存的租户/项目在后端还在不在、还启不启用。
 *
 * <p>后端拦截器<b>不校验</b>租户是否存在（当前没有登录体系），所以本地存着一个
 * 已被删除的租户号时，请求不会报错，只会安静地什么都查不到 ——「页面全空但没报错」
 * 是最难排查的一类故障。这里主动问一次 /api/context，对不上就回落到默认租户。
 *
 * <p><b>这段逻辑原先长在 TenantSwitcher 组件的 onMounted 里</b>，之所以有效，
 * 是因为那时切换器挂在每页都有的顶栏上。切换器收进「设置 › 基本信息」之后，
 * 再放组件里就只有访问那一页才会自检，等于没有。所以提到这里，由 main.ts
 * 在应用启动时调用一次，与任何 UI 解耦。
 */
export async function verifyContext(): Promise<void> {
  // 上一次自检要说的话（那次说完就重载了，没来得及让人看见）
  flushResetNotice();

  // 动态引入：services/api → utils/request → stores/tenant 已经是一条链，
  // 这里再静态 import services/api 就成环了
  const { getActiveContext } = await import('../services/api');

  try {
    const ctx = await getActiveContext();
    if (!ctx.tenantExists || !ctx.projectExists) {
      noticeAndReset('之前选择的租户/项目已不存在，已切回默认租户');
      return;
    }
    if (!ctx.tenantEnabled || !ctx.projectEnabled) {
      noticeAndReset('之前选择的租户/项目已被停用，已切回默认租户');
      return;
    }
    setNames(ctx.tenantName ?? '', ctx.projectName ?? '');
  } catch {
    // 后端没起来之类的情况：自检失败不该拦住页面，也不该误判成「租户没了」
    // 而把人踢回默认租户 —— 那会在后端重启期间悄悄改掉用户的选择
  }
}
