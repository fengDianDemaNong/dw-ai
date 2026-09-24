export type RunMode = 'standalone' | 'standard' | 'multi';
/** @deprecated 用 RunMode */
export type DeployMode = RunMode;

const STORAGE_KEY = 'dw-ai.deploy-mode';
export const DEFAULT_TENANT_ID = 't-xinghe';

function parseMode(v: string | null | undefined): RunMode | null {
  if (v === 'standalone' || v === 'standard' || v === 'multi') return v;
  return null;
}

function readEnv(): RunMode | null {
  return parseMode(import.meta.env.VITE_RUN_MODE) ?? parseMode(import.meta.env.VITE_DEPLOY_MODE);
}

export function getRunMode(): RunMode {
  // ?mode= 只在开发态生效（规格 06-runtime-modes：不要靠前端 ?mode= 当生产开关）。
  // 生产里它能把 multi 部署的前端骗成 standalone：页面按无登录渲染、后端全 401。
  if (import.meta.env.DEV && typeof window !== 'undefined') {
    const q = new URLSearchParams(window.location.search).get('mode');
    const fromQuery = parseMode(q);
    if (fromQuery) {
      window.sessionStorage.setItem(STORAGE_KEY, fromQuery);
      window.sessionStorage.setItem('dw-ai.deployMode', fromQuery);
      return fromQuery;
    }
  }
  // 顺序要紧：**运行时探测到的优先，构建时写死的兜底**。
  //
  // `dw-ai.deployMode` 里的值是后端给的（`bootstrapRemote()` 读 `cfg.runMode` 写入，
  // 登录页发现不一致时也用 `setDeployModeHint()` 纠正）—— 后端才知道自己跑在哪个模式。
  // 反过来的顺序（`VITE_RUN_MODE` 压在上面）会让前端按错误的模式渲染：用
  // `VITE_RUN_MODE=standalone` 起前端、后端却是 standard 时，前端摆出「没有登录这回事」
  // 的界面、请求也不带令牌，而后端把 `/api/**` 全判 401，只剩一片报错。
  if (typeof window !== 'undefined') {
    const fromApi = parseMode(window.sessionStorage.getItem('dw-ai.deployMode'));
    if (fromApi) return fromApi;
    const stored = parseMode(window.sessionStorage.getItem(STORAGE_KEY));
    if (stored) return stored;
  }
  const env = readEnv();
  if (env) return env;
  return 'standard';
}

/** 独立模式无登录；普通模式本进程登录；多租户去组织平台。 */
export function needsLocalLogin(): boolean {
  return getRunMode() === 'standard';
}

export function getDeployMode(): RunMode {
  return getRunMode();
}

export function setDeployModeHint(mode: RunMode) {
  window.sessionStorage.setItem('dw-ai.deployMode', mode);
}

/**
 * 作废「上次问后端问到的模式」。
 *
 * <p>这两个 key 的语义是「后端刚告诉我它跑在哪个模式」，只在**本次页面加载里后端确实
 * 答过**时才成立。后端没答上来（没起来 / 没就绪 / 答了个认不出的值）时必须清掉 ——
 * 留着它，{@link getRunMode} 会继续按**上一次部署**的模式渲染。
 *
 * <p>典型翻车：上次后端跑 standard，那次 {@code bootstrapRemote()} 把 {@code standard}
 * 写进了 sessionStorage；这次只起了无登录的独立模式前端而后端没起，
 * 探测连接失败却不作废，前端就照着残留的 standard 出登录页 —— 而这次根本没有后端可以
 * 登录。清掉之后落到构建时的 {@code VITE_RUN_MODE}，那才是「后端不在时」的答案。
 *
 * <p>两个 key 都清：{@code dw-ai.deployMode} 是现在用的，{@code dw-ai.deploy-mode}
 * 是旧名，{@code getRunMode} 仍然读它，只清一个就会从另一个读到同样的残留。
 */
export function clearDeployMode() {
  window.sessionStorage.removeItem('dw-ai.deployMode');
  window.sessionStorage.removeItem(STORAGE_KEY);
}

export function setDeployMode(mode: RunMode) {
  window.sessionStorage.setItem(STORAGE_KEY, mode);
  window.sessionStorage.setItem('dw-ai.deployMode', mode);
  window.location.reload();
}

export function isStandalone(): boolean {
  return getRunMode() === 'standalone';
}

export function isMultiTenant(): boolean {
  return getRunMode() === 'multi';
}

export function isStandardMode(): boolean {
  return getRunMode() === 'standard';
}
