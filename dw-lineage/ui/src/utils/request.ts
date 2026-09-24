import axios from 'axios';
import type { AxiosError, InternalAxiosRequestConfig } from 'axios';
import { currentAccessToken, currentAuthHeaders, currentTenantHeaders } from '../stores/tenant';
import { hasSession, refreshAccess, tokenExpiringSoon } from '../stores/auth';
import { requestEmbedToken } from '../config/embed';
import { API_BASE_URL } from '../config/api';
import { LOGIN_PATH, requiresLocalLogin } from '../config/pages';
import { isEmbed, isMulti, orgOrigin } from '../config/runtime';

/** 后端地址，见 config/api 的说明。 */
const baseURL = API_BASE_URL;

/** SQL 血缘解析属于计算密集型请求，大脚本耗时可达数十秒。 */
const TIMEOUT_MS = Number(import.meta.env.VITE_API_TIMEOUT) || 60000;

const instance = axios.create({
  baseURL,
  timeout: TIMEOUT_MS,
});

type RetriableConfig = InternalAxiosRequestConfig & { __tokenRetried?: boolean };

/** 等待宿主推来的新 token 到达 sessionStorage（超时则放弃）。 */
function waitForTokenChange(prev: string, timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    const startedAt = Date.now();
    const timer = window.setInterval(() => {
      const current = currentAccessToken();
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

let endingSession = false;

/**
 * 会话彻底结束，整页回登录页。
 *
 * <p>做法是 reload，让路由守卫去算登录页地址 —— 而不是这里自己拼。
 * 拼地址要处理 `BASE_URL` 前缀（部署在子路径下时 pathname 并不是 `/lineage/...`），
 * 守卫那边已经知道怎么算。整页重载还有一个顺带的好处：上一个身份的残留
 * （页面上的数据、内存里的 ref）一并清掉，那正是此刻需要的。
 *
 * <p>两道闸防止转圈：`endingSession` 挡住同一批并发 401 各触发一次重载；
 * 已经在登录页上就直接返回（否则登录页自己发的 401 会把它无限刷新）。
 */
function endSession(): void {
  if (endingSession || typeof window === 'undefined') return;
  if (window.location.pathname.includes(LOGIN_PATH)) return;
  endingSession = true;
  window.location.reload();
}

/**
 * standard 模式的 401：自己拿 refresh 换一枚令牌，换到了原样重试一次。
 *
 * <p>失败就去登录页。这里不区分「令牌过期」和「被停用 / 密码被改过」——
 * 后端刻意都回 401，前端也分辨不了；两种情况的正确动作都是重新登录，
 * 登录页会给出真正的答案。
 */
async function recoverLocal(config: RetriableConfig | undefined): Promise<unknown> {
  if (config && !config.__tokenRetried) {
    config.__tokenRetried = true;
    if (await refreshAccess()) return instance.request(config);
  }
  endSession();
  throw new Error('登录已过期，正在返回登录页');
}

/**
 * multi 模式的 401 = 组织签发的 access token 过期。
 *
 * embed 态：向宿主求一次新 token（宿主侧 ProductEmbed 响应 dw-embed-token-request），
 * 等到了就原样重试一次；等不到或重试仍 401，给出可行动的提示。
 * 整页态：没有宿主可求，带错误信息回组织平台重新登录。
 */
async function recoverFromOrg(config: RetriableConfig | undefined): Promise<unknown> {
  if (isEmbed()) {
    if (config && !config.__tokenRetried) {
      requestEmbedToken();
      const renewed = await waitForTokenChange(currentAccessToken(), 2000);
      if (renewed) {
        config.__tokenRetried = true;
        return instance.request(config);
      }
    }
    throw new Error('登录已过期且未从宿主获得新令牌，请从组织平台重新进入数据地图');
  }
  window.location.assign(`${orgOrigin()}/org/login`);
  throw new Error('登录已过期，正在返回组织平台重新登录');
}

instance.interceptors.response.use(
  (response) => response.data,
  async (error) => {
    if (error.code === 'ECONNABORTED') {
      return Promise.reject(
        new Error(`请求超时（${TIMEOUT_MS / 1000}s），SQL 可能过于复杂，请尝试拆分后重试`)
      );
    }
    if (!error.response) {
      return Promise.reject(new Error('网络异常，请检查网络或后端服务是否可用'));
    }

    const { status, data } = error.response;
    if (status === 401) {
      const config = error.config as RetriableConfig | undefined;
      if (requiresLocalLogin()) return recoverLocal(config);
      if (isMulti()) return recoverFromOrg(config);
    }
    const detail = data?.message || data?.error;
    return Promise.reject(new Error(detail || `请求错误 ${status}`));
  }
);

/**
 * 注入租户标识。
 *
 * 全站 API 函数都走这一个 axios 实例，所以这里是唯一需要改的地方 ——
 * 各个 api.ts 函数的签名一个都不用动。
 *
 * 独立 / 普通模式下后端对缺头是宽松的（回落到默认租户 1/1），没跑到也不会失败；
 * 但 multi 模式下后端要求 `X-Tenant-Code`，缺头直接 400 —— 那里不允许回落，
 * 否则等于把请求当成默认租户放行。所以别用「有没有报错」判断租户切换生效了没有，
 * 要看 /api/context 的回报。
 *
 * 同时也注入 `Authorization`：standard 与 multi 下后端都要校验 JWT（见 SecurityConfig）。
 * 区别只在令牌由谁签发 —— standard 是自己登录拿的（stores/auth），
 * multi 是壳带进来的（stores/tenant）。standalone 两条都没有，也就不发这个头。
 */
instance.interceptors.request.use(
  async (config) => {
    // standard：令牌快到期就先换一枚。不等这次请求真的 401 再补 ——
    // 补的时候这次请求已经失败了，用户会先看到一次无谓的报错或延迟。
    if (requiresLocalLogin() && hasSession() && tokenExpiringSoon()) {
      await refreshAccess();
    }
    Object.entries({ ...currentTenantHeaders(), ...currentAuthHeaders() }).forEach(([key, value]) => {
      config.headers.set(key, value);
    });
    return config;
  },
  (error) => Promise.reject(error)
);

export default instance;
