/**
 * 后端基址。
 *
 * <p>默认用相对路径，由 nginx / vite dev server 反向代理到后端，
 * 这样构建产物不绑定任何具体主机，也天然规避跨域。
 * 需要直连后端时（例如本地调试指向远端环境），在 `.env.*` 里设 `VITE_API_BASE_URL`。
 *
 * <h2>为什么单独一个模块，而不是留在 utils/request.ts</h2>
 *
 * <p>登录与令牌续期走的是<b>裸 fetch</b>，不经过 axios 实例（原因见 stores/auth 的说明），
 * 但也需要这个基址。把它留在 request.ts 里，`stores/auth` 就得反过来 import axios 层，
 * 于是 auth → request → tenant → auth 成环。
 */
const raw = import.meta.env.VITE_API_BASE_URL as string | undefined;

/** axios 的 baseURL：保留 `/` 的相对路径语义。 */
export const API_BASE_URL = raw || '/';

/**
 * 裸 fetch 拼接用的前缀。
 *
 * <p>去掉结尾斜杠，否则 `'' + '/api/...'` 拼出 `//api/...`（双斜杠）。
 * 多数服务端能容忍，但有些反向代理会把它当成协议相对地址处理，是没必要留的坑。
 */
export const API_PREFIX = (raw || '').replace(/\/+$/, '');

/** 拼一个后端绝对/相对地址。`path` 必须以 `/` 开头。 */
export function apiUrl(path: string): string {
  return `${API_PREFIX}${path}`;
}
