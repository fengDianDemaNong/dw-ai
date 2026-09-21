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
  if (typeof window !== 'undefined') {
    const q = new URLSearchParams(window.location.search).get('mode');
    const fromQuery = parseMode(q);
    if (fromQuery) {
      window.sessionStorage.setItem(STORAGE_KEY, fromQuery);
      window.sessionStorage.setItem('dw-ai.deployMode', fromQuery);
      return fromQuery;
    }
  }
  const env = readEnv();
  if (env) return env;
  if (typeof window !== 'undefined') {
    const fromApi = parseMode(window.sessionStorage.getItem('dw-ai.deployMode'));
    if (fromApi) return fromApi;
    const stored = parseMode(window.sessionStorage.getItem(STORAGE_KEY));
    if (stored) return stored;
  }
  return 'standalone';
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
