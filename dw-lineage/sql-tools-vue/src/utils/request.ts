import axios from 'axios';
import { currentTenantHeaders, ensureOrgProject } from '../stores/tenant';

/**
 * 后端地址。
 *
 * 默认使用相对路径，由 nginx / vite dev server 反向代理到后端，
 * 这样构建产物不绑定任何具体主机，也天然规避跨域。
 * 需要直连后端时（例如本地调试指向远端环境），在 .env.* 中设置 VITE_API_BASE_URL。
 */
const baseURL = import.meta.env.VITE_API_BASE_URL || '/';

/** SQL 血缘解析属于计算密集型请求，大脚本耗时可达数十秒。 */
const TIMEOUT_MS = Number(import.meta.env.VITE_API_TIMEOUT) || 60000;

const instance = axios.create({
  baseURL,
  timeout: TIMEOUT_MS,
});

instance.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.code === 'ECONNABORTED') {
      return Promise.reject(
        new Error(`请求超时（${TIMEOUT_MS / 1000}s），SQL 可能过于复杂，请尝试拆分后重试`)
      );
    }
    if (!error.response) {
      return Promise.reject(new Error('网络异常，请检查网络或后端服务是否可用'));
    }

    const { status, data } = error.response;
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
 * 后端 `TenantInterceptor` 在头缺失时会回落到默认租户，所以即使这里没跑到，
 * 请求也不会失败，只是会落到默认租户上 —— 这也正是为什么不能靠「有没有报错」
 * 来判断租户切换有没有生效，得看 /api/context 的回报。
 */
instance.interceptors.request.use(
  async (config) => {
    await ensureOrgProject();
    Object.entries(currentTenantHeaders()).forEach(([key, value]) => {
      config.headers.set(key, value);
    });
    return config;
  },
  (error) => Promise.reject(error)
);

export default instance;
