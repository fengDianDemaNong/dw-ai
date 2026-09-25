/**
 * 后端基址。
 *
 * <p>默认用相对路径，由 nginx / vite dev server 反向代理到后端，
 * 这样构建产物不绑定任何具体主机，也天然规避跨域。
 * 需要直连后端时（例如本地调试指向远端环境），在 `.env.*` 里设 `VITE_API_BASE_URL`。
 *
 * <p>打包态还能在**容器启动时**用环境变量覆盖，不必重新构建镜像 ——
 * 见 config/appConfig.ts 与 ui/docker-entrypoint.d/25-app-config.sh。
 *
 * <h2>为什么单独一个模块，而不是留在 utils/request.ts</h2>
 *
 * <p>登录与令牌续期走的是<b>裸 fetch</b>，不经过 axios 实例（原因见 stores/auth 的说明），
 * 但也需要这个基址。把它留在 request.ts 里，`stores/auth` 就得反过来 import axios 层，
 * 于是 auth → request → tenant → auth 成环。
 */
const ENV_RAW = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '';

/** `.` 与空串都表示「与页面同源」，保持既有默认行为。 */
function normalizePrefix(v: string): string {
  const t = v.trim();
  return !t || t === '.' ? '' : t.replace(/\/+$/, '');
}

let prefix = normalizePrefix(ENV_RAW);

/** axios 的 baseURL：保留 `/` 的相对路径语义。 */
export let API_BASE_URL = prefix || '/';

/**
 * 裸 fetch 拼接用的前缀。
 *
 * <p>去掉结尾斜杠，否则 `'' + '/api/...'` 拼出 `//api/...`（双斜杠）。
 * 多数服务端能容忍，但有些反向代理会把它当成协议相对地址处理，是没必要留的坑。
 */
export let API_PREFIX = prefix;

type BaseListener = (base: string) => void;
const listeners: BaseListener[] = [];

/**
 * 订阅基址变化，注册时立即回调一次当前值。
 *
 * <p>request.ts 用它把新值同步进 axios 实例的 `defaults.baseURL` —— 那里原本是
 * 模块顶层的 `const baseURL = API_BASE_URL`，即**加载时快照**，注入晚了就不生效。
 *
 * <p>用订阅而不是让本文件直接去改 axios 实例：那会引入 api.ts → request.ts 的 import，
 * 与已有的 request.ts → api.ts 构成环（就是本文件顶部说的那个环的变体）。
 */
export function onApiBaseChange(fn: BaseListener): void {
  listeners.push(fn);
  fn(API_BASE_URL);
}

/**
 * 注入运行时配置的后端地址。**必须在 createApp() 之前调用**（见 main.ts）。
 * 空串 = 同源相对路径，沿用改动前的默认行为。
 */
export function configureApiBase(value: string | undefined): void {
  prefix = normalizePrefix(value ?? '');
  API_BASE_URL = prefix || '/';
  API_PREFIX = prefix;
  for (const fn of listeners) fn(API_BASE_URL);
}

/** 拼一个后端绝对/相对地址。`path` 必须以 `/` 开头。 */
export function apiUrl(path: string): string {
  return `${API_PREFIX}${path}`;
}
