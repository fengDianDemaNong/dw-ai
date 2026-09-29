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
  return 'multi';
}

export function getDeployMode(): RunMode {
  return getRunMode();
}

export function setDeployModeHint(mode: RunMode) {
  window.sessionStorage.setItem('dw-ai.deployMode', mode);
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

/**
 * 这一页是不是**被别的页面框起来了**（壳中壳）。
 *
 * <p>dw-org 的嵌入态与 `dw-model` / `dw-lineage` **不一样，别照抄它们**。那两个只会被
 * **跨域**宿主嵌，所以判据可以落 sessionStorage（`?embed=1` 写标记，布局读标记）。
 * 而 org 只会被**自己**嵌（入口页的 Tab 里挂另一个 org 页面），同源 iframe 与父窗口
 * **共享 sessionStorage** —— 子帧写下的标记，父窗口下一次整页刷新（F5）也会读到，
 * 表现为「外壳的侧栏莫名其妙没了」。而布局的 `isEmbed()` 只在 setup 时求值一次，
 * 不刷新根本看不出来，属于最难查的一类。
 *
 * <p>所以这里**不落任何存储**，只看「有没有父窗口、且父窗口与我是同一个源」。
 * 取 `window.top` / `window.self` 只是取句柄、不解引用，跨源与 sandbox 下都不会抛。
 */
export function isEmbed(): boolean {
  if (typeof window === 'undefined') return false;
  // 显式声明这一档：当前设计用不到（org 只被自己嵌），留给将来别的宿主用。
  if (new URLSearchParams(window.location.search).get('embed') === '1') return true;
  if (window.self === window.top) return false;
  try {
    // 跨源父窗口时这一行会抛（句柄拿得到、它的 location 拿不到）—— 兜到 false：
    // 宁可多画一层壳，也不可静默把导航丢掉。
    return window.top?.location.origin === window.location.origin;
  } catch {
    return false;
  }
}
